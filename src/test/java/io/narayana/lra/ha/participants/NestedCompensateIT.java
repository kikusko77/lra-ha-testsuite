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
 * Mirrors the cancel-callback scenarios for a child transaction whose parent runs on a
 * different coordinator, exercising the cross-coordinator cascade and recovery paths.
 */
@QuarkusTest
class NestedCompensateIT extends TestBase {

    @Override
    protected String participantPath() {
        return "nested-participant";
    }

    private static final Logger log = Logger.getLogger(NestedCompensateIT.class);

    private static final long CRASH_RECOVERY_WAIT_S = 15;
    private static final long LRA_GONE_WAIT_MS = 30_000;
    private static final long LRA_GONE_FAST_MS = 10_000;
    private static final long RECOVERY_SCAN_WAIT_MS = 20_000;

    @Test
    void testIdempotentCompensate_coordinatorCrashDuringCleanup() {
        log.info("NestedCompensateIT: testIdempotentCompensate_coordinatorCrashDuringCleanup");
        URI parent = startTopLra("nested-idempotent-during-cleanup");
        URI nested = prepareNestedLra(parent, "nested-idempotent-during-cleanup",
                COMPENSATE_IDEMPOTENT, COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s (coordinator crashed)",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(nested, LRA_GONE_WAIT_MS);

        int callCount = getIdempotentCallCount(nested);
        int workDone = getIdempotentWorkDone(nested);
        log.infof("After crash recovery: callCount=%s, workDone=%s", callCount, workDone);

        assertTrue(callCount >= 1, "Compensate must have been called at least once, got " + callCount);
        assertEquals(1, workDone, "Side effect must be performed exactly once regardless of retry count");
    }

    @Test
    void testIdempotentCompensate_coordinatorCrashAfterSave() {
        log.info("NestedCompensateIT: testIdempotentCompensate_coordinatorCrashAfterSave");
        URI parent = startTopLra("nested-idempotent-after-save");
        URI nested = prepareNestedLra(parent, "nested-idempotent-after-save",
                COMPENSATE_IDEMPOTENT, COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("cancelLRA returned 404, treating as already processed");
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s (coordinator crashed)",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(nested, LRA_GONE_WAIT_MS);

        assertEquals(1, getIdempotentWorkDone(nested),
                "Side effect must be performed exactly once after crash-and-recovery");
    }

    @Test
    void testIdempotentCompensate_coordinatorCrashBeforeSave() {
        log.info("NestedCompensateIT: testIdempotentCompensate_coordinatorCrashBeforeSave");
        URI parent = startTopLra("nested-idempotent-before-save");
        URI nested = prepareNestedLra(parent, "nested-idempotent-before-save",
                COMPENSATE_IDEMPOTENT, COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_BEFORE_SAVE.name());

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s (coordinator crashed)",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(nested, LRA_GONE_WAIT_MS);

        assertEquals(1, getIdempotentWorkDone(nested),
                "Side effect must be performed once after the nested LRA is eventually cancelled");
    }

    @Test
    void testAsyncCompensate_withStatus_coordinatorCrashAfterSave() {
        log.info("NestedCompensateIT: testAsyncCompensate_withStatus_coordinatorCrashAfterSave");
        URI parent = startTopLra("nested-async-after-save");
        URI nested = prepareNestedLra(parent, "nested-async-after-save",
                COMPENSATE_ASYNC, COMPLETE, STATUS_FOR_ASYNC);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s, coordinator crashed",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(nested, LRA_GONE_WAIT_MS);
    }

    @Test
    void testAsyncCompensate_duplicateCallViaProxyFailover() {
        log.info("NestedCompensateIT: testAsyncCompensate_duplicateCallViaProxyFailover");
        URI parent = startTopLra("nested-async-duplicate");
        URI nested = prepareNestedLra(parent, "nested-async-duplicate",
                COMPENSATE_ASYNC, COMPLETE, STATUS_FOR_ASYNC);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s — coordinator crashed after 202",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(nested, LRA_GONE_WAIT_MS);

        int compensateCalls = getAsyncCallCount(nested);
        int statusCalls = getAsyncStatusCallCount(nested);
        log.infof("After async proxy failover: compensateCalls=%s, statusCalls=%s", compensateCalls, statusCalls);

        assertEquals(1, compensateCalls,
                "Async @Compensate should be called exactly once in END_DURING_CLEANUP failover");
        assertTrue(statusCalls >= 1,
                "Async duplicate path should poll @Status at least once, got " + statusCalls + " polls");
    }

    @Test
    void testAsyncCompensate_withStatus_crashAfterReceivingResponse() {
        log.info("NestedCompensateIT: testAsyncCompensate_withStatus_crashAfterReceivingResponse");
        waitForAllCoordinators(CRASH_RECOVERY_WAIT_S);
        resetProxyRouting();

        URI parent = startTopLra("nested-async-after-response");
        URI nested = prepareNestedLra(parent, "nested-async-after-response",
                COMPENSATE_ASYNC, COMPLETE, STATUS_FOR_ASYNC);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_PARTICIPANT_RESPONSE.name());

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s — coordinator crashed after 202",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(nested, LRA_GONE_WAIT_MS);

        int compensateCalls = getAsyncCallCount(nested);
        int statusCalls = getAsyncStatusCallCount(nested);
        log.infof("After async crash recovery: compensateCalls=%s, statusCalls=%s", compensateCalls, statusCalls);

        assertEquals(1, compensateCalls,
                "Async @Compensate should not be replayed after END_AFTER_PARTICIPANT_RESPONSE; got "
                        + compensateCalls + " calls");
        assertTrue(statusCalls >= 1,
                "Recovery should resolve via pre-flight @Status, got " + statusCalls + " polls");
    }

    @Test
    void testIdempotentCompensate_crashAfterReceivingResponse() {
        log.info("NestedCompensateIT: testIdempotentCompensate_crashAfterReceivingResponse");
        URI parent = startTopLra("nested-crash-after-response");
        URI nested = prepareNestedLra(parent, "nested-crash-after-response",
                COMPENSATE_IDEMPOTENT, COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_PARTICIPANT_RESPONSE.name());

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s — coordinator crashed after 200",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(nested, LRA_GONE_WAIT_MS);

        int callCount = getIdempotentCallCount(nested);
        int workDone = getIdempotentWorkDone(nested);
        log.infof("After crash recovery: callCount=%s, workDone=%s", callCount, workDone);

        assertTrue(callCount >= 1,
                "Compensate must be called at least once, got " + callCount);
        assertEquals(1, workDone,
                "Side effect must be performed exactly once regardless of any recovery replay");
    }

    @Test
    void testParticipantTransientFailure_coordinatorRetries() {
        log.info("NestedCompensateIT: testParticipantTransientFailure_coordinatorRetries");
        URI parent = startTopLra("nested-unreachable");
        URI nested = prepareNestedLra(parent, "nested-unreachable",
                COMPENSATE_UNREACHABLE, COMPLETE);

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s (503 from participant)",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }
        waitForNoActiveLra(nested, RECOVERY_SCAN_WAIT_MS);
    }

    @Test
    void testFailedToCompensate_lraMovesToFailedToCancel() {
        log.info("NestedCompensateIT: testFailedToCompensate_lraMovesToFailedToCancel");
        URI parent = startTopLra("nested-fail");
        URI nested = prepareNestedLra(parent, "nested-fail",
                COMPENSATE_FAIL, COMPLETE);

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s for fail scenario",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);

        List<String> activeIds = getActiveIds();
        String nestedUid = LRAConstants.getLRAUid(nested);
        boolean stillActive = activeIds.stream()
                .map(LRAConstants::getLRAUid)
                .anyMatch(nestedUid::equals);

        assertTrue(!stillActive,
                "Nested LRA should not be in the active list after FailedToCompensate; found in " + activeIds);
    }
}
