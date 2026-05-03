package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.LRAConstants;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exercises the close callback across synchronous, asynchronous and crash-recovery paths
 * and verifies the close and cancel branches stay mutually exclusive.
 */
@QuarkusTest
class CompleteIT extends TestBase {

    @Override
    protected String participantPath() {
        return "complete-participant";
    }

    private static final Logger log = LoggerFactory.getLogger(CompleteIT.class);

    private static final long CRASH_RECOVERY_WAIT_S = 15;
    private static final long LRA_GONE_WAIT_MS = 30_000;
    private static final long LRA_GONE_FAST_MS = 10_000;
    private static final long RECOVERY_SCAN_WAIT_MS = 20_000;

    /**
     * Crash hits after the participant callback already ran and the outcome was persisted.
     * The retry triggered by recovery must not produce a second side effect.
     */
    @Test
    void testIdempotentComplete_coordinatorCrashDuringCleanup() {
        log.info("CompleteIT: testIdempotentComplete_coordinatorCrashDuringCleanup");
        URI lra = prepareCompleteLra("complete-idempotent-during-cleanup", COMPLETE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} (coordinator crashed), proceeding to recovery check",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);

        int callCount = getIdempotentCallCount(lra);
        int workDone = getIdempotentWorkDone(lra);

        log.info("After crash recovery: callCount={}, workDone={}", callCount, workDone);

        assertTrue(callCount >= 1, "Complete must have been called at least once, got " + callCount);
        assertEquals(1, workDone, "Side effect must be performed exactly once regardless of retry count");
    }

    @Test
    void testIdempotentComplete_coordinatorCrashAfterSave() {
        log.info("CompleteIT: testIdempotentComplete_coordinatorCrashAfterSave");
        URI lra = prepareCompleteLra("complete-idempotent-after-save", COMPLETE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("closeLRA returned 404, treating as already processed");
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {}, coordinator crashed as expected",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);

        assertEquals(1, getIdempotentWorkDone(lra),
                "Side effect must be performed exactly once after crash-and-recovery");
    }

    /**
     * Crash hits before the close decision is persisted, so the proxy fails over and the second
     * coordinator drives the close to completion exactly once.
     */
    @Test
    void testIdempotentComplete_coordinatorCrashBeforeSave() {
        log.info("CompleteIT: testIdempotentComplete_coordinatorCrashBeforeSave");
        URI lra = prepareCompleteLra("complete-idempotent-before-save", COMPLETE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_BEFORE_SAVE.name());

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} — coordinator-1 crashed, proxy fails over to coordinator-2",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);
        waitForIdempotentCallCount(lra, 1, LRA_GONE_WAIT_MS);

        assertEquals(1, getIdempotentWorkDone(lra),
                "Side effect must be performed exactly once after proxy failover delivers close to coordinator-2");
    }

    /**
     * Crash hits after the synchronous 200 but before the success is persisted, so recovery
     * may replay the call; the participant's idempotency guard must keep the side effect at one.
     */
    @Test
    void testIdempotentComplete_crashAfterReceivingResponse() {
        log.info("CompleteIT: testIdempotentComplete_crashAfterReceivingResponse");
        URI lra = prepareCompleteLra("complete-crash-after-response", COMPLETE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_PARTICIPANT_RESPONSE.name());

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info(
                    "closeLRA returned {} — coordinator crashed after receiving participant 200 but before persisting FINISH_OK",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);

        int callCount = getIdempotentCallCount(lra);
        int workDone = getIdempotentWorkDone(lra);

        log.info("After crash recovery: callCount={}, workDone={}", callCount, workDone);

        assertTrue(callCount >= 1,
                "Complete must be called at least once before the LRA resolves, got " + callCount);
        assertEquals(1, workDone,
                "Side effect must be performed exactly once regardless of any recovery replay");
    }

    @Test
    void testAsyncComplete_withStatus_coordinatorCrashAfterSave() {
        log.info("CompleteIT: testAsyncComplete_withStatus_coordinatorCrashAfterSave");
        URI lra = prepareCompleteLraAsync(
                "complete-async-after-save",
                COMPLETE_ASYNC,
                STATUS_FOR_ASYNC_COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {}, coordinator crashed",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);
        assertNoActiveLras();
    }

    /**
     * The proxy retargets the close after the first coordinator crashes, and the second coordinator
     * resolves it from a status poll instead of replaying the participant call.
     */
    @Test
    void testAsyncComplete_duplicateCallViaProxyFailover() {
        log.info("CompleteIT: testAsyncComplete_duplicateCallViaProxyFailover");
        URI lra = prepareCompleteLraAsync(
                "complete-async-duplicate",
                COMPLETE_ASYNC,
                STATUS_FOR_ASYNC_COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} — coordinator crashed after 202, proxy will failover to coordinator-2",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);
        assertNoActiveLras();

        int completeCalls = getAsyncCallCount(lra);
        int statusCalls = getAsyncStatusCallCount(lra);

        log.info("After async proxy failover: completeCalls={}, statusCalls={}", completeCalls, statusCalls);

        assertEquals(1, completeCalls,
                "Async @Complete should be called exactly once in END_DURING_CLEANUP failover");
        assertTrue(statusCalls >= 1,
                "Async duplicate path should poll @Status at least once, got " + statusCalls + " polls");
    }

    /**
     * Crash hits between receiving the participant 202 and persisting the in-progress state.
     * Recovery must resolve the transaction via a status poll instead of replaying the call.
     */
    @Test
    void testAsyncComplete_withStatus_crashAfterReceivingResponse() {
        log.info("CompleteIT: testAsyncComplete_withStatus_crashAfterReceivingResponse");
        waitForAllCoordinators(CRASH_RECOVERY_WAIT_S);
        resetProxyRouting();
        URI lra = prepareCompleteLraAsync(
                "complete-async-after-response",
                COMPLETE_ASYNC,
                STATUS_FOR_ASYNC_COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_PARTICIPANT_RESPONSE.name());

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} — coordinator crashed after receiving participant 202",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);
        assertNoActiveLras();

        int completeCalls = getAsyncCallCount(lra);
        int statusCalls = getAsyncStatusCallCount(lra);

        log.info("After async crash recovery: completeCalls={}, statusCalls={}", completeCalls, statusCalls);

        assertEquals(1, completeCalls,
                "Async @Complete should not be replayed after END_AFTER_PARTICIPANT_RESPONSE; got "
                        + completeCalls + " calls");
        assertTrue(statusCalls >= 1,
                "Recovery should resolve this path via pre-flight @Status after the crash, got "
                        + statusCalls + " status polls");
    }

    /**
     * The participant fails the first call with a transient error and the coordinator
     * must wait for the recovery scan to retry, since it does not retry inline.
     */
    @Test
    void testParticipantTransientFailure_coordinatorRetries() {
        log.info("CompleteIT: testParticipantTransientFailure_coordinatorRetries");
        URI lra = prepareCompleteLra("complete-unreachable", COMPLETE_UNREACHABLE);

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} (503 from participant — coordinator queues for recovery scan)",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }
        waitForNoActiveLra(lra, RECOVERY_SCAN_WAIT_MS);
        assertNoActiveLras();
    }

    @Test
    void testFailedToComplete_lraMovesToFailedToClose() {
        log.info("CompleteIT: testFailedToComplete_lraMovesToFailedToClose");
        URI lra = prepareCompleteLra("complete-fail", COMPLETE_FAIL);

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} for fail scenario",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);

        List<String> activeIds = getActiveIds();
        String targetUid = LRAConstants.getLRAUid(lra);
        boolean stillActive = activeIds.stream()
                .map(LRAConstants::getLRAUid)
                .anyMatch(targetUid::equals);

        assertTrue(!stillActive,
                "LRA should not be in the active list after FailedToComplete; found in " + activeIds);
    }

    @Test
    void testComplete_notCalledOnCancel() {
        log.info("CompleteIT: testComplete_notCalledOnCancel");
        URI lra = prepareLra(
                "complete-not-on-cancel",
                COMPENSATE,
                COMPLETE_IDEMPOTENT);

        assertDoesNotThrow(() -> lraClient.cancelLRA(lra));
        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);

        assertEquals(0, getIdempotentCallCount(lra),
                "@Complete must not be called when the LRA is cancelled");
    }

    @Test
    void testCompensate_notCalledOnClose() {
        log.info("CompleteIT: testCompensate_notCalledOnClose");
        URI lra = prepareLra(
                "compensate-not-on-close",
                COMPENSATE_IDEMPOTENT,
                COMPLETE);

        assertDoesNotThrow(() -> lraClient.closeLRA(lra));
        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);

        assertEquals(0, getIdempotentCallCount(lra),
                "@Compensate must not be called when the LRA is closed");
    }

}
