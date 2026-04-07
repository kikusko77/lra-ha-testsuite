package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.LRAConstants;
import io.naryana.lra.ha.LRAParticipant;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for the @Compensate lifecycle in a multi-coordinator HA setup.
 * Covers the happy path, idempotent and async compensate, coordinator crash
 * scenarios at various inject points, transient participant failures, and
 * permanent participant failures that move the LRA to FailedToCancel.
 */
@QuarkusTest
class CompensateIT extends TestBase {

    private static final Logger log = LoggerFactory.getLogger(CompensateIT.class);

    /** Timeout used after coordinator crash tests — long enough for Docker to restart. */
    private static final long CRASH_RECOVERY_WAIT_S = 120;

    /** Timeout for LRA disappearance after coordinator has recovered. */
    private static final long LRA_GONE_WAIT_MS = 30_000;

    /** Timeout for non-crash tests where coordinator stays up. */
    private static final long LRA_GONE_FAST_MS = 10_000;

    /** Timeout for tests that wait for Arjuna's periodic recovery scan (default interval ~120 s). */
    private static final long RECOVERY_SCAN_WAIT_MS = 300_000; // 5 minutes

    /** Basic cancel: synchronous compensate returns 200 and the LRA disappears from all coordinators. */
    @Test
    void testCompensateHappyPath() {
        log.info("CompensateIT: testCompensateHappyPath");
        URI lra = prepareLra("happy");

        assertDoesNotThrow(() -> lraClient.cancelLRA(lra));

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);
        assertNoActiveLras();
    }

    /** Normal cancel with the idempotent endpoint; verifies exactly one call and one side effect. */
    @Test
    void testIdempotentCompensate_happyPath() {
        log.info("CompensateIT: testIdempotentCompensate_happyPath");
        URI lra = prepareLraIdempotent("idempotent-happy");

        assertDoesNotThrow(() -> lraClient.cancelLRA(lra));

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);

        assertEquals(1, getCompensateCallCount(lra),
                "Idempotent compensate should be called exactly once in the happy path");
        assertEquals(1, getCompensateWorkDone(lra),
                "Side effect must be performed exactly once");
    }

    /**
     * Coordinator crashes at END_DURING_CLEANUP, after @Compensate was already called and FINISH_OK persisted.
     * Recovery usually does not re-call @Compensate, but the idempotent guard protects against it if it does.
     */
    @Test
    void testIdempotentCompensate_coordinatorCrashDuringCleanup() {
        log.info("CompensateIT: testIdempotentCompensate_coordinatorCrashDuringCleanup");
        URI lra = prepareLraIdempotent("idempotent-during-cleanup");

        injectEnable(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} (coordinator crashed), proceeding to recovery check",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);

        int callCount = getCompensateCallCount(lra);
        int workDone = getCompensateWorkDone(lra);

        log.info("After crash recovery: callCount={}, workDone={}", callCount, workDone);

        // callCount is >= 1: called at least once before the crash. May be 2 if the
        // crash happened before Arjuna could persist FINISH_OK (timing-dependent).
        assertTrue(callCount >= 1, "Compensate must have been called at least once, got " + callCount);
        // workDone must always be 1 — idempotency guard must fire on any retry.
        assertEquals(1, workDone, "Side effect must be performed exactly once regardless of retry count");
    }

    /**
     * Coordinator crashes at END_AFTER_SAVE: Cancelling is persisted but participants were not called yet.
     * Recovery must call @Compensate and the side effect must happen exactly once.
     */
    @Test
    void testIdempotentCompensate_coordinatorCrashAfterSave() {
        log.info("CompensateIT: testIdempotentCompensate_coordinatorCrashAfterSave");
        URI lra = prepareLraIdempotent("idempotent-after-save");

        injectEnable(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

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

        assertEquals(1, getCompensateWorkDone(lra),
                "Side effect must be performed exactly once after crash-and-recovery");
    }

    /**
     * Coordinator crashes at END_BEFORE_SAVE: the cancel decision is never persisted.
     * The LRA stays Active and gets auto-cancelled by timeout when the coordinator restarts.
     */
    @Test
    void testIdempotentCompensate_coordinatorCrashBeforeSave() {
        log.info("CompensateIT: testIdempotentCompensate_coordinatorCrashBeforeSave");
        URI lra = prepareLraIdempotent("idempotent-before-save");

        injectEnable(nextRoutedCoordinator(), InjectPoint.END_BEFORE_SAVE.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} (coordinator crashed), LRA will be cancelled by timeout on recovery",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);

        assertEquals(1, getCompensateWorkDone(lra),
                "Side effect must be performed once after LRA is eventually cancelled via timeout");
    }

    /** Async compensate: coordinator receives 202 and polls @Status until it gets Compensated. */
    @Test
    void testAsyncCompensate_withStatus_happyPath() {
        log.info("CompensateIT: testAsyncCompensate_withStatus_happyPath");
        URI lra = prepareLraAsync("async-happy");

        assertDoesNotThrow(() -> lraClient.cancelLRA(lra));

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);
        assertNoActiveLras();
    }

    /**
     * Coordinator crashes at END_AFTER_SAVE on the async path.
     * Recovery calls @Compensate, gets 202, polls @Status, and finalises the LRA.
     */
    @Test
    void testAsyncCompensate_withStatus_coordinatorCrashAfterSave() {
        log.info("CompensateIT: testAsyncCompensate_withStatus_coordinatorCrashAfterSave");
        URI lra = prepareLraAsync("async-after-save");

        injectEnable(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

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
     * END_DURING_CLEANUP with async compensate causes @Compensate to be called twice via proxy failover.
     * The first coordinator crashes before sending a response, so the proxy reroutes to a second coordinator
     * which finds the LRA still in Cancelling state and calls @Compensate again.
     *
     * @Status correctly reports Compensated on the second call, preventing an infinite loop.
     */
    @Test
    void testAsyncCompensate_duplicateCallViaProxyFailover() {
        log.info("CompensateIT: testAsyncCompensate_duplicateCallViaProxyFailover");
        URI lra = prepareLraAsync("async-duplicate");

        injectEnable(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} — coordinator crashed after 202, proxy will failover to coordinator-2",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);
        assertNoActiveLras();
    }

    /**
     * Coordinator crashes at END_AFTER_PARTICIPANT_RESPONSE: the participant's 202 was received but
     * the Compensating state was never persisted, so on recovery accepted=false.
     * The pre-flight @Status check in preflightGetStatus should detect that compensation
     * already happened and resolve the LRA without replaying @Compensate.
     */
    @Test
    void testAsyncCompensate_withStatus_crashAfterReceivingResponse() {
        log.info("CompensateIT: testAsyncCompensate_withStatus_crashAfterReceivingResponse");
        waitForAllCoordinators(CRASH_RECOVERY_WAIT_S);
        resetProxyRouting();
        URI lra = prepareLraAsync("async-after-response");

        injectEnable(nextRoutedCoordinator(), InjectPoint.END_AFTER_PARTICIPANT_RESPONSE.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} — coordinator crashed after receiving participant 202",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);
        assertNoActiveLras();

        int compensateCalls = getAsyncCompensateCallCount(lra);
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
     * Coordinator crashes at END_AFTER_PARTICIPANT_RESPONSE after a synchronous 200 from @Compensate,
     * before FINISH_OK is persisted. The LRA stays in Cancelling, so recovery may call @Compensate again.
     * The idempotent guard ensures the side effect runs at most once regardless.
     */
    @Test
    void testIdempotentCompensate_crashAfterReceivingResponse() {
        log.info("CompensateIT: testIdempotentCompensate_crashAfterReceivingResponse");
        URI lra = prepareLraIdempotent("crash-after-response");

        injectEnable(nextRoutedCoordinator(), InjectPoint.END_AFTER_PARTICIPANT_RESPONSE.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info(
                    "cancelLRA returned {} — coordinator crashed after receiving participant 200 but before persisting FINISH_OK",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);

        int callCount = getCompensateCallCount(lra);
        int workDone = getCompensateWorkDone(lra);

        log.info("After crash recovery: callCount={}, workDone={}", callCount, workDone);

        assertTrue(callCount >= 1,
                "Compensate must be called at least once before the LRA resolves, got " + callCount);
        assertEquals(1, workDone,
                "Side effect must be performed exactly once regardless of any recovery replay");
    }

    /**
     * Participant returns 503 on the first call and 200 on retry.
     * The coordinator does not retry inline; it sets accepted=true and waits for Arjuna's recovery scan
     * (~120 s) to retry. Two @Compensate calls are expected total.
     */
    @Test
    void testParticipantTransientFailure_coordinatorRetries() {
        log.info("CompensateIT: testParticipantTransientFailure_coordinatorRetries");
        URI lra = prepareLraUnreachable("unreachable");

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} (503 from participant — coordinator queues for recovery scan)",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }
        waitForNoActiveLra(lra, RECOVERY_SCAN_WAIT_MS);
        assertNoActiveLras();
    }

    /**
     * @Compensate always returns 409, so the coordinator moves the LRA to FailedToCancel.
     *             The test checks that the LRA leaves the active list.
     */
    @Test
    void testFailedToCompensate_lraMovesToFailedToCancel() {
        log.info("CompensateIT: testFailedToCompensate_lraMovesToFailedToCancel");
        URI lra = prepareLraFail("fail");

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

    /** Starts a 30-second LRA enrolled with the synchronous compensate endpoint. */
    private URI prepareLra(String suffix) {
        injectResetAll();
        resetParticipantState();

        URI lra = startLra("io.narayana.lra.ha.LRAParticipant#compensate-" + suffix);
        lrasToAfterFinish.add(lra);

        URI compensate = participantUri(LRAParticipant.COMPENSATE_LRA);
        URI complete = participantUri(LRAParticipant.COMPLETE_LRA);
        URI recovery = lraClient.enlistCompensator(lra, 30L, buildCompensatorLink(compensate, complete), new StringBuilder());
        log.info("Enrolled with compensate={}, recoveryUrl={}", compensate, recovery);
        return lra;
    }

    /** Starts a 30-second LRA enrolled with the idempotent compensate endpoint (no @Status needed). */
    private URI prepareLraIdempotent(String suffix) {
        injectResetAll();
        resetParticipantState();

        URI lra = startLra("io.narayana.lra.ha.LRAParticipant#idempotent-" + suffix);
        lrasToAfterFinish.add(lra);

        URI compensate = participantUri(LRAParticipant.COMPENSATE_IDEMPOTENT);
        URI complete = participantUri(LRAParticipant.COMPLETE_LRA);
        URI recovery = lraClient.enlistCompensator(lra, 30L, buildCompensatorLink(compensate, complete), new StringBuilder());
        log.info("Enrolled idempotent compensate={}, recoveryUrl={}", compensate, recovery);
        return lra;
    }

    /** Starts a 30-second LRA enrolled with the async compensate and its status endpoint. */
    private URI prepareLraAsync(String suffix) {
        injectResetAll();
        resetParticipantState();

        URI lra = startLra("io.narayana.lra.ha.LRAParticipant#async-" + suffix);
        lrasToAfterFinish.add(lra);

        URI compensate = participantUri(LRAParticipant.COMPENSATE_ASYNC);
        URI complete = participantUri(LRAParticipant.COMPLETE_LRA);
        URI status = participantUri(LRAParticipant.STATUS_FOR_ASYNC);
        URI recovery = lraClient.enlistCompensator(lra, 30L, buildCompensatorLinkWithStatus(compensate, complete, status),
                new StringBuilder());
        log.info("Enrolled async compensate={}, status={}, recoveryUrl={}", compensate, status, recovery);
        return lra;
    }

    /** Starts a 30-second LRA enrolled with the transient-failure compensate endpoint (503 then 200). */
    private URI prepareLraUnreachable(String suffix) {
        injectResetAll();
        resetParticipantState();

        URI lra = startLra("io.narayana.lra.ha.LRAParticipant#unreachable-" + suffix);
        lrasToAfterFinish.add(lra);

        URI compensate = participantUri(LRAParticipant.COMPENSATE_UNREACHABLE);
        URI complete = participantUri(LRAParticipant.COMPLETE_LRA);
        URI recovery = lraClient.enlistCompensator(lra, 30L, buildCompensatorLink(compensate, complete), new StringBuilder());
        log.info("Enrolled unreachable compensate={}, recoveryUrl={}", compensate, recovery);
        return lra;
    }

    /** Starts a 30-second LRA enrolled with the permanently-failing compensate endpoint (always 409). */
    private URI prepareLraFail(String suffix) {
        injectResetAll();
        resetParticipantState();

        URI lra = startLra("io.narayana.lra.ha.LRAParticipant#fail-" + suffix);
        lrasToAfterFinish.add(lra);

        URI compensate = participantUri(LRAParticipant.COMPENSATE_FAIL);
        URI complete = participantUri(LRAParticipant.COMPLETE_LRA);
        URI recovery = lraClient.enlistCompensator(lra, 30L, buildCompensatorLink(compensate, complete), new StringBuilder());
        log.info("Enrolled fail compensate={}, recoveryUrl={}", compensate, recovery);
        return lra;
    }

    /** Starts a 30-second LRA with a unique client ID. */
    private URI startLra(String clientId) {
        URI lra = lraClient.startLRA(null, clientId + "-" + System.nanoTime(), 30L, ChronoUnit.SECONDS, true);
        log.info("Started LRA: {}", lra);
        return lra;
    }

    /** Asserts that no active LRAs exist across the entire cluster. */
    private void assertNoActiveLras() {
        List<String> activeIds = getActiveIds();
        long unique = activeIds.stream().distinct().count();
        assertEquals(0, unique, "Expected no active LRAs but got: " + activeIds);
    }
}
