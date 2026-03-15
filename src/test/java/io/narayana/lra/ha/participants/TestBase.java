package io.narayana.lra.ha.participants;

import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.narayana.lra.client.NarayanaLRAClient;
import io.naryana.lra.ha.LRAParticipant;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class TestBase {

    @Inject
    NarayanaLRAClient lraClient;

    protected Client client;
    protected List<URI> lrasToAfterFinish;

    @Inject
    @ConfigProperty(name = "lra.coordinator.url")
    List<URI> coordinatorUris;

    @Inject
    @ConfigProperty(name = "narayana.lra.base-uri")
    String participantBaseUri;

    protected List<NarayanaLRAClient> coordinatorClients;

    @AfterAll
    @ActivateRequestContext
    void afterAll() {
        if (lraClient != null) {
            lraClient.close();
        }
    }

    @BeforeEach
    void beforeEach() {
        client = ClientBuilder.newClient();
        lrasToAfterFinish = new ArrayList<>();

        coordinatorClients = coordinatorUris.stream()
                .map(NarayanaLRAClient::new)
                .toList();

        waitForAllCoordinators(120);
    }

    @AfterEach
    void afterEach() {
        for (URI lraToFinish : lrasToAfterFinish) {
            try {
                lraClient.cancelLRA(lraToFinish);
                LoggerFactory.getLogger(getClass())
                        .info("Cleanup request completed for {}", lraToFinish);
            } catch (jakarta.ws.rs.NotFoundException e) {
                LoggerFactory.getLogger(getClass())
                        .info("Cleanup skipped, already gone: {}", lraToFinish);
            } catch (Exception e) {
                LoggerFactory.getLogger(getClass())
                        .error("Cleanup failed for {}", lraToFinish, e);
            }
        }
        if (client != null) {
            client.close();
        }
    }

    protected List<String> getActiveIds() {
        List<String> all = new ArrayList<>();

        for (URI base : coordinatorUris) {
            Response r = null;
            try {
                r = client.target(base)
                        .path("active/ids")
                        .request(MediaType.APPLICATION_JSON)
                        .get();

                if (r.getStatus() == 200) {
                    String json = r.readEntity(String.class);
                    List<String> ids = new ObjectMapper()
                            .readValue(json, new TypeReference<List<String>>() {
                            });
                    all.addAll(ids);
                }
            } catch (Exception e) {
                LoggerFactory.getLogger(getClass())
                        .info("Coordinator {} unreachable (possibly crashed)", base);
            } finally {
                if (r != null)
                    r.close();
            }
        }

        return all;
    }

    protected List<URI> snapshotAllLrasAcrossCoordinators() {
        List<URI> all = new ArrayList<>();
        for (int i = 0; i < coordinatorUris.size(); i++) {
            URI base = coordinatorUris.get(i);
            Response r = null;
            try {
                r = client.target(base)
                        .request(MediaType.APPLICATION_JSON)
                        .get();

                String json = r.readEntity(String.class);
                LoggerFactory.getLogger(getClass())
                        .info("GET {} -> status={} body={}", base, r.getStatus(), json);

            } finally {
                if (r != null)
                    r.close();
            }
        }
        return all;
    }

    protected void logSnapshot(String label, List<URI> ids) {
        LoggerFactory.getLogger(getClass())
                .info("{} total entries (with duplicates): {}", label, ids.size());
        LoggerFactory.getLogger(getClass())
                .info("{}: {}", label, ids);
    }

    protected URI invokeParticipant(URI baseUri,
            URI lraId,
            String resourcePath,
            int expectedStatus,
            MultivaluedMap<String, String> queryParams) {
        Response response = null;
        try {
            var target = client.target(
                    UriBuilder.fromUri(baseUri)
                            .path(LRAParticipant.RESOURCE_PATH)
                            .path(resourcePath)
                            .build());

            if (queryParams != null) {
                for (var e : queryParams.entrySet()) {
                    for (String v : e.getValue()) {
                        target = target.queryParam(e.getKey(), v);
                    }
                }
            }

            Invocation.Builder builder = target.request();

            if (lraId != null) {
                builder.header(LRA_HTTP_CONTEXT_HEADER, lraId.toASCIIString());
            }

            response = builder.get();

            Assertions.assertTrue(response.hasEntity(), "Expected response to contain LRA id or error message");
            String responseMessage = response.readEntity(String.class);

            Assertions.assertEquals(expectedStatus, response.getStatus(), responseMessage);

            return URI.create(responseMessage);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    protected URI invokeParticipant(URI baseUri, URI lraId, String resourcePath, int expectedStatus,
            String... queryKeyVals) {
        MultivaluedMap<String, String> qp = null;

        if (queryKeyVals != null && queryKeyVals.length > 0) {
            Assertions.assertEquals(0, queryKeyVals.length % 2, "Query params must be key/value pairs");
            qp = new MultivaluedHashMap<>();
            for (int i = 0; i < queryKeyVals.length; i += 2) {
                qp.add(queryKeyVals[i], queryKeyVals[i + 1]);
            }
        }

        return invokeParticipant(baseUri, lraId, resourcePath, expectedStatus, qp);
    }

    protected URI invokeParticipant(URI baseUri, URI lraId, String resourcePath, int expectedStatus) {
        return invokeParticipant(baseUri, lraId, resourcePath, expectedStatus, (MultivaluedMap<String, String>) null);
    }

    protected void injectEnable(URI coordinatorBase, String point) {
        callInject(coordinatorBase, point, "enable");
    }

    protected void injectDisable(URI coordinatorBase, String point) {
        callInject(coordinatorBase, point, "disable");
    }

    protected void injectReset(URI coordinatorBase) {
        Response r = null;
        try {
            r = client.target(coordinatorBase)
                    .path("inject/reset")
                    .request(MediaType.TEXT_PLAIN)
                    .post(null);

        } finally {
            if (r != null)
                r.close();
        }
    }

    private void callInject(URI coordinatorBase, String point, String action) {
        Response r = null;
        try {
            r = client.target(coordinatorBase)
                    .path("inject")
                    .path(action)
                    .queryParam("point", point)
                    .request(MediaType.TEXT_PLAIN)
                    .post(null);

            String body = r.hasEntity() ? r.readEntity(String.class) : "";
            Assertions.assertTrue(r.getStatus() >= 200 && r.getStatus() < 300,
                    "Failed to " + action + " inject " + point + " on " + coordinatorBase
                            + " status=" + r.getStatus() + " body=" + body);
        } finally {
            if (r != null)
                r.close();
        }
    }

    protected void injectResetAll() {
        for (URI base : coordinatorUris) {
            try {
                injectReset(base);
            } catch (Exception e) {
                LoggerFactory.getLogger(getClass()).warn("injectReset failed on {}: {}", base, e.toString());
            }
        }
    }

    protected void waitForCoordinator(URI base, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            Response r = null;
            try {
                r = client.target(base)
                        .path("active/ids")
                        .request()
                        .get();

                if (r.getStatus() == 200) {
                    return;
                }
            } catch (Exception ignored) {
            } finally {
                if (r != null)
                    r.close();
            }
        }

        throw new RuntimeException("Coordinator not ready: " + base);
    }

    protected URI firstReachableCoordinator() {
        for (URI base : coordinatorUris) {
            try {
                waitForCoordinator(base, 1_000);
                return base;
            } catch (Exception ignored) {
            }
        }
        // All coordinators appear to be down — wait for any to recover before giving up.
        LoggerFactory.getLogger(getClass())
                .warn("All coordinators unreachable; waiting up to 60 s for one to recover...");
        return waitForAnyCoordinator(60);
    }

    /**
     * Blocks using Awaitility until at least one coordinator responds with HTTP 200,
     * polling every 2 seconds. Throws {@link ConditionTimeoutException} if none
     * recover within {@code atMostSeconds}.
     */
    protected URI waitForAnyCoordinator(long atMostSeconds) {
        AtomicReference<URI> found = new AtomicReference<>();

        Awaitility.await("waiting for any coordinator to recover")
                .atMost(atMostSeconds, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> {
                    for (URI base : coordinatorUris) {
                        Response r = null;
                        try {
                            r = client.target(base)
                                    .path("active/ids")
                                    .request()
                                    .get();
                            if (r.getStatus() == 200) {
                                found.set(base);
                                return true;
                            }
                        } catch (Exception ignored) {
                        } finally {
                            if (r != null)
                                r.close();
                        }
                    }
                    return false;
                });

        return found.get();
    }

    /**
     * Blocks until every coordinator in the cluster responds with HTTP 200.
     * Called in {@link #beforeEach()} so each test starts with a fully healthy cluster,
     * even if a coordinator was crashed by the previous test and is still restarting.
     */
    protected void waitForAllCoordinators(long atMostSeconds) {
        for (URI base : coordinatorUris) {
            Awaitility.await("waiting for coordinator " + base + " to be ready")
                    .atMost(atMostSeconds, TimeUnit.SECONDS)
                    .pollInterval(Duration.ofSeconds(2))
                    .until(() -> {
                        Response r = null;
                        try {
                            r = client.target(base)
                                    .path("active/ids")
                                    .request()
                                    .get();
                            return r.getStatus() == 200;
                        } catch (Exception ignored) {
                            return false;
                        } finally {
                            if (r != null)
                                r.close();
                        }
                    });
        }
    }

    protected URI participantUri(String path) {
        return UriBuilder.fromUri(participantBaseUri)
                .path("lra-participant")
                .path(path)
                .build();
    }

    protected String buildCompensatorLink(URI compensate, URI complete) {
        return "<" + compensate.toASCIIString() + ">; rel=\"compensate\"; type=\"text/plain\""
                + ",<" + complete.toASCIIString() + ">; rel=\"complete\"; type=\"text/plain\"";
    }
}
