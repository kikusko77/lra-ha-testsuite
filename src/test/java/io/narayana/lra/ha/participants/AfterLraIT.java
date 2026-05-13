package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

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

    private static final Logger log = Logger.getLogger(AfterLraIT.class);
    private static final long CRASH_RECOVERY_TIMEOUT_S = 15;

    @Test
    void testAfterLra_onCancel_receivesCancelledStatus() {
        log.info("AfterLraIT: testAfterLra_onCancel_receivesCancelledStatus");
        URI lra = prepareLraWithAfter(
                participantClientId("after-cancel"),
                COMPENSATE, COMPLETE);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s", e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        waitForNoActiveLra(lra, LRA_GONE_HAPPY_PATH_MS);
        waitForAfterCallCount(lra, 1, LRA_GONE_HAPPY_PATH_MS);

        assertEquals(LRAStatus.Cancelled.name(), getAfterLraStatus(lra),
                "@AfterLRA must receive Cancelled when the LRA is cancelled");
        assertEquals(1, getAfterCallCount(lra),
                "@AfterLRA must be called exactly once in the happy path");
    }

    @Test
    void testAfterLra_onFailedToClose_receivesFailedToCloseStatus() {
        log.info("AfterLraIT: testAfterLra_onFailedToClose_receivesFailedToCloseStatus");
        URI lra = prepareLraWithAfter(
                participantClientId("after-failed-close"),
                COMPENSATE, COMPLETE_FAIL);

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("closeLRA returned %s — expected for fail scenario",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        waitForNoActiveLra(lra, LRA_GONE_HAPPY_PATH_MS);
        waitForAfterCallCount(lra, 1, LRA_GONE_HAPPY_PATH_MS);

        assertEquals(LRAStatus.FailedToClose.name(), getAfterLraStatus(lra),
                "@AfterLRA must receive FailedToClose when @Complete permanently fails");
        assertEquals(1, getAfterCallCount(lra),
                "@AfterLRA must be called exactly once even on failed close");
    }

    @Test
    void testAfterLra_onFailedToCancel_receivesFailedToCancelStatus() {
        log.info("AfterLraIT: testAfterLra_onFailedToCancel_receivesFailedToCancelStatus");
        URI lra = prepareLraWithAfter(
                participantClientId("after-failed-cancel"),
                COMPENSATE_FAIL, COMPLETE);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s — expected for fail scenario",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        waitForNoActiveLra(lra, LRA_GONE_HAPPY_PATH_MS);
        waitForAfterCallCount(lra, 1, LRA_GONE_HAPPY_PATH_MS);

        assertEquals(LRAStatus.FailedToCancel.name(), getAfterLraStatus(lra),
                "@AfterLRA must receive FailedToCancel when @Compensate permanently fails");
        assertEquals(1, getAfterCallCount(lra),
                "@AfterLRA must be called exactly once even on failed cancel");
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
        URI lra = prepareLraWithAfter(
                participantClientId("after-crash-after-save"),
                COMPENSATE, COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("cancelLRA returned 404, treating as already processed");
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s — coordinator crashed as expected",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);
        waitForAfterCallCount(lra, 1, LRA_GONE_AFTER_RECOVERY_MS);

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
        URI lra = prepareLraWithAfter(
                participantClientId("after-crash-during-cleanup"),
                COMPENSATE, COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_DURING_CLEANUP);

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("closeLRA returned %s — coordinator crashed during cleanup",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);
        waitForAfterCallCount(lra, 1, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(LRAStatus.Closed.name(), getAfterLraStatus(lra),
                "@AfterLRA must be delivered with Closed even when coordinator crashed during cleanup");
        assertEquals(1, getAfterCallCount(lra),
                "@AfterLRA must be called exactly once after crash-during-cleanup recovery");
    }

}
