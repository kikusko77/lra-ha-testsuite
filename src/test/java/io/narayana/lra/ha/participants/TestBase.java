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
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
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

    protected static final long LRA_GONE_HAPPY_PATH_MS = 10_000;
    protected static final long LRA_GONE_AFTER_RECOVERY_MS = 30_000;

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

    protected List<String> getActiveIds() {
        for (URI base : coordinatorUris) {
            Response response = null;
            try {
                response = client.target(base)
                        .path("active/ids")
                        .request(MediaType.APPLICATION_JSON)
                        .get();
                if (response.getStatus() == 200) {
                    String json = response.readEntity(String.class);
                    return JSON.readValue(json, new TypeReference<List<String>>() {
                    });
                }
            } catch (Exception e) {
                LOG.infof("Coordinator %s unreachable (possibly crashed)", base);
            } finally {
                if (response != null)
                    response.close();
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
            Response response = null;
            try {
                response = client.target(base)
                        .path("active/ids")
                        .request(MediaType.APPLICATION_JSON)
                        .get();
                if (response.getStatus() == 200) {
                    String json = response.readEntity(String.class);
                    all.addAll(JSON.readValue(json, new TypeReference<List<String>>() {
                    }));
                }
            } catch (Exception ignored) {
            } finally {
                if (response != null)
                    response.close();
            }
        }
        return new ArrayList<>(all);
    }

    protected void enableFailurePoint(URI coordinatorBase, String point) {
        callInject(coordinatorBase, point, "enable");
    }

    protected void disableFailurePoint(URI coordinatorBase, String point) {
        callInject(coordinatorBase, point, "disable");
    }

    protected void injectReset(URI coordinatorBase) {
        Response response = null;
        try {
            response = client.target(coordinatorBase)
                    .path("inject/reset")
                    .request(MediaType.TEXT_PLAIN)
                    .post(null);
            String body = response.hasEntity() ? response.readEntity(String.class) : "";
            Assertions.assertTrue(response.getStatus() >= 200 && response.getStatus() < 300,
                    "Failed to reset inject state on " + coordinatorBase
                            + " status=" + response.getStatus() + " body=" + body);
        } finally {
            if (response != null)
                response.close();
        }
    }

    private void callInject(URI coordinatorBase, String point, String action) {
        Response response = null;
        try {
            response = client.target(coordinatorBase)
                    .path("inject")
                    .path(action)
                    .queryParam("point", point)
                    .request(MediaType.TEXT_PLAIN)
                    .post(null);
            String body = response.hasEntity() ? response.readEntity(String.class) : "";
            Assertions.assertTrue(response.getStatus() >= 200 && response.getStatus() < 300,
                    "Failed to " + action + " inject " + point + " on " + coordinatorBase
                            + " status=" + response.getStatus() + " body=" + body);
        } finally {
            if (response != null)
                response.close();
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
        Response response = null;
        try {
            response = client.target(healthUri).request().get();
            return response.getStatus() == 200;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (response != null)
                response.close();
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
        coordinatorUris.parallelStream().forEach(base -> Awaitility.await("waiting for coordinator " + base + " to be ready")
                .atMost(atMostSeconds, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> isCoordinatorReachable(base)));
    }

    protected static void waitFor(String description, long timeoutMs, BooleanSupplier condition) {
        try {
            Awaitility.await(description)
                    .atMost(Duration.ofMillis(timeoutMs))
                    .pollInterval(Duration.ofMillis(200))
                    .until(condition::getAsBoolean);
        } catch (ConditionTimeoutException timeout) {
            LOG.warnf("Timed out waiting for %s: %s", description, timeout.getMessage());
        }
    }

    protected static void assertCountStays(String description, int expected, long settleMs, IntSupplier getter) {
        try {
            Awaitility.await(description)
                    .during(Duration.ofMillis(settleMs))
                    .atMost(Duration.ofMillis(settleMs + 1_000))
                    .until(() -> getter.getAsInt() == expected);
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError(description + ": expected " + expected
                    + " to hold for " + settleMs + "ms, but observed " + getter.getAsInt());
        }
    }

    protected void waitForCallCount(URI lraId, int expected, long timeoutMs) {
        waitFor("call count >= " + expected, timeoutMs, () -> getCallCount(lraId) >= expected);
    }

    /**
     * Polls every backend because the transaction lives in only one local object store and
     * a single-backend read can miss it.
     */
    protected void waitForNoActiveLra(URI lraId, long timeoutMs) {
        String targetLraUid = LRAConstants.getLRAUid(lraId);
        waitFor("LRA " + targetLraUid + " to leave the cluster active list", timeoutMs,
                () -> getAllActiveIdsAcrossCoordinators().stream()
                        .map(LRAConstants::getLRAUid)
                        .noneMatch(targetLraUid::equals));
    }

    protected void waitForForgetCallCount(URI lraId, int expected, long timeoutMs) {
        waitFor("forget call count >= " + expected, timeoutMs, () -> getForgetCallCount(lraId) >= expected);
    }

    protected void waitForStatusIntermediateCompensateCallCount(URI lraId, int expected, long timeoutMs) {
        waitFor("status-intermediate-compensate call count >= " + expected, timeoutMs,
                () -> getStatusIntermediateCompensateCallCount(lraId) >= expected);
    }

    protected void waitForStatusIntermediateCompleteCallCount(URI lraId, int expected, long timeoutMs) {
        waitFor("status-intermediate-complete call count >= " + expected, timeoutMs,
                () -> getStatusIntermediateCompleteCallCount(lraId) >= expected);
    }

    protected void assertNoActiveLras() {
        List<String> activeIds = getActiveIds();
        Assertions.assertEquals(0, activeIds.size(), "Expected no active LRAs but got: " + activeIds);
    }

    protected PrepareLraBuilder prepareLra() {
        return new PrepareLraBuilder(this);
    }

    protected URI prepareLra(String clientIdPrefix, String compensatePath, String completePath) {
        return prepareLra()
                .clientId(clientIdPrefix)
                .compensate(compensatePath)
                .complete(completePath)
                .start();
    }

    protected URI prepareLra(String clientIdPrefix, String compensatePath, String completePath, String statusPath) {
        return prepareLra()
                .clientId(clientIdPrefix)
                .compensate(compensatePath)
                .complete(completePath)
                .status(statusPath)
                .start();
    }

    protected URI prepareLra(
            String clientIdPrefix,
            String compensatePath,
            String completePath,
            String forgetPath,
            String statusPath) {
        return prepareLra()
                .clientId(clientIdPrefix)
                .compensate(compensatePath)
                .complete(completePath)
                .forget(forgetPath)
                .status(statusPath)
                .start();
    }

    protected URI prepareLra(
            URI parentLRA,
            String clientIdPrefix,
            String compensatePath,
            String completePath,
            String forgetPath,
            String statusPath) {
        return prepareLra()
                .parent(parentLRA)
                .clientId(clientIdPrefix)
                .compensate(compensatePath)
                .complete(completePath)
                .forget(forgetPath)
                .status(statusPath)
                .start();
    }

    protected URI prepareCompensateLra(String scenario, String compensatePath) {
        return prepareLra(participantClientId(scenario), compensatePath, COMPLETE);
    }

    protected URI prepareCompensateLraWithStatus(String scenario, String compensatePath, String statusPath) {
        return prepareLra(participantClientId(scenario), compensatePath, COMPLETE, statusPath);
    }

    protected URI prepareCompensateLraWithStatusAndForget(String scenario, String statusPath) {
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

    protected URI prepareCompleteLraWithStatus(String scenario, String completePath, String statusPath) {
        return prepareLra(participantClientId(scenario), COMPENSATE, completePath, statusPath);
    }

    protected URI prepareCompleteLraWithStatusAndForget(String scenario, String statusPath) {
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

    protected int getCallCount(URI lraId) {
        return client.target(participantUri(CALL_COUNT))
                .queryParam("lraId", lraId.toASCIIString())
                .request().get(Integer.class);
    }

    protected int getFailCallCount(URI lraId) {
        return client.target(participantUri(FAIL_CALL_COUNT))
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

    protected void waitForAfterCallCount(URI lraId, int expected, long timeoutMs) {
        waitFor("@AfterLRA call count >= " + expected, timeoutMs, () -> getAfterCallCount(lraId) >= expected);
    }

    protected URI prepareLraWithAfter(
            String clientIdPrefix,
            String compensatePath,
            String completePath) {
        return prepareLra()
                .clientId(clientIdPrefix)
                .compensate(compensatePath)
                .complete(completePath)
                .after(AFTER_LRA)
                .start();
    }

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

    protected URI prepareNestedLraWithAfter(
            URI parent,
            String scenario,
            String compensatePath,
            String completePath) {
        return prepareLra()
                .parent(parent)
                .clientId(participantClientId(scenario) + "-nested")
                .compensate(compensatePath)
                .complete(completePath)
                .after(AFTER_LRA)
                .start();
    }
}
