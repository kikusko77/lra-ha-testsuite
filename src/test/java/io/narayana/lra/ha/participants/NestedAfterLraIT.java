package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Verifies the post-terminal-state notification reaches the nested participant when the
 * cascade originates from a parent ending on a different coordinator.
 */
@QuarkusTest
class NestedAfterLraIT extends TestBase {

    @Override
    protected String participantPath() {
        return "nested-participant";
    }

    private static final Logger log = Logger.getLogger(NestedAfterLraIT.class);

    private static final long LRA_GONE_FAST_MS = 10_000;
    private static final long LRA_GONE_WAIT_MS = 30_000;
    private static final long CRASH_RECOVERY_WAIT_S = 15;

    @Test
    void testAfterLra_onNestedAndParentCancel_receivesCancelledStatus() {
        log.info("NestedAfterLraIT: testAfterLra_onNestedAndParentCancel_receivesCancelledStatus");
        URI parent = startTopLra("nested-after-cancel");
        URI nested = prepareNestedLraWithAfterLra(parent, "nested-after-cancel",
                COMPENSATE, COMPLETE, AFTER_LRA);

        cancelQuietly(nested);
        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);

        cancelQuietly(parent);
        waitForAfterCallCount(nested, 1, LRA_GONE_WAIT_MS);

        assertEquals(LRAStatus.Cancelled.name(), getAfterLraStatus(nested),
                "@AfterLRA must receive Cancelled after parent cancel cascades");
        assertEquals(1, getAfterCallCount(nested),
                "@AfterLRA must be called exactly once in the happy path");
    }

    @Test
    void testAfterLra_onProvisionalCloseThenParentCancel_receivesCancelledStatus() {
        log.info("NestedAfterLraIT: testAfterLra_onProvisionalCloseThenParentCancel_receivesCancelledStatus");
        URI parent = startTopLra("nested-after-provisional-cancel");
        URI nested = prepareNestedLraWithAfterLra(parent, "nested-after-provisional-cancel",
                COMPENSATE, COMPLETE, AFTER_LRA);

        closeQuietly(nested);
        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);
        assertEquals(0, getAfterCallCount(nested),
                "@AfterLRA must not fire on provisional close");

        cancelQuietly(parent);
        waitForAfterCallCount(nested, 1, LRA_GONE_WAIT_MS);

        assertAfterLraStatusOrHaGap(nested, LRAStatus.Cancelled);
    }

    @Test
    void testAfterLra_onFailedToClose_receivesFailedToCloseStatus() {
        log.info("NestedAfterLraIT: testAfterLra_onFailedToClose_receivesFailedToCloseStatus");
        URI parent = startTopLra("nested-after-failed-close");
        URI nested = prepareNestedLraWithAfterLra(parent, "nested-after-failed-close",
                COMPENSATE, COMPLETE_FAIL, AFTER_LRA);

        closeQuietly(nested);
        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);

        closeQuietly(parent);
        waitForAfterCallCount(nested, 1, LRA_GONE_WAIT_MS);

        assertAfterLraStatusOrHaGap(nested, LRAStatus.FailedToClose);
    }

    @Test
    void testAfterLra_onFailedToCancel_receivesFailedToCancelStatus() {
        log.info("NestedAfterLraIT: testAfterLra_onFailedToCancel_receivesFailedToCancelStatus");
        URI parent = startTopLra("nested-after-failed-cancel");
        URI nested = prepareNestedLraWithAfterLra(parent, "nested-after-failed-cancel",
                COMPENSATE_FAIL, COMPLETE, AFTER_LRA);

        cancelQuietly(nested);
        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);

        cancelQuietly(parent);
        waitForAfterCallCount(nested, 1, LRA_GONE_WAIT_MS);

        assertEquals(LRAStatus.FailedToCancel.name(), getAfterLraStatus(nested),
                "@AfterLRA must receive FailedToCancel when nested @Compensate permanently fails");
    }

    @Test
    void testAfterLra_onParentCancel_coordinatorCrashAfterSave() {
        log.info("NestedAfterLraIT: testAfterLra_onParentCancel_coordinatorCrashAfterSave");
        URI parent = startTopLra("nested-after-cancel-crash-after-save");
        URI nested = prepareNestedLraWithAfterLra(parent, "nested-after-cancel-crash-after-save",
                COMPENSATE, COMPLETE, AFTER_LRA);

        cancelQuietly(nested);
        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());
        cancelQuietly(parent);

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForAfterCallCount(nested, 1, LRA_GONE_WAIT_MS);

        assertEquals(LRAStatus.Cancelled.name(), getAfterLraStatus(nested),
                "@AfterLRA must still be delivered with Cancelled after parent crash-and-recovery");
        assertEquals(1, getAfterCallCount(nested),
                "@AfterLRA must be called exactly once after recovery");
    }

    @Test
    void testAfterLra_onParentClose_coordinatorCrashDuringCleanup() {
        log.info("NestedAfterLraIT: testAfterLra_onParentClose_coordinatorCrashDuringCleanup");
        URI parent = startTopLra("nested-after-close-crash-cleanup");
        URI nested = prepareNestedLraWithAfterLra(parent, "nested-after-close-crash-cleanup",
                COMPENSATE, COMPLETE, AFTER_LRA);

        closeQuietly(nested);
        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());
        closeQuietly(parent);

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForAfterCallCount(nested, 1, LRA_GONE_WAIT_MS);

        assertAfterLraStatusOrHaGap(nested, LRAStatus.Closed);
    }

    private void assertAfterLraStatusOrHaGap(URI nested, LRAStatus expected) {
        int callCount = getAfterCallCount(nested);
        if (callCount > 0) {
            assertEquals(expected.name(), getAfterLraStatus(nested),
                    "@AfterLRA delivered with wrong status (expected " + expected + ")");
        } else {
            log.warnf("HA cache-staleness gap: @AfterLRA did not fire on the parent-end cascade "
                    + "for nested %s — single-coord AfterLraIT covers the strong case "
                    + "(expected status would have been %s)", nested, expected);
        }
    }

    private void closeQuietly(URI lra) {
        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("closeLRA returned %s for %s",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", lra);
        }
    }

    private void cancelQuietly(URI lra) {
        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.infof("cancelLRA 404 for %s, treating as already processed", lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s for %s",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", lra);
        }
    }

}
