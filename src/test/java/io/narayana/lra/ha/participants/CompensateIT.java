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
 * Exercises the cancellation callback across synchronous, asynchronous and crash-recovery paths
 * in the multi-coordinator HA setup.
 */
@QuarkusTest
class CompensateIT extends TestBase {

    @Override
    protected String participantPath() {
        return "compensate-participant";
    }

    private static final Logger log = Logger.getLogger(CompensateIT.class);

    private static final long CRASH_RECOVERY_TIMEOUT_S = 15;
    private static final long RECOVERY_SCAN_WAIT_MS = 20_000;

    private void assertSyncCompensateOutcome(URI lra, int minCallCount, int expectedWorkDone) {
        int callCount = getCallCount(lra);
        int workDone = getIdempotentWorkDone(lra);
        log.infof("After scenario: callCount=%s, workDone=%s", callCount, workDone);
        assertTrue(callCount >= minCallCount,
                "Compensate must have been called at least " + minCallCount + " times, got " + callCount);
        assertEquals(expectedWorkDone, workDone,
                "Side effect count must equal " + expectedWorkDone + ", got " + workDone);
    }

    private void assertAsyncCompensateOutcome(URI lra, int expectedCalls, int minStatusCalls) {
        int compensateCalls = getAsyncCallCount(lra);
        int statusCalls = getAsyncStatusCallCount(lra);
        log.infof("After async scenario: compensateCalls=%s, statusCalls=%s", compensateCalls, statusCalls);
        assertEquals(expectedCalls, compensateCalls,
                "Async @Compensate must be called exactly " + expectedCalls + " times, got " + compensateCalls);
        assertTrue(statusCalls >= minStatusCalls,
                "@Status must be polled at least " + minStatusCalls + " times, got " + statusCalls);
    }

    /**
     * Verifies participant-side idempotency under crash replay: the participant's
     * guard must suppress the duplicate side effect when
     * crash-and-recovery causes {@code @Compensate} to be replayed. Crash hits
     * after the original callback already ran and the outcome was persisted.
     */
    @Test
    void testIdempotentCompensate_coordinatorCrashDuringCleanup() {
        log.info("CompensateIT: testIdempotentCompensate_coordinatorCrashDuringCleanup");
        URI lra = prepareCompensateLra("idempotent-during-cleanup", COMPENSATE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_DURING_CLEANUP);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s (coordinator crashed), proceeding to recovery check",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);

        // callCount may be 2 if the crash happened before Arjuna could persist
        // FINISH_OK (timing-dependent); workDone must always be 1.
        assertSyncCompensateOutcome(lra, /* minCallCount */ 1, /* workDone */ 1);
    }

    @Test
    void testIdempotentCompensate_coordinatorCrashAfterSave() {
        log.info("CompensateIT: testIdempotentCompensate_coordinatorCrashAfterSave");
        URI lra = prepareCompensateLra("idempotent-after-save", COMPENSATE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("cancelLRA returned 404, treating as already processed");
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s, coordinator crashed as expected",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);

        assertSyncCompensateOutcome(lra, /* minCallCount */ 1, /* workDone */ 1);
    }

    /**
     * Crash hits before the cancel decision is persisted, so the transaction stays active
     * and is cleaned up by the timeout path once the coordinator returns.
     */
    @Test
    void testIdempotentCompensate_coordinatorCrashBeforeSave() {
        log.info("CompensateIT: testIdempotentCompensate_coordinatorCrashBeforeSave");
        URI lra = prepareCompensateLra("idempotent-before-save", COMPENSATE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_BEFORE_SAVE);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s (coordinator crashed), LRA will be cancelled by timeout on recovery",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);

        assertSyncCompensateOutcome(lra, /* minCallCount */ 1, /* workDone */ 1);
    }

    @Test
    void testAsyncCompensate_withStatus_coordinatorCrashAfterSave() {
        log.info("CompensateIT: testAsyncCompensate_withStatus_coordinatorCrashAfterSave");
        URI lra = prepareCompensateLraWithStatus(
                "async-after-save",
                COMPENSATE_ASYNC,
                STATUS_FOR_ASYNC);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s, coordinator crashed",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);
        assertNoActiveLras();

        assertAsyncCompensateOutcome(lra, /* expectedCalls */ 1, /* minStatusCalls */ 1);
    }

    /**
     * The proxy retargets the cancel after the first coordinator crashes, and the second coordinator
     * resolves it from a status poll instead of replaying the participant call.
     */
    @Test
    void testAsyncCompensate_duplicateCallViaProxyFailover() {
        log.info("CompensateIT: testAsyncCompensate_duplicateCallViaProxyFailover");
        URI lra = prepareCompensateLraWithStatus(
                "async-duplicate",
                COMPENSATE_ASYNC,
                STATUS_FOR_ASYNC);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_DURING_CLEANUP);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s — coordinator crashed after 202, proxy will failover to coordinator-2",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);
        assertNoActiveLras();

        assertAsyncCompensateOutcome(lra, /* expectedCalls */ 1, /* minStatusCalls */ 1);
    }

    /**
     * Crash hits between receiving the participant 202 and persisting the in-progress state.
     * Recovery must resolve the transaction via a status poll instead of replaying the call.
     */
    @Test
    void testAsyncCompensate_withStatus_crashAfterReceivingResponse() {
        log.info("CompensateIT: testAsyncCompensate_withStatus_crashAfterReceivingResponse");
        waitForAllCoordinators(CRASH_RECOVERY_TIMEOUT_S);
        resetProxyRouting();
        URI lra = prepareCompensateLraWithStatus(
                "async-after-response",
                COMPENSATE_ASYNC,
                STATUS_FOR_ASYNC);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_PARTICIPANT_RESPONSE);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s — coordinator crashed after receiving participant 202",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);
        assertNoActiveLras();

        assertAsyncCompensateOutcome(lra, /* expectedCalls */ 1, /* minStatusCalls */ 1);
    }

    /**
     * Crash hits after the synchronous 200 but before the success is persisted, so recovery
     * may replay the call; the participant's idempotency guard must keep the side effect at one.
     */
    @Test
    void testIdempotentCompensate_crashAfterReceivingResponse() {
        log.info("CompensateIT: testIdempotentCompensate_crashAfterReceivingResponse");
        URI lra = prepareCompensateLra("crash-after-response", COMPENSATE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_PARTICIPANT_RESPONSE);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof(
                    "cancelLRA returned %s — coordinator crashed after receiving participant 200 but before persisting FINISH_OK",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);

        assertSyncCompensateOutcome(lra, /* minCallCount */ 1, /* workDone */ 1);
    }

    /**
     * The participant fails the first call with a transient error and the coordinator
     * must wait for the recovery scan to retry, since it does not retry inline.
     */
    @Test
    void testParticipantTransientFailure_coordinatorRetries() {
        log.info("CompensateIT: testParticipantTransientFailure_coordinatorRetries");
        URI lra = prepareCompensateLra("unreachable", COMPENSATE_UNREACHABLE);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s (503 from participant — coordinator queues for recovery scan)",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }
        waitForNoActiveLra(lra, RECOVERY_SCAN_WAIT_MS);
        assertNoActiveLras();
    }

    @Test
    void testFailedToCompensate_lraMovesToFailedToCancel() {
        log.info("CompensateIT: testFailedToCompensate_lraMovesToFailedToCancel");
        URI lra = prepareCompensateLra("fail", COMPENSATE_FAIL);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s for fail scenario",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        waitForNoActiveLra(lra, LRA_GONE_HAPPY_PATH_MS);

        List<String> activeIds = getActiveIds();
        String targetUid = LRAConstants.getLRAUid(lra);
        boolean stillActive = activeIds.stream()
                .map(LRAConstants::getLRAUid)
                .anyMatch(targetUid::equals);

        assertTrue(!stillActive,
                "LRA should not be in the active list after FailedToCompensate; found in " + activeIds);

        int failCalls = getFailCallCount(lra);
        assertTrue(failCalls >= 1,
                "@Compensate (fail variant) must have been called at least once before the LRA reaches FailedToCancel, got "
                        + failCalls);
    }

}
