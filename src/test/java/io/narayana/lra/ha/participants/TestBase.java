package io.narayana.lra.ha.participants;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.narayana.lra.LRAConstants;
import io.narayana.lra.client.NarayanaLRAClient;
import io.narayana.lra.ha.proxy.CoordinatorProxyResource;
import io.quarkus.narayana.lra.runtime.LRAConfiguration;
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
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

@QuarkusTestResource(CoordinatorProxyResource.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class TestBase implements ParticipantEndpoints {

    private static final Logger LOG = Logger.getLogger(TestBase.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    NarayanaLRAClient lraClient;

    protected Client client;
    protected List<URI> lrasToAfterFinish;

    protected List<URI> coordinatorUris;

    @Inject
    LRAConfiguration lraConfig;

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
                LOG.infof("Cleanup request completed for %s", lraToFinish);
            } catch (jakarta.ws.rs.NotFoundException e) {
                LOG.infof("Cleanup skipped, already gone: %s", lraToFinish);
            } catch (Exception e) {
                LOG.errorf("Cleanup failed for %s", lraToFinish, e);
            }
        }
        if (client != null) {
            client.close();
        }
    }

    protected String participantPath() {
        return "participant";
    }

    protected URI participantUri(String endpoint) {
        String baseUri = lraConfig.baseUri()
                .orElseThrow(() -> new IllegalStateException(
                        "quarkus.lra.base-uri must be set for the test participant callbacks"));
        return UriBuilder.fromUri(baseUri)
                .path(participantPath())
                .path(endpoint)
                .build();
    }

    // -------------------------------------------------------------------------
    // Coordinator helpers
    // -------------------------------------------------------------------------

    protected List<String> getActiveIds() {
        for (URI base : coordinatorUris) {
            Response r = null;
            try {
                r = client.target(base)
                        .path("active/ids")
                        .request(MediaType.APPLICATION_JSON)
                        .get();
                if (r.getStatus() == 200) {
                    String json = r.readEntity(String.class);
                    return JSON.readValue(json, new TypeReference<List<String>>() {
                    });
                }
            } catch (Exception e) {
                LOG.infof("Coordinator %s unreachable (possibly crashed)", base);
            } finally {
                if (r != null)
                    r.close();
            }
        }
        return new ArrayList<>();
    }

    /**
     * Each transaction lives in a single backend's local object store, so a single-backend
     * read misses transactions created elsewhere; this union is needed for the nested cases.
     */
    protected List<String> getAllActiveIdsAcrossCoordinators() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        for (URI base : coordinatorUris) {
            Response r = null;
            try {
                r = client.target(base)
                        .path("active/ids")
                        .request(MediaType.APPLICATION_JSON)
                        .get();
                if (r.getStatus() == 200) {
                    String json = r.readEntity(String.class);
                    all.addAll(JSON.readValue(json, new TypeReference<List<String>>() {
                    }));
                }
            } catch (Exception ignored) {
            } finally {
                if (r != null)
                    r.close();
            }
        }
        return new ArrayList<>(all);
    }

    protected boolean isLraActiveAnywhere(URI lraId) {
        String targetUid = LRAConstants.getLRAUid(lraId);
        return getAllActiveIdsAcrossCoordinators().stream()
                .map(LRAConstants::getLRAUid)
                .anyMatch(targetUid::equals);
    }

    protected void enableFailurePoint(URI coordinatorBase, String point) {
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
        LOG.warnf("All coordinators unreachable; waiting up to %s s for one to recover...", atMostSeconds);
        return waitForAnyCoordinator(atMostSeconds);
    }

    private URI findReachableCoordinator() {
        for (URI base : coordinatorUris) {
            if (isCoordinatorReachable(base))
                return base;
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

    protected void waitForAllCoordinators(long atMostSeconds) {
        for (URI base : coordinatorUris) {
            Awaitility.await("waiting for coordinator " + base + " to be ready")
                    .atMost(atMostSeconds, TimeUnit.SECONDS)
                    .pollInterval(Duration.ofSeconds(2))
                    .until(() -> isCoordinatorReachable(base));
        }
    }

    protected void waitForIdempotentCallCount(URI lraId, int expected, long timeoutMs) {
        try {
            Awaitility.await("waiting for idempotent call count >= " + expected)
                    .atMost(Duration.ofMillis(timeoutMs))
                    .pollInterval(Duration.ofMillis(200))
                    .until(() -> getIdempotentCallCount(lraId) >= expected);
        } catch (ConditionTimeoutException ignored) {
        }
    }

    /**
     * Polls every backend because the transaction lives in only one local object store and
     * a single-backend read can miss it.
     */
    protected void waitForNoActiveLra(URI lraId, long timeoutMs) {
        String targetLraUid = LRAConstants.getLRAUid(lraId);
        try {
            Awaitility.await("waiting for LRA " + targetLraUid + " to leave the cluster active list")
                    .atMost(Duration.ofMillis(timeoutMs))
                    .pollInterval(Duration.ofMillis(200))
                    .until(() -> getAllActiveIdsAcrossCoordinators().stream()
                            .map(LRAConstants::getLRAUid)
                            .noneMatch(targetLraUid::equals));
        } catch (ConditionTimeoutException ignored) {
        }
    }

    protected void waitForForgetCallCount(URI lraId, int expected, long timeoutMs) {
        try {
            Awaitility.await("waiting for forget call count >= " + expected)
                    .atMost(Duration.ofMillis(timeoutMs))
                    .pollInterval(Duration.ofMillis(200))
                    .until(() -> getForgetCallCount(lraId) >= expected);
        } catch (ConditionTimeoutException ignored) {
        }
    }

    protected void waitForStatusIntermediateCompensateCallCount(URI lraId, int expected, long timeoutMs) {
        try {
            Awaitility.await("waiting for status-intermediate-compensate call count >= " + expected)
                    .atMost(Duration.ofMillis(timeoutMs))
                    .pollInterval(Duration.ofMillis(200))
                    .until(() -> getStatusIntermediateCompensateCallCount(lraId) >= expected);
        } catch (ConditionTimeoutException ignored) {
        }
    }

    protected void waitForStatusIntermediateCompleteCallCount(URI lraId, int expected, long timeoutMs) {
        try {
            Awaitility.await("waiting for status-intermediate-complete call count >= " + expected)
                    .atMost(Duration.ofMillis(timeoutMs))
                    .pollInterval(Duration.ofMillis(200))
                    .until(() -> getStatusIntermediateCompleteCallCount(lraId) >= expected);
        } catch (ConditionTimeoutException ignored) {
        }
    }

    protected void assertNoActiveLras() {
        List<String> activeIds = getActiveIds();
        Assertions.assertEquals(0, activeIds.size(), "Expected no active LRAs but got: " + activeIds);
    }

    protected URI prepareLra(String clientIdPrefix, String compensatePath, String completePath) {
        return prepareLra(null, clientIdPrefix, compensatePath, completePath, null, null);
    }

    protected URI prepareLra(String clientIdPrefix, String compensatePath, String completePath, String statusPath) {
        return prepareLra(null, clientIdPrefix, compensatePath, completePath, null, statusPath);
    }

    protected URI prepareLra(
            String clientIdPrefix,
            String compensatePath,
            String completePath,
            String forgetPath,
            String statusPath) {
        return prepareLra(null, clientIdPrefix, compensatePath, completePath, forgetPath, statusPath);
    }

    protected URI prepareLra(
            URI parentLRA,
            String clientIdPrefix,
            String compensatePath,
            String completePath,
            String forgetPath,
            String statusPath) {
        injectResetAll();

        URI lra = startLra(parentLRA, clientIdPrefix);
        lrasToAfterFinish.add(lra);

        URI compensate = participantUri(compensatePath);
        URI complete = participantUri(completePath);
        URI forget = forgetPath == null ? null : participantUri(forgetPath);
        URI status = statusPath == null ? null : participantUri(statusPath);
        URI recovery = lraClient.joinLRA(lra, 30L, compensate, complete, forget, null, null, status,
                new StringBuilder());

        LOG.infof("Enrolled compensate=%s, complete=%s, forget=%s, status=%s, recoveryUrl=%s",
                compensate, complete, forget, status, recovery);
        return lra;
    }

    protected URI prepareCompensateLra(String scenario, String compensatePath) {
        return prepareLra(participantClientId(scenario), compensatePath, COMPLETE);
    }

    protected URI prepareCompensateLraAsync(String scenario, String compensatePath, String statusPath) {
        return prepareLra(participantClientId(scenario), compensatePath, COMPLETE, statusPath);
    }

    protected URI prepareCompensateLraAsyncWithForget(String scenario, String statusPath) {
        return prepareLra(
                participantClientId(scenario),
                COMPENSATE_ASYNC,
                COMPLETE,
                FORGET,
                statusPath);
    }

    protected URI prepareCompleteLra(String scenario, String completePath) {
        return prepareLra(participantClientId(scenario), COMPENSATE, completePath);
    }

    protected URI prepareCompleteLraAsync(String scenario, String completePath, String statusPath) {
        return prepareLra(participantClientId(scenario), COMPENSATE, completePath, statusPath);
    }

    protected URI prepareCompleteLraAsyncWithForget(String scenario, String statusPath) {
        return prepareLra(
                participantClientId(scenario),
                COMPENSATE,
                COMPLETE_ASYNC,
                FORGET,
                statusPath);
    }

    protected URI startLra(String clientIdPrefix) {
        return startLra(null, clientIdPrefix);
    }

    protected URI startLra(URI parentLRA, String clientIdPrefix) {
        URI lra = lraClient.startLRA(parentLRA, clientIdPrefix + "-" + System.nanoTime(), 30L, ChronoUnit.SECONDS, true);
        LOG.infof("Started LRA: %s", lra);
        return lra;
    }

    protected String participantClientId(String scenario) {
        return getClass().getSimpleName() + "#" + scenario;
    }

    protected String buildCompensatorLink(URI compensate, URI complete) {
        return "<" + compensate.toASCIIString() + ">; rel=\"compensate\"; type=\"text/plain\""
                + ",<" + complete.toASCIIString() + ">; rel=\"complete\"; type=\"text/plain\"";
    }

    protected int getIdempotentCallCount(URI lraId) {
        return client.target(participantUri(IDEMPOTENT_CALL_COUNT))
                .queryParam("lraId", lraId.toASCIIString())
                .request().get(Integer.class);
    }

    protected int getIdempotentWorkDone(URI lraId) {
        return client.target(participantUri(IDEMPOTENT_WORK_DONE))
                .queryParam("lraId", lraId.toASCIIString())
                .request().get(Integer.class);
    }

    protected int getAsyncCallCount(URI lraId) {
        return client.target(participantUri(ASYNC_CALL_COUNT))
                .queryParam("lraId", lraId.toASCIIString())
                .request().get(Integer.class);
    }

    protected int getAsyncStatusCallCount(URI lraId) {
        return client.target(participantUri(ASYNC_STATUS_CALL_COUNT))
                .queryParam("lraId", lraId.toASCIIString())
                .request().get(Integer.class);
    }

    protected int getForgetCallCount(URI lraId) {
        return client.target(participantUri(FORGET_CALL_COUNT))
                .queryParam("lraId", lraId.toASCIIString())
                .request().get(Integer.class);
    }

    protected int getStatusGoneCallCount(URI lraId) {
        return client.target(participantUri(STATUS_GONE_CALL_COUNT))
                .queryParam("lraId", lraId.toASCIIString())
                .request().get(Integer.class);
    }

    protected int getStatusIntermediateCompensateCallCount(URI lraId) {
        return client.target(participantUri(STATUS_INTERMEDIATE_COMPENSATE_CALL_COUNT))
                .queryParam("lraId", lraId.toASCIIString())
                .request().get(Integer.class);
    }

    protected int getStatusIntermediateCompleteCallCount(URI lraId) {
        return client.target(participantUri(STATUS_INTERMEDIATE_COMPLETE_CALL_COUNT))
                .queryParam("lraId", lraId.toASCIIString())
                .request().get(Integer.class);
    }

    protected int getAsyncCompensateCallCount(URI lraId) {
        return client.target(participantUri(ASYNC_COMPENSATE_CALL_COUNT))
                .queryParam("lraId", lraId.toASCIIString())
                .request().get(Integer.class);
    }

    protected int getAsyncCompleteCallCount(URI lraId) {
        return client.target(participantUri(ASYNC_COMPLETE_CALL_COUNT))
                .queryParam("lraId", lraId.toASCIIString())
                .request().get(Integer.class);
    }

    protected String getAfterLraStatus(URI lraId) {
        return client.target(participantUri(AFTER_STATUS))
                .queryParam("lraId", lraId.toASCIIString())
                .request().get(String.class);
    }

    protected int getAfterCallCount(URI lraId) {
        return client.target(participantUri(AFTER_CALL_COUNT))
                .queryParam("lraId", lraId.toASCIIString())
                .request().get(Integer.class);
    }

    protected int getAfterIdempotentCallCount(URI lraId) {
        return client.target(participantUri(AFTER_IDEMPOTENT_CALL_COUNT))
                .queryParam("lraId", lraId.toASCIIString())
                .request().get(Integer.class);
    }

    protected int getAfterWorkDone(URI lraId) {
        return client.target(participantUri(AFTER_WORK_DONE))
                .queryParam("lraId", lraId.toASCIIString())
                .request().get(Integer.class);
    }

    protected void waitForAfterCallCount(URI lraId, int expected, long timeoutMs) {
        try {
            Awaitility.await("waiting for @AfterLRA call count >= " + expected)
                    .atMost(Duration.ofMillis(timeoutMs))
                    .pollInterval(Duration.ofMillis(200))
                    .until(() -> getAfterCallCount(lraId) >= expected);
        } catch (ConditionTimeoutException ignored) {
        }
    }

    protected URI prepareLraWithAfterLra(
            String clientIdPrefix,
            String compensatePath,
            String completePath,
            String afterPath) {
        injectResetAll();

        URI lra = startLra(clientIdPrefix);
        lrasToAfterFinish.add(lra);

        URI compensate = participantUri(compensatePath);
        URI complete = participantUri(completePath);
        URI after = participantUri(afterPath);

        URI recovery = lraClient.joinLRA(lra, 30L, compensate, complete, null, null, after, null,
                new StringBuilder());

        LOG.infof("Enrolled compensate=%s, complete=%s, after=%s, recoveryUrl=%s",
                compensate, complete, after, recovery);
        return lra;
    }

    // -------------------------------------------------------------------------
    // Nested LRA helpers — return the nested URI; the parent is added to the
    // cleanup list so afterEach tears down the whole hierarchy.
    // -------------------------------------------------------------------------

    protected URI startTopLra(String scenario) {
        URI parent = startLra(participantClientId(scenario) + "-parent");
        lrasToAfterFinish.add(parent);
        return parent;
    }

    protected URI startNestedLra(URI parent, String scenario) {
        URI nested = startLra(parent, participantClientId(scenario) + "-nested");
        lrasToAfterFinish.add(nested);
        return nested;
    }

    protected URI prepareNestedLra(
            URI parent,
            String scenario,
            String compensatePath,
            String completePath) {
        return prepareLra(parent, participantClientId(scenario), compensatePath, completePath, null, null);
    }

    protected URI prepareNestedLra(
            URI parent,
            String scenario,
            String compensatePath,
            String completePath,
            String statusPath) {
        return prepareLra(parent, participantClientId(scenario), compensatePath, completePath, null, statusPath);
    }

    protected URI prepareNestedLra(
            URI parent,
            String scenario,
            String compensatePath,
            String completePath,
            String forgetPath,
            String statusPath) {
        return prepareLra(parent, participantClientId(scenario), compensatePath, completePath, forgetPath, statusPath);
    }

    protected URI prepareNestedLraWithAfterLra(
            URI parent,
            String scenario,
            String compensatePath,
            String completePath,
            String afterPath) {
        URI nested = startLra(parent, participantClientId(scenario) + "-nested");
        lrasToAfterFinish.add(nested);

        URI compensate = participantUri(compensatePath);
        URI complete = participantUri(completePath);
        URI after = participantUri(afterPath);

        URI recovery = lraClient.joinLRA(nested, 30L, compensate, complete, null, null, after, null,
                new StringBuilder());

        LOG.infof("Enrolled NESTED compensate=%s, complete=%s, after=%s, parent=%s, nested=%s, recoveryUrl=%s",
                compensate, complete, after, parent, nested, recovery);
        return nested;
    }
}
