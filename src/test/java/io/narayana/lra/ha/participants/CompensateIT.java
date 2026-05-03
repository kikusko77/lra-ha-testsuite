package io.narayana.lra.ha.participants;

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
 * Exercises the cancellation callback across synchronous, asynchronous and crash-recovery paths
 * in the multi-coordinator HA setup.
 */
@QuarkusTest
class CompensateIT extends TestBase {

    @Override
    protected String participantPath() {
        return "compensate-participant";
    }

    private static final Logger log = LoggerFactory.getLogger(CompensateIT.class);

    private static final long CRASH_RECOVERY_WAIT_S = 15;
    private static final long LRA_GONE_WAIT_MS = 30_000;
    private static final long LRA_GONE_FAST_MS = 10_000;
    private static final long RECOVERY_SCAN_WAIT_MS = 20_000;

    /**
     * Crash hits after the participant callback already ran and the outcome was persisted.
     * The retry triggered by recovery must not produce a second side effect.
     */
    @Test
    void testIdempotentCompensate_coordinatorCrashDuringCleanup() {
        log.info("CompensateIT: testIdempotentCompensate_coordinatorCrashDuringCleanup");
        URI lra = prepareCompensateLra("idempotent-during-cleanup", COMPENSATE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} (coordinator crashed), proceeding to recovery check",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);

        int callCount = getIdempotentCallCount(lra);
        int workDone = getIdempotentWorkDone(lra);

        log.info("After crash recovery: callCount={}, workDone={}", callCount, workDone);

        // callCount is >= 1: called at least once before the crash. May be 2 if the
        // crash happened before Arjuna could persist FINISH_OK (timing-dependent).
        assertTrue(callCount >= 1, "Compensate must have been called at least once, got " + callCount);
        // workDone must always be 1 — idempotency guard must fire on any retry.
        assertEquals(1, workDone, "Side effect must be performed exactly once regardless of retry count");
    }

    @Test
    void testIdempotentCompensate_coordinatorCrashAfterSave() {
        log.info("CompensateIT: testIdempotentCompensate_coordinatorCrashAfterSave");
        URI lra = prepareCompensateLra("idempotent-after-save", COMPENSATE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("cancelLRA returned 404, treating as already processed");
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {}, coordinator crashed as expected",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);

        assertEquals(1, getIdempotentWorkDone(lra),
                "Side effect must be performed exactly once after crash-and-recovery");
    }

    /**
     * Crash hits before the cancel decision is persisted, so the transaction stays active
     * and is cleaned up by the timeout path once the coordinator returns.
     */
    @Test
    void testIdempotentCompensate_coordinatorCrashBeforeSave() {
        log.info("CompensateIT: testIdempotentCompensate_coordinatorCrashBeforeSave");
        URI lra = prepareCompensateLra("idempotent-before-save", COMPENSATE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_BEFORE_SAVE.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} (coordinator crashed), LRA will be cancelled by timeout on recovery",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);

        assertEquals(1, getIdempotentWorkDone(lra),
                "Side effect must be performed once after LRA is eventually cancelled via timeout");
    }

    @Test
    void testAsyncCompensate_withStatus_coordinatorCrashAfterSave() {
        log.info("CompensateIT: testAsyncCompensate_withStatus_coordinatorCrashAfterSave");
        URI lra = prepareCompensateLraAsync(
                "async-after-save",
                COMPENSATE_ASYNC,
                STATUS_FOR_ASYNC);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {}, coordinator crashed",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);
        assertNoActiveLras();
    }

    /**
     * The proxy retargets the cancel after the first coordinator crashes, and the second coordinator
     * resolves it from a status poll instead of replaying the participant call.
     */
    @Test
    void testAsyncCompensate_duplicateCallViaProxyFailover() {
        log.info("CompensateIT: testAsyncCompensate_duplicateCallViaProxyFailover");
        URI lra = prepareCompensateLraAsync(
                "async-duplicate",
                COMPENSATE_ASYNC,
                STATUS_FOR_ASYNC);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} — coordinator crashed after 202, proxy will failover to coordinator-2",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);
        assertNoActiveLras();

        int compensateCalls = getAsyncCallCount(lra);
        int statusCalls = getAsyncStatusCallCount(lra);

        log.info("After async proxy failover: compensateCalls={}, statusCalls={}", compensateCalls, statusCalls);

        assertEquals(1, compensateCalls,
                "Async @Compensate should be called exactly once in END_DURING_CLEANUP failover");
        assertTrue(statusCalls >= 1,
                "Async duplicate path should poll @Status at least once, got " + statusCalls + " polls");
    }

    /**
     * Crash hits between receiving the participant 202 and persisting the in-progress state.
     * Recovery must resolve the transaction via a status poll instead of replaying the call.
     */
    @Test
    void testAsyncCompensate_withStatus_crashAfterReceivingResponse() {
        log.info("CompensateIT: testAsyncCompensate_withStatus_crashAfterReceivingResponse");
        waitForAllCoordinators(CRASH_RECOVERY_WAIT_S);
        resetProxyRouting();
        URI lra = prepareCompensateLraAsync(
                "async-after-response",
                COMPENSATE_ASYNC,
                STATUS_FOR_ASYNC);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_PARTICIPANT_RESPONSE.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} — coordinator crashed after receiving participant 202",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);
        assertNoActiveLras();

        int compensateCalls = getAsyncCallCount(lra);
        int statusCalls = getAsyncStatusCallCount(lra);

        log.info("After async crash recovery: compensateCalls={}, statusCalls={}", compensateCalls, statusCalls);

        assertEquals(1, compensateCalls,
                "Async @Compensate should not be replayed after END_AFTER_PARTICIPANT_RESPONSE; got "
                        + compensateCalls + " calls");
        assertTrue(statusCalls >= 1,
                "Recovery should resolve this path via pre-flight @Status after the crash, got " + statusCalls
                        + " status polls");
    }

    /**
     * Crash hits after the synchronous 200 but before the success is persisted, so recovery
     * may replay the call; the participant's idempotency guard must keep the side effect at one.
     */
    @Test
    void testIdempotentCompensate_crashAfterReceivingResponse() {
        log.info("CompensateIT: testIdempotentCompensate_crashAfterReceivingResponse");
        URI lra = prepareCompensateLra("crash-after-response", COMPENSATE_IDEMPOTENT);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_PARTICIPANT_RESPONSE.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info(
                    "cancelLRA returned {} — coordinator crashed after receiving participant 200 but before persisting FINISH_OK",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);

        int callCount = getIdempotentCallCount(lra);
        int workDone = getIdempotentWorkDone(lra);

        log.info("After crash recovery: callCount={}, workDone={}", callCount, workDone);

        assertTrue(callCount >= 1,
                "Compensate must be called at least once before the LRA resolves, got " + callCount);
        assertEquals(1, workDone,
                "Side effect must be performed exactly once regardless of any recovery replay");
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
            log.info("cancelLRA returned {} (503 from participant — coordinator queues for recovery scan)",
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
            log.info("cancelLRA returned {} for fail scenario",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);

        List<String> activeIds = getActiveIds();
        String targetUid = LRAConstants.getLRAUid(lra);
        boolean stillActive = activeIds.stream()
                .map(LRAConstants::getLRAUid)
                .anyMatch(targetUid::equals);

        assertTrue(!stillActive,
                "LRA should not be in the active list after FailedToCompensate; found in " + activeIds);
    }

}
