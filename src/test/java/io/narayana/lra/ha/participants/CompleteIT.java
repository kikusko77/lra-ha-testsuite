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
 * Tests for the @Complete lifecycle in a multi-coordinator HA setup.
 *
 * Mirrors {@link CompensateIT} but drives LRAs through {@code closeLRA} rather
 * than {@code cancelLRA}. Also includes mutual-exclusion tests that verify
 *
 * @Complete is never called on cancel and @Compensate is never called on close.
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

    /** Basic close: synchronous complete returns 200 and the LRA disappears from all coordinators. */
    @Test
    void testCompleteHappyPath() {
        log.info("CompleteIT: testCompleteHappyPath");
        URI lra = prepareCompleteLra("complete-happy", COMPLETE);

        assertDoesNotThrow(() -> lraClient.closeLRA(lra));

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);
        assertNoActiveLras();
    }

    /** Normal close with the idempotent endpoint; verifies exactly one call and one side effect. */
    @Test
    void testIdempotentComplete_happyPath() {
        log.info("CompleteIT: testIdempotentComplete_happyPath");
        URI lra = prepareCompleteLra("complete-idempotent-happy", COMPLETE_IDEMPOTENT);

        assertDoesNotThrow(() -> lraClient.closeLRA(lra));

        // The LRA transitions to Closing before @Complete is called, so waitForNoActiveLra
        // can return before the callback is delivered. Poll directly on the call count.
        waitForIdempotentCallCount(lra, 1, LRA_GONE_FAST_MS);

        assertEquals(1, getIdempotentCallCount(lra),
                "Idempotent complete should be called exactly once in the happy path");
        assertEquals(1, getIdempotentWorkDone(lra),
                "Side effect must be performed exactly once");
    }

    /**
     * Coordinator crashes at END_DURING_CLEANUP, after @Complete was already called and FINISH_OK persisted.
     * Recovery usually does not re-call @Complete, but the idempotent guard protects against it if it does.
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

    /**
     * Coordinator crashes at END_AFTER_SAVE: Closing is persisted but participants were not called yet.
     * Recovery must call @Complete and the side effect must happen exactly once.
     */
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
     * Coordinator crashes at END_BEFORE_SAVE: the close decision is never persisted on coordinator-1.
     * The HA proxy detects the crash and fails over to coordinator-2, which finds the LRA still Active
     * and processes the close — calling @Complete exactly once.
     * The idempotent guard ensures the side effect is performed exactly once.
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
     * Coordinator crashes at END_AFTER_PARTICIPANT_RESPONSE after a synchronous 200 from @Complete,
     * before FINISH_OK is persisted. The LRA stays in Closing, so recovery may call @Complete again.
     * The idempotent guard ensures the side effect runs at most once regardless.
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

    /** Async complete: coordinator receives 202 and polls @Status until it gets Completed. */
    @Test
    void testAsyncComplete_withStatus_happyPath() {
        log.info("CompleteIT: testAsyncComplete_withStatus_happyPath");
        URI lra = prepareCompleteLraAsync(
                "complete-async-happy",
                COMPLETE_ASYNC,
                STATUS_FOR_ASYNC_COMPLETE);

        assertDoesNotThrow(() -> lraClient.closeLRA(lra));

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);
        assertNoActiveLras();
    }

    /**
     * Coordinator crashes at END_AFTER_SAVE on the async path.
     * Recovery calls @Complete, gets 202, polls @Status, and finalises the LRA.
     */
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
     * END_DURING_CLEANUP with async complete causes @Complete to be called twice via proxy failover.
     * The first coordinator crashes before sending a response, so the proxy reroutes to a second
     * coordinator which finds the LRA still in Closing state and calls @Complete again.
     *
     * @Status correctly reports Completed on the second call, preventing an infinite loop.
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
     * Coordinator crashes at END_AFTER_PARTICIPANT_RESPONSE: the participant's 202 was received but
     * the Completing state was never persisted, so on recovery accepted=false.
     * The pre-flight @Status check should detect that completion already happened
     * and resolve the LRA without replaying @Complete.
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
     * Participant returns 503 on the first call and 200 on retry.
     * The coordinator does not retry inline; it sets accepted=true and waits for Arjuna's recovery scan
     * (~120 s) to retry.
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

    /**
     * @Complete always returns 409, so the coordinator moves the LRA to FailedToComplete.
     *           The test checks that the LRA leaves the active list.
     */
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

    /**
     * When an LRA is cancelled, only @Compensate must be invoked — never @Complete.
     * Uses the idempotent complete endpoint to track whether @Complete was called.
     */
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

    /**
     * When an LRA is closed, only @Complete must be invoked — never @Compensate.
     * Uses the idempotent compensate endpoint to track whether @Compensate was called.
     */
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
