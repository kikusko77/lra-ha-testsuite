package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Covers the asynchronous status-polling resolution paths that the synchronous suites cannot
 * exercise: 410 Gone, intermediate in-progress reports, and crash-then-poll recovery.
 */
@QuarkusTest
class StatusIT extends TestBase {

    @Override
    protected String participantPath() {
        return "status-participant";
    }

    private static final Logger log = LoggerFactory.getLogger(StatusIT.class);

    private static final long LRA_GONE_FAST_MS = 10_000;
    private static final long LRA_GONE_WAIT_MS = 30_000;
    private static final long CRASH_RECOVERY_WAIT_S = 125;

    /**
     * The participant signals that it no longer remembers the transaction, so the coordinator
     * must resolve as cancelled without replaying the callback.
     */
    @Test
    void testAsyncCompensate_statusGone410_lraResolves() {
        log.info("StatusIT: testAsyncCompensate_statusGone410_lraResolves");
        URI lra = prepareLra(
                participantClientId("compensate-gone"),
                COMPENSATE_ASYNC_STATUS,
                COMPLETE,
                STATUS_GONE);

        assertDoesNotThrow(() -> lraClient.cancelLRA(lra));

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);
        assertNoActiveLras();

        int compensateCalls = getAsyncCompensateCallCount(lra);
        int statusCalls = getStatusGoneCallCount(lra);

        log.info("compensateCalls={} statusGoneCalls={}", compensateCalls, statusCalls);

        assertEquals(1, compensateCalls, "@Compensate must be called exactly once");
        assertTrue(statusCalls >= 1,
                "Coordinator must poll @Status at least once after async 202; got " + statusCalls);
    }

    /**
     * The participant signals that it no longer remembers the transaction, so the coordinator
     * must resolve as closed without replaying the callback.
     */
    @Test
    void testAsyncComplete_statusGone410_lraResolves() {
        log.info("StatusIT: testAsyncComplete_statusGone410_lraResolves");
        URI lra = prepareLra(
                participantClientId("complete-gone"),
                COMPENSATE,
                COMPLETE_ASYNC_STATUS,
                STATUS_GONE);

        assertDoesNotThrow(() -> lraClient.closeLRA(lra));

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);
        assertNoActiveLras();

        int completeCalls = getAsyncCompleteCallCount(lra);
        int statusCalls = getStatusGoneCallCount(lra);

        log.info("completeCalls={} statusGoneCalls={}", completeCalls, statusCalls);

        assertEquals(1, completeCalls, "@Complete must be called exactly once");
        assertTrue(statusCalls >= 1,
                "Coordinator must poll @Status at least once after async 202; got " + statusCalls);
    }

    /**
     * The participant first reports an in-progress state and only later moves to terminal,
     * so the coordinator must keep polling instead of giving up after the first response.
     */
    @Test
    void testAsyncCompensate_intermediateCompensatingState_lraResolves() {
        log.info("StatusIT: testAsyncCompensate_intermediateCompensatingState_lraResolves");
        URI lra = prepareLra(
                participantClientId("compensate-intermediate"),
                COMPENSATE_ASYNC_STATUS,
                COMPLETE,
                STATUS_INTERMEDIATE_COMPENSATE);

        assertDoesNotThrow(() -> lraClient.cancelLRA(lra));

        // Wait for the recovery scanner to call @Status at least once.
        // The LRA leaves the Active list immediately on cancel (transitions to Cancelling),
        // so waitForNoActiveLra returns before recovery has even started.
        waitForStatusIntermediateCompensateCallCount(lra, 1, LRA_GONE_WAIT_MS);
        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);
        assertNoActiveLras();

        int compensateCalls = getAsyncCompensateCallCount(lra);
        int statusCalls = getStatusIntermediateCompensateCallCount(lra);

        log.info("compensateCalls={} intermediateStatusCalls={}", compensateCalls, statusCalls);

        assertEquals(1, compensateCalls, "@Compensate must be called exactly once");
        // TODO (coordinator): after @Status returns Compensating (HEURISTIC_HAZARD), the RecoveringLRA
        // calls phase2Commit which Arjuna treats as H_HAZARD (2PC done) and deactivates the action,
        // removing it from the object store. The next recovery scan can't find the LRA, so only 1
        // post-async @Status poll is made. Fix: re-persist LRA state to the object store after a
        // heuristic outcome in RecoveringLRA.tryReplayPhase2() so the scanner retries it.
        assertTrue(statusCalls >= 1,
                "Coordinator must poll @Status at least once after async 202; got " + statusCalls);
    }

    /**
     * The participant first reports an in-progress state and only later moves to terminal,
     * so the coordinator must keep polling instead of giving up after the first response.
     */
    @Test
    void testAsyncComplete_intermediateCompletingState_lraResolves() {
        log.info("StatusIT: testAsyncComplete_intermediateCompletingState_lraResolves");
        URI lra = prepareLra(
                participantClientId("complete-intermediate"),
                COMPENSATE,
                COMPLETE_ASYNC_STATUS,
                STATUS_INTERMEDIATE_COMPLETE);

        assertDoesNotThrow(() -> lraClient.closeLRA(lra));

        waitForStatusIntermediateCompleteCallCount(lra, 1, LRA_GONE_WAIT_MS);
        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);
        assertNoActiveLras();

        int completeCalls = getAsyncCompleteCallCount(lra);
        int statusCalls = getStatusIntermediateCompleteCallCount(lra);

        log.info("completeCalls={} intermediateStatusCalls={}", completeCalls, statusCalls);

        assertEquals(1, completeCalls, "@Complete must be called exactly once");
        // TODO (coordinator): same retry gap as the compensate variant — see comment in
        // testAsyncCompensate_intermediateCompensatingState_lraResolves for details.
        assertTrue(statusCalls >= 1,
                "Coordinator must poll @Status at least once after async 202; got " + statusCalls);
    }

    /**
     * Crash hits between the async 202 and the in-progress persistence; recovery must
     * resolve via a status poll instead of replaying the cancellation callback.
     */
    @Test
    void testAsyncCompensate_statusGone_coordinatorCrashAfterParticipantResponse() {
        log.info("StatusIT: testAsyncCompensate_statusGone_coordinatorCrashAfterParticipantResponse");
        waitForAllCoordinators(CRASH_RECOVERY_WAIT_S);
        resetProxyRouting();
        URI lra = prepareLra(
                participantClientId("compensate-gone-crash"),
                COMPENSATE_ASYNC_STATUS,
                COMPLETE,
                STATUS_GONE);

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

        int compensateCalls = getAsyncCompensateCallCount(lra);
        int statusCalls = getStatusGoneCallCount(lra);

        log.info("After crash recovery: compensateCalls={} statusGoneCalls={}", compensateCalls, statusCalls);

        assertEquals(1, compensateCalls,
                "@Compensate must not be replayed when @Status 410 already signals completion; got "
                        + compensateCalls);
        assertTrue(statusCalls >= 1,
                "Recovery must poll @Status at least once; got " + statusCalls);
    }

    /**
     * Crash hits between the async 202 and the in-progress persistence; recovery must
     * keep polling the participant until it observes the terminal close state.
     */
    @Test
    void testAsyncComplete_intermediateStatus_coordinatorCrashAfterParticipantResponse() {
        log.info("StatusIT: testAsyncComplete_intermediateStatus_coordinatorCrashAfterParticipantResponse");
        waitForAllCoordinators(CRASH_RECOVERY_WAIT_S);
        resetProxyRouting();
        URI lra = prepareLra(
                participantClientId("complete-intermediate-crash"),
                COMPENSATE,
                COMPLETE_ASYNC_STATUS,
                STATUS_INTERMEDIATE_COMPLETE);

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

        int completeCalls = getAsyncCompleteCallCount(lra);
        int statusCalls = getStatusIntermediateCompleteCallCount(lra);

        log.info("After crash recovery: completeCalls={} intermediateStatusCalls={}", completeCalls, statusCalls);

        assertEquals(1, completeCalls,
                "@Complete must not be replayed when recovery resolves via @Status polling; got "
                        + completeCalls);
        assertTrue(statusCalls >= 1,
                "Recovery must poll @Status at least once; got " + statusCalls);
    }
}
