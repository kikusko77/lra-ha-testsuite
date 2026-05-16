package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.LRAConstants;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.util.List;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Mirrors the close-callback scenarios for a child transaction whose parent runs on a
 * different coordinator, exercising the cross-coordinator cascade and recovery paths.
 */
@QuarkusTest
class NestedCompleteIT extends TestBase {

    @Override
    protected String participantPath() {
        return "nested-participant";
    }

    private static final Logger log = Logger.getLogger(NestedCompleteIT.class);

    private static final long CRASH_RECOVERY_TIMEOUT_S = 15;
    private static final long RECOVERY_SCAN_WAIT_MS = 20_000;

    @Test
    void testIdempotentComplete_coordinatorCrashDuringCleanup() {
        log.info("NestedCompleteIT: testIdempotentComplete_coordinatorCrashDuringCleanup");
        URI parent = startTopLra("nested-complete-during-cleanup");
        URI nested = prepareNestedLra(parent, "nested-complete-during-cleanup",
                COMPENSATE, COMPLETE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_DURING_CLEANUP);

        close(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);

        int callCount = getCallCount(nested);
        int workDone = getIdempotentWorkDone(nested);
        log.infof("After crash recovery: callCount=%s, workDone=%s", callCount, workDone);

        assertTrue(callCount >= 1, "Complete must have been called at least once, got " + callCount);
        assertEquals(1, workDone, "Side effect must be performed exactly once regardless of retry count");
    }

    @Test
    void testIdempotentComplete_coordinatorCrashAfterSave() {
        log.info("NestedCompleteIT: testIdempotentComplete_coordinatorCrashAfterSave");
        URI parent = startTopLra("nested-complete-after-save");
        URI nested = prepareNestedLra(parent, "nested-complete-after-save",
                COMPENSATE, COMPLETE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE);

        close(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(1, getIdempotentWorkDone(nested),
                "Side effect must be performed exactly once after crash-and-recovery");
    }

    @Test
    void testIdempotentComplete_coordinatorCrashBeforeSave() {
        log.info("NestedCompleteIT: testIdempotentComplete_coordinatorCrashBeforeSave");
        URI parent = startTopLra("nested-complete-before-save");
        URI nested = prepareNestedLra(parent, "nested-complete-before-save",
                COMPENSATE, COMPLETE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_BEFORE_SAVE);

        close(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);
        waitForCallCount(nested, 1, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(1, getIdempotentWorkDone(nested),
                "Side effect must be performed exactly once after proxy failover");
    }

    @Test
    void testIdempotentComplete_crashAfterReceivingResponse() {
        log.info("NestedCompleteIT: testIdempotentComplete_crashAfterReceivingResponse");
        URI parent = startTopLra("nested-complete-crash-after-response");
        URI nested = prepareNestedLra(parent, "nested-complete-crash-after-response",
                COMPENSATE, COMPLETE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_PARTICIPANT_RESPONSE);

        close(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);

        int callCount = getCallCount(nested);
        int workDone = getIdempotentWorkDone(nested);
        log.infof("After crash recovery: callCount=%s, workDone=%s", callCount, workDone);

        assertTrue(callCount >= 1, "Complete must be called at least once, got " + callCount);
        assertEquals(1, workDone, "Side effect must be performed exactly once regardless of any recovery replay");
    }

    @Test
    void testAsyncComplete_withStatus_coordinatorCrashAfterSave() {
        log.info("NestedCompleteIT: testAsyncComplete_withStatus_coordinatorCrashAfterSave");
        URI parent = startTopLra("nested-complete-async-after-save");
        URI nested = prepareNestedLra(parent, "nested-complete-async-after-save",
                COMPENSATE, COMPLETE_ASYNC, STATUS_FOR_ASYNC_COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE);

        close(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);
    }

    @Test
    void testAsyncComplete_duplicateCallViaProxyFailover() {
        log.info("NestedCompleteIT: testAsyncComplete_duplicateCallViaProxyFailover");
        URI parent = startTopLra("nested-complete-async-duplicate");
        URI nested = prepareNestedLra(parent, "nested-complete-async-duplicate",
                COMPENSATE, COMPLETE_ASYNC, STATUS_FOR_ASYNC_COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_DURING_CLEANUP);

        close(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);

        int completeCalls = getAsyncCallCount(nested);
        int statusCalls = getAsyncStatusCallCount(nested);
        log.infof("After async proxy failover: completeCalls=%s, statusCalls=%s", completeCalls, statusCalls);

        assertEquals(1, completeCalls,
                "Async @Complete should be called exactly once in END_DURING_CLEANUP failover");
        assertTrue(statusCalls >= 1,
                "Async duplicate path should poll @Status at least once, got " + statusCalls);
    }

    @Test
    void testAsyncComplete_withStatus_crashAfterReceivingResponse() {
        log.info("NestedCompleteIT: testAsyncComplete_withStatus_crashAfterReceivingResponse");
        waitForAllCoordinators(CRASH_RECOVERY_TIMEOUT_S);
        resetProxyRouting();

        URI parent = startTopLra("nested-complete-async-after-response");
        URI nested = prepareNestedLra(parent, "nested-complete-async-after-response",
                COMPENSATE, COMPLETE_ASYNC, STATUS_FOR_ASYNC_COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_PARTICIPANT_RESPONSE);

        close(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);

        int completeCalls = getAsyncCallCount(nested);
        int statusCalls = getAsyncStatusCallCount(nested);
        log.infof("After async crash recovery: completeCalls=%s, statusCalls=%s", completeCalls, statusCalls);

        assertEquals(1, completeCalls,
                "Async @Complete should not be replayed after END_AFTER_PARTICIPANT_RESPONSE; got "
                        + completeCalls + " calls");
        assertTrue(statusCalls >= 1,
                "Recovery should resolve via pre-flight @Status, got " + statusCalls + " polls");
    }

    @Test
    void testParticipantTransientFailure_coordinatorRetries() {
        log.info("NestedCompleteIT: testParticipantTransientFailure_coordinatorRetries");
        URI parent = startTopLra("nested-complete-unreachable");
        URI nested = prepareNestedLra(parent, "nested-complete-unreachable",
                COMPENSATE, COMPLETE_UNREACHABLE);

        close(nested);
        waitForNoActiveLra(nested, RECOVERY_SCAN_WAIT_MS);
    }

    @Test
    void testFailedToComplete_lraMovesToFailedToClose() {
        log.info("NestedCompleteIT: testFailedToComplete_lraMovesToFailedToClose");
        URI parent = startTopLra("nested-complete-fail");
        URI nested = prepareNestedLra(parent, "nested-complete-fail",
                COMPENSATE, COMPLETE_FAIL);

        close(nested);

        waitForNoActiveLra(nested, LRA_GONE_HAPPY_PATH_MS);

        List<String> activeIds = getActiveLras();
        String nestedUid = LRAConstants.getLRAUid(nested);
        boolean stillActive = activeIds.stream()
                .map(LRAConstants::getLRAUid)
                .anyMatch(nestedUid::equals);

        assertTrue(!stillActive,
                "Nested LRA should not be in the active list after FailedToComplete; found in " + activeIds);
    }
}
