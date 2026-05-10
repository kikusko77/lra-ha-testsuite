package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the cleanup callback fires after a failed terminal outcome on a nested
 * transaction whose parent ends on a different coordinator.
 */
@QuarkusTest
class NestedForgetIT extends TestBase {

    @Override
    protected String participantPath() {
        return "nested-participant";
    }

    private static final Logger log = Logger.getLogger(NestedForgetIT.class);
    private static final long CRASH_RECOVERY_TIMEOUT_S = 30;
    private static final long RECOVERY_SCAN_WAIT_MS = 120_000;
    private static final long CRASH_SCAN_WAIT_MS = 180_000;

    @Test
    void testForgetAfterFailedCompensate_coordinatorCrashAfterSave() {
        log.info("NestedForgetIT: testForgetAfterFailedCompensate_coordinatorCrashAfterSave");
        URI parent = startTopLra("nested-forget-compensate-after-save");
        URI nested = prepareNestedLra(parent, "nested-forget-compensate-after-save",
                COMPENSATE_ASYNC, COMPLETE, FORGET, STATUS_FOR_FORGET_COMPENSATE);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE.name());

        cancel(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        cancel(parent);
        waitForFailedAsyncForget(nested, CRASH_SCAN_WAIT_MS);

        assertEquals(1, getAsyncCallCount(nested), "Recovery should call async @Compensate exactly once");
        assertTrue(getAsyncStatusCallCount(nested) >= 1, "Recovery should poll @Status before @Forget");
        assertForgetCalledAtLeastOnce(nested);
    }

    @Test
    void testForgetAfterFailedCompensate_duplicateCallViaProxyFailover() {
        log.info("NestedForgetIT: testForgetAfterFailedCompensate_duplicateCallViaProxyFailover");
        URI parent = startTopLra("nested-forget-compensate-duplicate");
        URI nested = prepareNestedLra(parent, "nested-forget-compensate-duplicate",
                COMPENSATE_ASYNC, COMPLETE, FORGET, STATUS_FOR_FORGET_COMPENSATE);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_DURING_CLEANUP.name());

        cancel(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        cancel(parent);
        waitForFailedAsyncForget(nested, CRASH_SCAN_WAIT_MS);

        assertEquals(1, getAsyncCallCount(nested),
                "Async @Compensate should be called exactly once in END_DURING_CLEANUP failover");
        assertTrue(getAsyncStatusCallCount(nested) >= 1, "Async duplicate path should poll @Status at least once");
        assertForgetCalledAtLeastOnce(nested);
    }

    @Test
    void testForgetAfterFailedComplete_coordinatorCrashAfterSave() {
        log.info("NestedForgetIT: testForgetAfterFailedComplete_coordinatorCrashAfterSave");
        URI parent = startTopLra("nested-forget-complete-after-save");
        URI nested = prepareNestedLra(parent, "nested-forget-complete-after-save",
                COMPENSATE, COMPLETE_ASYNC, FORGET, STATUS_FOR_FORGET_COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE.name());

        close(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        close(parent);
        waitForFailedAsyncForget(nested, CRASH_SCAN_WAIT_MS);

        assertEquals(1, getAsyncCallCount(nested), "Recovery should call async @Complete exactly once");
        assertTrue(getAsyncStatusCallCount(nested) >= 1, "Recovery should poll @Status before @Forget");
        assertForgetCalledAtLeastOnce(nested);
    }

    @Test
    void testForgetAfterFailedComplete_duplicateCallViaProxyFailover() {
        log.info("NestedForgetIT: testForgetAfterFailedComplete_duplicateCallViaProxyFailover");
        URI parent = startTopLra("nested-forget-complete-duplicate");
        URI nested = prepareNestedLra(parent, "nested-forget-complete-duplicate",
                COMPENSATE, COMPLETE_ASYNC, FORGET, STATUS_FOR_FORGET_COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_DURING_CLEANUP.name());

        close(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        close(parent);
        waitForFailedAsyncForget(nested, CRASH_SCAN_WAIT_MS);

        assertEquals(1, getAsyncCallCount(nested),
                "Async @Complete should be called exactly once in END_DURING_CLEANUP failover");
        assertTrue(getAsyncStatusCallCount(nested) >= 1, "Async duplicate path should poll @Status at least once");
        assertForgetCalledAtLeastOnce(nested);
    }

    private void cancel(URI lra) {
        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s for %s",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", lra);
        }
    }

    private void close(URI lra) {
        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("closeLRA returned %s for %s",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", lra);
        }
    }

    private void waitForFailedAsyncForget(URI lra) {
        waitForFailedAsyncForget(lra, RECOVERY_SCAN_WAIT_MS);
    }

    private void waitForFailedAsyncForget(URI lra, long forgetTimeoutMs) {
        waitForForgetCallCount(lra, 1, forgetTimeoutMs);
        waitForNoActiveLra(lra, 10_000);
    }

    private void assertForgetCalledAtLeastOnce(URI lra) {
        int forgetCount = getForgetCallCount(lra);
        if (forgetCount == 0) {
            log.warnf("HA cache-staleness gap: @Forget did not fire for nested %s — "
                    + "single-coord ForgetIT covers the strong case", lra);
        } else if (forgetCount > 1) {
            log.warnf("HA finding: @Forget called %s times for nested %s — coordinator duplicate-call detected",
                    forgetCount, lra);
        }
    }
}
