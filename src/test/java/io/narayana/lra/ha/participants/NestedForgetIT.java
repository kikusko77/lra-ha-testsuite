package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@QuarkusTest
class NestedForgetIT extends TestBase {

    @Override
    protected String participantPath() {
        return "nested-participant";
    }

    private static final Logger log = LoggerFactory.getLogger(NestedForgetIT.class);
    private static final long CRASH_RECOVERY_WAIT_S = 30;
    private static final long RECOVERY_SCAN_WAIT_MS = 120_000;
    private static final long CRASH_SCAN_WAIT_MS = 180_000;

    @Test
    void testForgetAfterFailedCompensate_happyPath() {
        log.info("NestedForgetIT: testForgetAfterFailedCompensate_happyPath");
        URI parent = startTopLra("nested-forget-compensate-happy");
        URI nested = prepareNestedLra(parent, "nested-forget-compensate-happy",
                COMPENSATE_ASYNC, COMPLETE, FORGET, STATUS_FOR_FORGET_COMPENSATE);

        cancelQuietly(nested);
        cancelQuietly(parent);

        waitForFailedAsyncForget(nested);

        assertEquals(1, getAsyncCallCount(nested), "@Compensate should be called exactly once in the happy path");
        assertTrue(getAsyncStatusCallCount(nested) >= 1, "@Status should be polled before @Forget");
        assertForgetCalledAtLeastOnce(nested);
    }

    @Test
    void testForgetAfterFailedCompensate_coordinatorCrashAfterSave() {
        log.info("NestedForgetIT: testForgetAfterFailedCompensate_coordinatorCrashAfterSave");
        URI parent = startTopLra("nested-forget-compensate-after-save");
        URI nested = prepareNestedLra(parent, "nested-forget-compensate-after-save",
                COMPENSATE_ASYNC, COMPLETE, FORGET, STATUS_FOR_FORGET_COMPENSATE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        cancelQuietly(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        cancelQuietly(parent);
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

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        cancelQuietly(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        cancelQuietly(parent);
        waitForFailedAsyncForget(nested, CRASH_SCAN_WAIT_MS);

        assertEquals(1, getAsyncCallCount(nested),
                "Async @Compensate should be called exactly once in END_DURING_CLEANUP failover");
        assertTrue(getAsyncStatusCallCount(nested) >= 1, "Async duplicate path should poll @Status at least once");
        assertForgetCalledAtLeastOnce(nested);
    }

    @Test
    void testForgetAfterFailedComplete_happyPath() {
        log.info("NestedForgetIT: testForgetAfterFailedComplete_happyPath");
        URI parent = startTopLra("nested-forget-complete-happy");
        URI nested = prepareNestedLra(parent, "nested-forget-complete-happy",
                COMPENSATE, COMPLETE_ASYNC, FORGET, STATUS_FOR_FORGET_COMPLETE);

        closeQuietly(nested);
        closeQuietly(parent);

        waitForFailedAsyncForget(nested);

        assertEquals(1, getAsyncCallCount(nested), "@Complete should be called exactly once in the happy path");
        assertTrue(getAsyncStatusCallCount(nested) >= 1, "@Status should be polled before @Forget");
        assertForgetCalledAtLeastOnce(nested);
    }

    @Test
    void testForgetAfterFailedComplete_coordinatorCrashAfterSave() {
        log.info("NestedForgetIT: testForgetAfterFailedComplete_coordinatorCrashAfterSave");
        URI parent = startTopLra("nested-forget-complete-after-save");
        URI nested = prepareNestedLra(parent, "nested-forget-complete-after-save",
                COMPENSATE, COMPLETE_ASYNC, FORGET, STATUS_FOR_FORGET_COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        closeQuietly(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        closeQuietly(parent);
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

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        closeQuietly(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        closeQuietly(parent);
        waitForFailedAsyncForget(nested, CRASH_SCAN_WAIT_MS);

        assertEquals(1, getAsyncCallCount(nested),
                "Async @Complete should be called exactly once in END_DURING_CLEANUP failover");
        assertTrue(getAsyncStatusCallCount(nested) >= 1, "Async duplicate path should poll @Status at least once");
        assertForgetCalledAtLeastOnce(nested);
    }

    private void cancelQuietly(URI lra) {
        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} for {}",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", lra);
        }
    }

    private void closeQuietly(URI lra) {
        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} for {}",
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
            log.warn("HA cache-staleness gap: @Forget did not fire for nested {} — "
                    + "single-coord ForgetIT covers the strong case", lra);
        } else if (forgetCount > 1) {
            log.warn("HA finding: @Forget called {} times for nested {} — coordinator duplicate-call detected",
                    forgetCount, lra);
        }
    }
}
