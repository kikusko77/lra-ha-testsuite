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
    private static final long CRASH_RECOVERY_TIMEOUT_S = 15;

    @Test
    void testAfterLra_onNestedAndParentCancel_receivesCancelledStatus() {
        log.info("NestedAfterLraIT: testAfterLra_onNestedAndParentCancel_receivesCancelledStatus");
        URI parent = startTopLra("nested-after-cancel");
        URI nested = prepareNestedLraWithAfter(parent, "nested-after-cancel",
                COMPENSATE, COMPLETE);

        cancel(nested);
        waitForNoActiveLra(nested, LRA_GONE_HAPPY_PATH_MS);

        cancel(parent);
        waitForAfterCallCount(nested, 1, LRA_GONE_AFTER_RECOVERY_MS);

        assertAfterLraOutcome(nested, LRAStatus.Cancelled, 1);
    }

    @Test
    void testAfterLra_onProvisionalCloseThenParentCancel_receivesCancelledStatus() {
        log.info("NestedAfterLraIT: testAfterLra_onProvisionalCloseThenParentCancel_receivesCancelledStatus");
        URI parent = startTopLra("nested-after-provisional-cancel");
        URI nested = prepareNestedLraWithAfter(parent, "nested-after-provisional-cancel",
                COMPENSATE, COMPLETE);

        close(nested);
        waitForNoActiveLra(nested, LRA_GONE_HAPPY_PATH_MS);
        assertEquals(0, getAfterCallCount(nested),
                "@AfterLRA must not fire on provisional close");

        cancel(parent);
        waitForAfterCallCount(nested, 1, LRA_GONE_AFTER_RECOVERY_MS);

        assertAfterLraOutcome(nested, LRAStatus.Cancelled, 1);
    }

    @Test
    void testAfterLra_onFailedToClose_receivesFailedToCloseStatus() {
        log.info("NestedAfterLraIT: testAfterLra_onFailedToClose_receivesFailedToCloseStatus");
        URI parent = startTopLra("nested-after-failed-close");
        URI nested = prepareNestedLraWithAfter(parent, "nested-after-failed-close",
                COMPENSATE, COMPLETE_FAIL);

        close(nested);
        waitForNoActiveLra(nested, LRA_GONE_HAPPY_PATH_MS);

        close(parent);
        waitForAfterCallCount(nested, 1, LRA_GONE_AFTER_RECOVERY_MS);

        assertAfterLraOutcome(nested, LRAStatus.FailedToClose, 1);
    }

    @Test
    void testAfterLra_onFailedToCancel_receivesFailedToCancelStatus() {
        log.info("NestedAfterLraIT: testAfterLra_onFailedToCancel_receivesFailedToCancelStatus");
        URI parent = startTopLra("nested-after-failed-cancel");
        URI nested = prepareNestedLraWithAfter(parent, "nested-after-failed-cancel",
                COMPENSATE_FAIL, COMPLETE);

        cancel(nested);
        waitForNoActiveLra(nested, LRA_GONE_HAPPY_PATH_MS);

        cancel(parent);
        waitForAfterCallCount(nested, 1, LRA_GONE_AFTER_RECOVERY_MS);

        assertAfterLraOutcome(nested, LRAStatus.FailedToCancel, 1);
    }

    @Test
    void testAfterLra_onParentCancel_coordinatorCrashAfterSave() {
        log.info("NestedAfterLraIT: testAfterLra_onParentCancel_coordinatorCrashAfterSave");
        URI parent = startTopLra("nested-after-cancel-crash-after-save");
        URI nested = prepareNestedLraWithAfter(parent, "nested-after-cancel-crash-after-save",
                COMPENSATE, COMPLETE);

        cancel(nested);
        waitForNoActiveLra(nested, LRA_GONE_HAPPY_PATH_MS);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE);
        cancel(parent);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForAfterCallCount(nested, 1, LRA_GONE_AFTER_RECOVERY_MS);

        assertAfterLraOutcome(nested, LRAStatus.Cancelled, 1);
    }

    @Test
    void testAfterLra_onParentClose_coordinatorCrashDuringCleanup() {
        log.info("NestedAfterLraIT: testAfterLra_onParentClose_coordinatorCrashDuringCleanup");
        URI parent = startTopLra("nested-after-close-crash-cleanup");
        URI nested = prepareNestedLraWithAfter(parent, "nested-after-close-crash-cleanup",
                COMPENSATE, COMPLETE);

        close(nested);
        waitForNoActiveLra(nested, LRA_GONE_HAPPY_PATH_MS);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_DURING_CLEANUP);
        close(parent);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForAfterCallCount(nested, 1, LRA_GONE_AFTER_RECOVERY_MS);

        assertAfterLraOutcome(nested, LRAStatus.Closed, 1);
    }

    private void assertAfterLraOutcome(URI nested, LRAStatus expectedStatus, int expectedCallCount) {
        assertCountStays("@AfterLRA call count for " + nested,
                expectedCallCount, 2_000, () -> getAfterCallCount(nested));
        assertEquals(expectedStatus.name(), getAfterLraStatus(nested),
                "@AfterLRA must receive " + expectedStatus + ", got " + getAfterLraStatus(nested));
    }

}
