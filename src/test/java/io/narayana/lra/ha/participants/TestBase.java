package io.narayana.lra.ha.participants;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.narayana.lra.LRAConstants;
import io.narayana.lra.client.NarayanaLRAClient;
import io.narayana.lra.ha.proxy.CoordinatorProxyResource;
import io.naryana.lra.ha.LRAParticipant;
import io.quarkus.test.common.QuarkusTestResource;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
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

@QuarkusTestResource(CoordinatorProxyResource.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class TestBase {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    NarayanaLRAClient lraClient;

    protected Client client;
    protected List<URI> lrasToAfterFinish;

    /** Direct URIs of the real coordinator backends, used for fault-injection. */
    protected List<URI> coordinatorUris;

    @Inject
    @ConfigProperty(name = "narayana.lra.base-uri")
    String participantBaseUri;

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

        coordinatorUris = CoordinatorProxyResource.getBackends();

        waitForAllCoordinators(120);
        injectResetAll();
        CoordinatorProxyResource.resetProxyRouting();
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
                    List<String> ids = JSON
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
            String body = r.hasEntity() ? r.readEntity(String.class) : "";
            Assertions.assertTrue(r.getStatus() >= 200 && r.getStatus() < 300,
                    "Failed to reset inject state on " + coordinatorBase
                            + " status=" + r.getStatus() + " body=" + body);
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
            injectReset(base);
        }
    }

    protected void resetProxyRouting() {
        CoordinatorProxyResource.resetProxyRouting();
    }

    protected URI nextRoutedCoordinator() {
        return CoordinatorProxyResource.nextRoutedBackend();
    }

    protected URI ensureCoordinatorAvailability(long atMostSeconds) {
        URI reachable = findReachableCoordinator();
        if (reachable != null) {
            return reachable;
        }

        LoggerFactory.getLogger(getClass())
                .warn("All coordinators unreachable; waiting up to {} s for one to recover...", atMostSeconds);
        return waitForAnyCoordinator(atMostSeconds);
    }

    private URI findReachableCoordinator() {
        for (URI base : coordinatorUris) {
            if (isCoordinatorReachable(base)) {
                return base;
            }
        }
        return null;
    }

    private boolean isCoordinatorReachable(URI base) {
        URI healthUri = URI.create("http://" + base.getHost() + ":" + base.getPort() + "/q/health/ready");
        Response r = null;
        try {
            r = client.target(healthUri).request().get();
            return r.getStatus() == 200;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (r != null)
                r.close();
        }
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
                    URI reachable = findReachableCoordinator();
                    if (reachable != null) {
                        found.set(reachable);
                        return true;
                    }
                    return false;
                });

        return found.get();
    }

    /**
     * Blocks until every coordinator in the cluster reports ready on
     * {@code /q/health/ready}. Use this only in tests that explicitly require
     * the full cluster to be up.
     */
    protected void waitForAllCoordinators(long atMostSeconds) {
        for (URI base : coordinatorUris) {
            Awaitility.await("waiting for coordinator " + base + " to be ready")
                    .atMost(atMostSeconds, TimeUnit.SECONDS)
                    .pollInterval(Duration.ofSeconds(2))
                    .until(() -> isCoordinatorReachable(base));
        }
    }

    /**
     * Polls until {@link #getIdempotentCallCount} reaches at least {@code expected} or the timeout expires.
     * Use this after {@code closeLRA}/{@code cancelLRA} when checking call counts on the idempotent endpoint:
     * the LRA transitions to Closing/Cancelling (leaving the active list) before participants are notified,
     * so {@link #waitForNoActiveLra} alone is not a reliable proxy for "callback was delivered".
     */
    protected void waitForIdempotentCallCount(URI lraId, int expected, long timeoutMs) {
        try {
            Awaitility.await("waiting for idempotent call count >= " + expected)
                    .atMost(Duration.ofMillis(timeoutMs))
                    .pollInterval(Duration.ofMillis(200))
                    .until(() -> getIdempotentCallCount(lraId) >= expected);
        } catch (ConditionTimeoutException ignored) {
            // Callers do the follow-up assertions.
        }
    }

    protected void waitForNoActiveLra(URI lraId, long timeoutMs) {
        String targetLraUid = LRAConstants.getLRAUid(lraId);

        try {
            Awaitility.await("waiting for LRA " + targetLraUid + " to leave the active list")
                    .atMost(Duration.ofMillis(timeoutMs))
                    .pollInterval(Duration.ofMillis(200))
                    .until(() -> getActiveIds().stream()
                            .map(LRAConstants::getLRAUid)
                            .noneMatch(targetLraUid::equals));
        } catch (ConditionTimeoutException ignored) {
        }
    }

    protected URI prepareLra(String clientIdPrefix, String compensatePath, String completePath) {
        return prepareLra(clientIdPrefix, compensatePath, completePath, null);
    }

    protected URI prepareLra(String clientIdPrefix, String compensatePath, String completePath, String statusPath) {
        injectResetAll();
        resetParticipantState();

        URI lra = startLra(clientIdPrefix);
        lrasToAfterFinish.add(lra);

        URI compensate = participantUri(compensatePath);
        URI complete = participantUri(completePath);
        URI recovery;

        if (statusPath == null) {
            recovery = lraClient.enlistCompensator(
                    lra,
                    30L,
                    buildCompensatorLink(compensate, complete),
                    new StringBuilder());
            LoggerFactory.getLogger(getClass())
                    .info("Enrolled compensate={}, complete={}, recoveryUrl={}", compensate, complete, recovery);
        } else {
            URI status = participantUri(statusPath);
            recovery = lraClient.enlistCompensator(
                    lra,
                    30L,
                    buildCompensatorLinkWithStatus(compensate, complete, status),
                    new StringBuilder());
            LoggerFactory.getLogger(getClass())
                    .info("Enrolled compensate={}, complete={}, status={}, recoveryUrl={}",
                            compensate, complete, status, recovery);
        }

        return lra;
    }

    protected URI prepareCompensateLra(String scenario, String compensatePath) {
        return prepareLra(participantClientId(scenario), compensatePath, LRAParticipant.COMPLETE_LRA);
    }

    protected URI prepareCompensateLraAsync(String scenario, String compensatePath, String statusPath) {
        return prepareLra(participantClientId(scenario), compensatePath, LRAParticipant.COMPLETE_LRA, statusPath);
    }

    protected URI prepareCompleteLra(String scenario, String completePath) {
        return prepareLra(participantClientId(scenario), LRAParticipant.COMPENSATE_LRA, completePath);
    }

    protected URI prepareCompleteLraAsync(String scenario, String completePath, String statusPath) {
        return prepareLra(participantClientId(scenario), LRAParticipant.COMPENSATE_LRA, completePath, statusPath);
    }

    protected URI startLra(String clientIdPrefix) {
        URI lra = lraClient.startLRA(null, clientIdPrefix + "-" + System.nanoTime(), 30L, ChronoUnit.SECONDS, true);
        LoggerFactory.getLogger(getClass()).info("Started LRA: {}", lra);
        return lra;
    }

    protected String participantClientId(String scenario) {
        return "io.narayana.lra.ha.LRAParticipant#" + scenario;
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

    protected String buildCompensatorLinkWithStatus(URI compensate, URI complete, URI status) {
        return "<" + compensate.toASCIIString() + ">; rel=\"compensate\"; type=\"text/plain\""
                + ",<" + complete.toASCIIString() + ">; rel=\"complete\"; type=\"text/plain\""
                + ",<" + status.toASCIIString() + ">; rel=\"status\"; type=\"text/plain\"";
    }

    /**
     * Resets all in-memory state in the participant bean (idempotency maps,
     * async state, unreachable counters). Call this at the start of each
     * test for a clean slate.
     */
    protected void resetParticipantState() {
        Response r = null;
        try {
            r = client.target(participantUri(LRAParticipant.RESET_PARTICIPANT_STATE))
                    .request()
                    .post(null);
        } finally {
            if (r != null)
                r.close();
        }
    }

    /**
     * Returns the total number of times an idempotent endpoint (compensate or complete)
     * was called for the given LRA, including coordinator retries.
     */
    protected int getIdempotentCallCount(URI lraId) {
        return client.target(participantUri(LRAParticipant.IDEMPOTENT_CALL_COUNT))
                .queryParam("lraId", lraId.toASCIIString())
                .request()
                .get(Integer.class);
    }

    /**
     * Returns {@code 1} if the idempotent side effect was performed for the given LRA,
     * {@code 0} otherwise.
     */
    protected int getIdempotentWorkDone(URI lraId) {
        return client.target(participantUri(LRAParticipant.IDEMPOTENT_WORK_DONE))
                .queryParam("lraId", lraId.toASCIIString())
                .request()
                .get(Integer.class);
    }

    /** Returns how many times the async endpoint (compensate or complete) was called for the given LRA. */
    protected int getAsyncCallCount(URI lraId) {
        return client.target(participantUri(LRAParticipant.ASYNC_CALL_COUNT))
                .queryParam("lraId", lraId.toASCIIString())
                .request()
                .get(Integer.class);
    }

    /** Returns how many times a status endpoint was polled for the given LRA. */
    protected int getAsyncStatusCallCount(URI lraId) {
        return client.target(participantUri(LRAParticipant.ASYNC_STATUS_CALL_COUNT))
                .queryParam("lraId", lraId.toASCIIString())
                .request()
                .get(Integer.class);
    }

    protected void assertNoActiveLras() {
        List<String> activeIds = getActiveIds();
        long unique = activeIds.stream().distinct().count();
        Assertions.assertEquals(0, unique, "Expected no active LRAs but got: " + activeIds);
    }
}
