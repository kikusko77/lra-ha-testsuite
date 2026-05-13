package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the cleanup callback fires after a failed terminal outcome, including
 * crash and proxy-failover variants of the failed-cancel and failed-close paths.
 */
@QuarkusTest
class ForgetIT extends TestBase {

    @Override
    protected String participantPath() {
        return "forget-participant";
    }

    private static final Logger log = Logger.getLogger(ForgetIT.class);
    private static final long CRASH_RECOVERY_TIMEOUT_S = 30;
    private static final long RECOVERY_SCAN_WAIT_MS = 120_000;
    private static final long CRASH_SCAN_WAIT_MS = 180_000;

    /**
     * Crash hits after the cancel decision is persisted; recovery must drive the participant
     * to the failed terminal status and then deliver the cleanup callback.
     */
    @Test
    void testForgetAfterFailedCompensate_coordinatorCrashAfterSave() {
        log.info("ForgetIT: testForgetAfterFailedCompensate_coordinatorCrashAfterSave");
        URI lra = prepareCompensateLraWithStatusAndForget(
                "forget-compensate-after-save",
                STATUS_FOR_FORGET_COMPENSATE);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s, coordinator crashed as expected",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForFailedAsyncForget(lra, CRASH_SCAN_WAIT_MS);

        assertEquals(1, getAsyncCallCount(lra), "Recovery should call async @Compensate exactly once");
        assertTrue(getAsyncStatusCallCount(lra) >= 1, "Recovery should poll @Status before @Forget");
        assertForgetCalledAtLeastOnce(lra);
    }

    /**
     * Proxy failover routes the cancel to a second coordinator; cleanup must still fire even
     * though the participant call itself is not replayed.
     */
    @Test
    void testForgetAfterFailedCompensate_duplicateCallViaProxyFailover() {
        log.info("ForgetIT: testForgetAfterFailedCompensate_duplicateCallViaProxyFailover");
        URI lra = prepareCompensateLraWithStatusAndForget(
                "forget-compensate-duplicate",
                STATUS_FOR_FORGET_COMPENSATE);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_DURING_CLEANUP);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s — coordinator crashed after 202, proxy will fail over",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForFailedAsyncForget(lra, CRASH_SCAN_WAIT_MS);

        assertEquals(1, getAsyncCallCount(lra),
                "Async @Compensate should be called exactly once in END_DURING_CLEANUP failover");
        assertTrue(getAsyncStatusCallCount(lra) >= 1, "Async duplicate path should poll @Status at least once");
        assertForgetCalledAtLeastOnce(lra);
    }

    /**
     * Crash hits after the close decision is persisted; recovery must drive the participant
     * to the failed terminal status and then deliver the cleanup callback.
     */
    @Test
    void testForgetAfterFailedComplete_coordinatorCrashAfterSave() {
        log.info("ForgetIT: testForgetAfterFailedComplete_coordinatorCrashAfterSave");
        URI lra = prepareCompleteLraWithStatusAndForget(
                "forget-complete-after-save",
                STATUS_FOR_FORGET_COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE);

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("closeLRA returned %s, coordinator crashed as expected",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForFailedAsyncForget(lra, CRASH_SCAN_WAIT_MS);

        assertEquals(1, getAsyncCallCount(lra), "Recovery should call async @Complete exactly once");
        assertTrue(getAsyncStatusCallCount(lra) >= 1, "Recovery should poll @Status before @Forget");
        assertForgetCalledAtLeastOnce(lra);
    }

    /**
     * Proxy failover routes the close to a second coordinator; cleanup must still fire even
     * though the participant call itself is not replayed.
     */
    @Test
    void testForgetAfterFailedComplete_duplicateCallViaProxyFailover() {
        log.info("ForgetIT: testForgetAfterFailedComplete_duplicateCallViaProxyFailover");
        URI lra = prepareCompleteLraWithStatusAndForget(
                "forget-complete-duplicate",
                STATUS_FOR_FORGET_COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_DURING_CLEANUP);

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("closeLRA returned %s — coordinator crashed after 202, proxy will fail over",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForFailedAsyncForget(lra, CRASH_SCAN_WAIT_MS);

        assertEquals(1, getAsyncCallCount(lra),
                "Async @Complete should be called exactly once in END_DURING_CLEANUP failover");
        assertTrue(getAsyncStatusCallCount(lra) >= 1, "Async duplicate path should poll @Status at least once");
        assertForgetCalledAtLeastOnce(lra);
    }

    private void waitForFailedAsyncForget(URI lra) {
        waitForFailedAsyncForget(lra, RECOVERY_SCAN_WAIT_MS);
    }

    private void waitForFailedAsyncForget(URI lra, long forgetTimeoutMs) {
        waitForForgetCallCount(lra, 1, forgetTimeoutMs);
        waitForNoActiveLra(lra, LRA_GONE_HAPPY_PATH_MS);
        assertNoActiveLras();
    }

    private void assertForgetCalledAtLeastOnce(URI lra) {
        int forgetCount = getForgetCallCount(lra);
        assertTrue(forgetCount >= 1, "@Forget must be called at least once");
        if (forgetCount > 1) {
            log.warnf("HA finding: @Forget called %s times for %s — coordinator duplicate-call detected; "
                    + "multiple recovery scanners processed the same LRA record concurrently",
                    forgetCount, lra);
        }
    }
}
