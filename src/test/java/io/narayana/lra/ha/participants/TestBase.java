package io.narayana.lra.ha.participants;

import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;

import io.narayana.lra.client.internal.NarayanaLRAClient;
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
import java.util.ArrayList;
import java.util.List;
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
    @ConfigProperty(name = "lra.coordinator.urls")
    List<URI> coordinatorUris;

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
    }

    @AfterEach
    void afterEach() {
        for (URI lraToFinish : lrasToAfterFinish) {
            try {
                lraClient.cancelLRA(lraToFinish);
            } catch (Exception ignored) {
            }
        }
        if (client != null) {
            client.close();
        }
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

    protected void holdAfterCurrentPush(URI coordinatorBase, long sleepTime, String clientId, Integer timeoutCount) {
        Response r = null;
        try {
            var target = client.target(coordinatorBase)
                    .path("inject/hold-after-current-push")
                    .queryParam("sleepTime", sleepTime);

            if (clientId != null && !clientId.isBlank()) {
                target = target.queryParam("clientId", clientId);
            }

            if (timeoutCount != null) {
                target = target.queryParam("timeoutCount", timeoutCount);
            }

            r = target.request(MediaType.TEXT_PLAIN).post(null);

            String body = r.hasEntity() ? r.readEntity(String.class) : "";
            Assertions.assertTrue(r.getStatus() >= 200 && r.getStatus() < 300,
                    "Failed to hold on " + coordinatorBase + " status=" + r.getStatus() + " body=" + body);
        } finally {
            if (r != null)
                r.close();
        }
    }

    protected void resetInjection(URI coordinatorBase) {
        Response r = null;
        try {
            r = client.target(coordinatorBase)
                    .path("inject/reset")
                    .request(MediaType.TEXT_PLAIN)
                    .post(null);

            String body = r.hasEntity() ? r.readEntity(String.class) : "";
            Assertions.assertTrue(r.getStatus() >= 200 && r.getStatus() < 300,
                    "Failed to reset injection on " + coordinatorBase + " status=" + r.getStatus() + " body=" + body);
        } finally {
            if (r != null)
                r.close();
        }
    }

    protected void resetInjectionOnAllCoordinators() {
        for (URI c : coordinatorUris) {
            resetInjection(c);
        }
    }
}
