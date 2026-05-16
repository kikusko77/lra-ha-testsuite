package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Mirrors the asynchronous status-polling resolution paths for nested transactions whose
 * parent ends on a different coordinator.
 */
@QuarkusTest
class NestedStatusIT extends TestBase {

    @Override
    protected String participantPath() {
        return "nested-participant";
    }

    private static final Logger log = Logger.getLogger(NestedStatusIT.class);
    private static final long CRASH_RECOVERY_TIMEOUT_S = 125;

    @Test
    void testAsyncCompensate_statusGone410_lraResolves() {
        log.info("NestedStatusIT: testAsyncCompensate_statusGone410_lraResolves");
        URI parent = startTopLra("nested-status-compensate-gone");
        URI nested = prepareNestedLra(parent, "nested-status-compensate-gone",
                COMPENSATE_ASYNC_STATUS, COMPLETE, STATUS_GONE);

        assertDoesNotThrow(() -> lraClient.cancelLRA(nested));

        waitForNoActiveLra(nested, LRA_GONE_HAPPY_PATH_MS);

        int compensateCalls = getAsyncCompensateCallCount(nested);
        int statusCalls = getStatusGoneCallCount(nested);
        log.infof("compensateCalls=%s statusGoneCalls=%s", compensateCalls, statusCalls);

        assertEquals(1, compensateCalls, "@Compensate must be called exactly once");
        assertTrue(statusCalls >= 1,
                "Coordinator must poll @Status at least once after async 202; got " + statusCalls);
    }

    @Test
    void testAsyncComplete_statusGone410_lraResolves() {
        log.info("NestedStatusIT: testAsyncComplete_statusGone410_lraResolves");
        URI parent = startTopLra("nested-status-complete-gone");
        URI nested = prepareNestedLra(parent, "nested-status-complete-gone",
                COMPENSATE, COMPLETE_ASYNC_STATUS, STATUS_GONE);

        assertDoesNotThrow(() -> lraClient.closeLRA(nested));

        waitForNoActiveLra(nested, LRA_GONE_HAPPY_PATH_MS);

        int completeCalls = getAsyncCompleteCallCount(nested);
        int statusCalls = getStatusGoneCallCount(nested);
        log.infof("completeCalls=%s statusGoneCalls=%s", completeCalls, statusCalls);

        assertEquals(1, completeCalls, "@Complete must be called exactly once");
        assertTrue(statusCalls >= 1,
                "Coordinator must poll @Status at least once after async 202; got " + statusCalls);
    }

    @Test
    void testAsyncCompensate_intermediateCompensatingState_lraResolves() {
        log.info("NestedStatusIT: testAsyncCompensate_intermediateCompensatingState_lraResolves");
        URI parent = startTopLra("nested-status-compensate-intermediate");
        URI nested = prepareNestedLra(parent, "nested-status-compensate-intermediate",
                COMPENSATE_ASYNC_STATUS, COMPLETE, STATUS_INTERMEDIATE_COMPENSATE);

        assertDoesNotThrow(() -> lraClient.cancelLRA(nested));

        waitForStatusIntermediateCompensateCallCount(nested, 1, LRA_GONE_AFTER_RECOVERY_MS);
        waitForNoActiveLra(nested, LRA_GONE_HAPPY_PATH_MS);

        int compensateCalls = getAsyncCompensateCallCount(nested);
        int statusCalls = getStatusIntermediateCompensateCallCount(nested);
        log.infof("compensateCalls=%s intermediateStatusCalls=%s", compensateCalls, statusCalls);

        assertEquals(1, compensateCalls, "@Compensate must be called exactly once");
        assertTrue(statusCalls >= 1,
                "Coordinator must poll @Status at least once after async 202; got " + statusCalls);
    }

    @Test
    void testAsyncComplete_intermediateCompletingState_lraResolves() {
        log.info("NestedStatusIT: testAsyncComplete_intermediateCompletingState_lraResolves");
        URI parent = startTopLra("nested-status-complete-intermediate");
        URI nested = prepareNestedLra(parent, "nested-status-complete-intermediate",
                COMPENSATE, COMPLETE_ASYNC_STATUS, STATUS_INTERMEDIATE_COMPLETE);

        assertDoesNotThrow(() -> lraClient.closeLRA(nested));

        waitForStatusIntermediateCompleteCallCount(nested, 1, LRA_GONE_AFTER_RECOVERY_MS);
        waitForNoActiveLra(nested, LRA_GONE_HAPPY_PATH_MS);

        int completeCalls = getAsyncCompleteCallCount(nested);
        int statusCalls = getStatusIntermediateCompleteCallCount(nested);
        log.infof("completeCalls=%s intermediateStatusCalls=%s", completeCalls, statusCalls);

        assertEquals(1, completeCalls, "@Complete must be called exactly once");
        assertTrue(statusCalls >= 1,
                "Coordinator must poll @Status at least once after async 202; got " + statusCalls);
    }

    @Test
    void testAsyncCompensate_statusGone_coordinatorCrashAfterParticipantResponse() {
        log.info("NestedStatusIT: testAsyncCompensate_statusGone_coordinatorCrashAfterParticipantResponse");
        waitForAllCoordinators(CRASH_RECOVERY_TIMEOUT_S);
        resetProxyRouting();

        URI parent = startTopLra("nested-status-compensate-gone-crash");
        URI nested = prepareNestedLra(parent, "nested-status-compensate-gone-crash",
                COMPENSATE_ASYNC_STATUS, COMPLETE, STATUS_GONE);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_PARTICIPANT_RESPONSE);

        cancel(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);

        int compensateCalls = getAsyncCompensateCallCount(nested);
        int statusCalls = getStatusGoneCallCount(nested);
        log.infof("After crash recovery: compensateCalls=%s statusGoneCalls=%s", compensateCalls, statusCalls);

        assertEquals(1, compensateCalls,
                "@Compensate must not be replayed when @Status 410 already signals completion; got "
                        + compensateCalls);
        assertTrue(statusCalls >= 1,
                "Recovery must poll @Status at least once; got " + statusCalls);
    }

    @Test
    void testAsyncComplete_intermediateStatus_coordinatorCrashAfterParticipantResponse() {
        log.info("NestedStatusIT: testAsyncComplete_intermediateStatus_coordinatorCrashAfterParticipantResponse");
        waitForAllCoordinators(CRASH_RECOVERY_TIMEOUT_S);
        resetProxyRouting();

        URI parent = startTopLra("nested-status-complete-intermediate-crash");
        URI nested = prepareNestedLra(parent, "nested-status-complete-intermediate-crash",
                COMPENSATE, COMPLETE_ASYNC_STATUS, STATUS_INTERMEDIATE_COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_PARTICIPANT_RESPONSE);

        close(nested);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);

        int completeCalls = getAsyncCompleteCallCount(nested);
        int statusCalls = getStatusIntermediateCompleteCallCount(nested);
        log.infof("After crash recovery: completeCalls=%s intermediateStatusCalls=%s", completeCalls, statusCalls);

        assertEquals(1, completeCalls,
                "@Complete must not be replayed when recovery resolves via @Status polling; got "
                        + completeCalls);
        assertTrue(statusCalls >= 1,
                "Recovery must poll @Status at least once; got " + statusCalls);
    }
}
