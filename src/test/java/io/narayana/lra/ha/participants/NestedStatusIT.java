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
 * Mirrors the asynchronous status-polling resolution paths for nested transactions whose
 * parent ends on a different coordinator.
 */
@QuarkusTest
class NestedStatusIT extends TestBase {

    @Override
    protected String participantPath() {
        return "nested-participant";
    }

    private static final Logger log = LoggerFactory.getLogger(NestedStatusIT.class);

    private static final long LRA_GONE_FAST_MS = 10_000;
    private static final long LRA_GONE_WAIT_MS = 30_000;
    private static final long CRASH_RECOVERY_WAIT_S = 125;

    @Test
    void testAsyncCompensate_statusGone410_lraResolves() {
        log.info("NestedStatusIT: testAsyncCompensate_statusGone410_lraResolves");
        URI parent = startTopLra("nested-status-compensate-gone");
        URI nested = prepareNestedLra(parent, "nested-status-compensate-gone",
                COMPENSATE_ASYNC_STATUS, COMPLETE, STATUS_GONE);

        assertDoesNotThrow(() -> lraClient.cancelLRA(nested));

        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);

        int compensateCalls = getAsyncCompensateCallCount(nested);
        int statusCalls = getStatusGoneCallCount(nested);
        log.info("compensateCalls={} statusGoneCalls={}", compensateCalls, statusCalls);

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

        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);

        int completeCalls = getAsyncCompleteCallCount(nested);
        int statusCalls = getStatusGoneCallCount(nested);
        log.info("completeCalls={} statusGoneCalls={}", completeCalls, statusCalls);

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

        waitForStatusIntermediateCompensateCallCount(nested, 1, LRA_GONE_WAIT_MS);
        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);

        int compensateCalls = getAsyncCompensateCallCount(nested);
        int statusCalls = getStatusIntermediateCompensateCallCount(nested);
        log.info("compensateCalls={} intermediateStatusCalls={}", compensateCalls, statusCalls);

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

        waitForStatusIntermediateCompleteCallCount(nested, 1, LRA_GONE_WAIT_MS);
        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);

        int completeCalls = getAsyncCompleteCallCount(nested);
        int statusCalls = getStatusIntermediateCompleteCallCount(nested);
        log.info("completeCalls={} intermediateStatusCalls={}", completeCalls, statusCalls);

        assertEquals(1, completeCalls, "@Complete must be called exactly once");
        assertTrue(statusCalls >= 1,
                "Coordinator must poll @Status at least once after async 202; got " + statusCalls);
    }

    @Test
    void testAsyncCompensate_statusGone_coordinatorCrashAfterParticipantResponse() {
        log.info("NestedStatusIT: testAsyncCompensate_statusGone_coordinatorCrashAfterParticipantResponse");
        waitForAllCoordinators(CRASH_RECOVERY_WAIT_S);
        resetProxyRouting();

        URI parent = startTopLra("nested-status-compensate-gone-crash");
        URI nested = prepareNestedLra(parent, "nested-status-compensate-gone-crash",
                COMPENSATE_ASYNC_STATUS, COMPLETE, STATUS_GONE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_PARTICIPANT_RESPONSE.name());

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} — coordinator crashed after 202",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(nested, LRA_GONE_WAIT_MS);

        int compensateCalls = getAsyncCompensateCallCount(nested);
        int statusCalls = getStatusGoneCallCount(nested);
        log.info("After crash recovery: compensateCalls={} statusGoneCalls={}", compensateCalls, statusCalls);

        assertEquals(1, compensateCalls,
                "@Compensate must not be replayed when @Status 410 already signals completion; got "
                        + compensateCalls);
        assertTrue(statusCalls >= 1,
                "Recovery must poll @Status at least once; got " + statusCalls);
    }

    @Test
    void testAsyncComplete_intermediateStatus_coordinatorCrashAfterParticipantResponse() {
        log.info("NestedStatusIT: testAsyncComplete_intermediateStatus_coordinatorCrashAfterParticipantResponse");
        waitForAllCoordinators(CRASH_RECOVERY_WAIT_S);
        resetProxyRouting();

        URI parent = startTopLra("nested-status-complete-intermediate-crash");
        URI nested = prepareNestedLra(parent, "nested-status-complete-intermediate-crash",
                COMPENSATE, COMPLETE_ASYNC_STATUS, STATUS_INTERMEDIATE_COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_PARTICIPANT_RESPONSE.name());

        try {
            lraClient.closeLRA(nested);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} — coordinator crashed after 202",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(nested, LRA_GONE_WAIT_MS);

        int completeCalls = getAsyncCompleteCallCount(nested);
        int statusCalls = getStatusIntermediateCompleteCallCount(nested);
        log.info("After crash recovery: completeCalls={} intermediateStatusCalls={}", completeCalls, statusCalls);

        assertEquals(1, completeCalls,
                "@Complete must not be replayed when recovery resolves via @Status polling; got "
                        + completeCalls);
        assertTrue(statusCalls >= 1,
                "Recovery must poll @Status at least once; got " + statusCalls);
    }
}
