package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies the post-terminal-state notification fires with the correct outcome for every
 * resolution path and survives crashes during cleanup.
 */
@QuarkusTest
class AfterLraIT extends TestBase {

    @Override
    protected String participantPath() {
        return "after-lra-participant";
    }

    private static final Logger log = LoggerFactory.getLogger(AfterLraIT.class);

    private static final long LRA_GONE_FAST_MS = 10_000;
    private static final long LRA_GONE_WAIT_MS = 30_000;
    private static final long CRASH_RECOVERY_WAIT_S = 15;

    @Test
    void testAfterLra_onCancel_receivesCancelledStatus() {
        log.info("AfterLraIT: testAfterLra_onCancel_receivesCancelledStatus");
        URI lra = prepareLraWithAfterLra(
                participantClientId("after-cancel"),
                COMPENSATE, COMPLETE, AFTER_LRA);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {}", e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);
        waitForAfterCallCount(lra, 1, LRA_GONE_FAST_MS);

        assertEquals(LRAStatus.Cancelled.name(), getAfterLraStatus(lra),
                "@AfterLRA must receive Cancelled when the LRA is cancelled");
        assertEquals(1, getAfterCallCount(lra),
                "@AfterLRA must be called exactly once in the happy path");
    }

    @Test
    void testAfterLra_onFailedToClose_receivesFailedToCloseStatus() {
        log.info("AfterLraIT: testAfterLra_onFailedToClose_receivesFailedToCloseStatus");
        URI lra = prepareLraWithAfterLra(
                participantClientId("after-failed-close"),
                COMPENSATE, COMPLETE_FAIL, AFTER_LRA);

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} — expected for fail scenario",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);
        waitForAfterCallCount(lra, 1, LRA_GONE_FAST_MS);

        assertEquals(LRAStatus.FailedToClose.name(), getAfterLraStatus(lra),
                "@AfterLRA must receive FailedToClose when @Complete permanently fails");
    }

    @Test
    void testAfterLra_onFailedToCancel_receivesFailedToCancelStatus() {
        log.info("AfterLraIT: testAfterLra_onFailedToCancel_receivesFailedToCancelStatus");
        URI lra = prepareLraWithAfterLra(
                participantClientId("after-failed-cancel"),
                COMPENSATE_FAIL, COMPLETE, AFTER_LRA);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} — expected for fail scenario",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);
        waitForAfterCallCount(lra, 1, LRA_GONE_FAST_MS);

        assertEquals(LRAStatus.FailedToCancel.name(), getAfterLraStatus(lra),
                "@AfterLRA must receive FailedToCancel when @Compensate permanently fails");
    }

    // -------------------------------------------------------------------------
    // HA crash scenarios
    // -------------------------------------------------------------------------

    /**
     * Crash hits after the cancel decision is persisted but before any participant call;
     * recovery must drive the participant and still deliver the post-terminal notification.
     */
    @Test
    void testAfterLra_onCancel_coordinatorCrashAfterSave() {
        log.info("AfterLraIT: testAfterLra_onCancel_coordinatorCrashAfterSave");
        URI lra = prepareLraWithAfterLra(
                participantClientId("after-crash-after-save"),
                COMPENSATE, COMPLETE, AFTER_LRA);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("cancelLRA returned 404, treating as already processed");
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} — coordinator crashed as expected",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);
        waitForAfterCallCount(lra, 1, LRA_GONE_WAIT_MS);

        assertEquals(LRAStatus.Cancelled.name(), getAfterLraStatus(lra),
                "@AfterLRA must still be delivered with Cancelled after crash-and-recovery");
        assertEquals(1, getAfterCallCount(lra),
                "@AfterLRA must be called exactly once after recovery");
    }

    /**
     * Crash hits between the participant callbacks and the final cleanup step;
     * recovery must still deliver the post-terminal notification.
     */
    @Test
    void testAfterLra_onClose_coordinatorCrashDuringCleanup() {
        log.info("AfterLraIT: testAfterLra_onClose_coordinatorCrashDuringCleanup");
        URI lra = prepareLraWithAfterLra(
                participantClientId("after-crash-during-cleanup"),
                COMPENSATE, COMPLETE, AFTER_LRA);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} — coordinator crashed during cleanup",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);
        waitForAfterCallCount(lra, 1, LRA_GONE_WAIT_MS);

        assertEquals(LRAStatus.Closed.name(), getAfterLraStatus(lra),
                "@AfterLRA must be delivered with Closed even when coordinator crashed during cleanup");
    }

}
