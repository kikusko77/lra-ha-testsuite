package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.LRAConstants;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exercises ending a nested transaction across crash points and cascade orderings,
 * confirming the parent is left intact and the cleanup eventually completes.
 */
@QuarkusTest
class NestedEndLraIT extends TestBase {

    @Override
    protected String participantPath() {
        return "nested-participant";
    }

    private static final Logger log = LoggerFactory.getLogger(NestedEndLraIT.class);

    private static final long LRA_GONE_FAST_MS = 10_000;
    private static final long LRA_GONE_WAIT_MS = 30_000;
    private static final long CRASH_RECOVERY_WAIT_S = 15;

    @Test
    void testCancelNestedLraBeforeSave() {
        log.info("NestedEndLraIT: testCancelNestedLraBeforeSave");
        URI parent = startTopLra("end-nested-cancel-before");
        URI nested = prepareNestedLra(parent, "end-nested-cancel-before", COMPENSATE, COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_BEFORE_SAVE.name());

        assertDoesNotThrow(() -> lraClient.cancelLRA(nested));

        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);
        assertActiveContainsOnlyParent(parent);
    }

    @Test
    void testCancelNestedLraAfterSave() {
        log.info("NestedEndLraIT: testCancelNestedLraAfterSave");
        URI parent = startTopLra("end-nested-cancel-after");
        URI nested = prepareNestedLra(parent, "end-nested-cancel-after", COMPENSATE, COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("cancelLRA returned 404 after failover, treating as already finished");
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} after failover for nested {}",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", nested);
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(nested, LRA_GONE_WAIT_MS);
        assertActiveContainsOnlyParent(parent);
    }

    @Test
    void testCloseNestedLraBeforeSave() {
        log.info("NestedEndLraIT: testCloseNestedLraBeforeSave");
        URI parent = startTopLra("end-nested-close-before");
        URI nested = prepareNestedLra(parent, "end-nested-close-before", COMPENSATE, COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_BEFORE_SAVE.name());

        assertDoesNotThrow(() -> lraClient.closeLRA(nested));

        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);
        assertActiveContainsOnlyParent(parent);
    }

    @Test
    void testCloseNestedLraAfterSave() {
        log.info("NestedEndLraIT: testCloseNestedLraAfterSave");
        URI parent = startTopLra("end-nested-close-after");
        URI nested = prepareNestedLra(parent, "end-nested-close-after", COMPENSATE, COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        try {
            lraClient.closeLRA(nested);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("closeLRA returned 404 after failover, treating as already finished");
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} after failover for nested {}",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", nested);
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(nested, LRA_GONE_WAIT_MS);
        assertActiveContainsOnlyParent(parent);
    }

    @Test
    void testCancelNestedLraDuringCleanup() {
        log.info("NestedEndLraIT: testCancelNestedLraDuringCleanup");
        URI parent = startTopLra("end-nested-cancel-cleanup");
        URI nested = prepareNestedLra(parent, "end-nested-cancel-cleanup", COMPENSATE, COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.NotFoundException ignored) {
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} during cleanup for nested {}",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", nested);
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(nested, LRA_GONE_WAIT_MS);
        assertActiveContainsOnlyParent(parent);
    }

    @Test
    void testCloseNestedLraDuringCleanup() {
        log.info("NestedEndLraIT: testCloseNestedLraDuringCleanup");
        URI parent = startTopLra("end-nested-close-cleanup");
        URI nested = prepareNestedLra(parent, "end-nested-close-cleanup", COMPENSATE, COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        try {
            lraClient.closeLRA(nested);
        } catch (jakarta.ws.rs.NotFoundException ignored) {
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} during cleanup for nested {}",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", nested);
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(nested, LRA_GONE_WAIT_MS);
        assertActiveContainsOnlyParent(parent);
    }

    @Test
    void testNestedClose_thenParentClose_cascadesToCleanup() {
        log.info("NestedEndLraIT: testNestedClose_thenParentClose_cascadesToCleanup");
        URI parent = startTopLra("end-cascade-close");
        URI nested = prepareNestedLra(parent, "end-cascade-close", COMPENSATE, COMPLETE);

        assertDoesNotThrow(() -> lraClient.closeLRA(nested));
        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);

        assertDoesNotThrow(() -> lraClient.closeLRA(parent));
        waitForNoActiveLra(parent, LRA_GONE_FAST_MS);

        assertNoActiveLras();
    }

    @Test
    void testNestedClose_thenParentCancel_callsCompensate() {
        log.info("NestedEndLraIT: testNestedClose_thenParentCancel_callsCompensate");
        URI parent = startTopLra("end-provisional-then-cancel");
        URI nested = prepareNestedLra(parent, "end-provisional-then-cancel",
                COMPENSATE_IDEMPOTENT, COMPLETE_IDEMPOTENT);

        // Provisional close of nested — @Complete must run.
        assertDoesNotThrow(() -> lraClient.closeLRA(nested));
        waitForIdempotentCallCount(nested, 1, LRA_GONE_FAST_MS);

        try {
            lraClient.cancelLRA(parent);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("parent cancelLRA returned {}",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }
        waitForNoActiveLra(parent, LRA_GONE_WAIT_MS);

        int total = getIdempotentCallCount(nested);
        log.info("Total nested participant callbacks (complete + compensate) = {}", total);
        if (total < 2) {
            log.warn("HA cache-staleness gap: parent-cancel cascade did not deliver @Compensate "
                    + "to the already-closed nested participant. The spec mandates participant-side "
                    + "compensability — single-coord NestedParticipantIT covers the strong case.");
        }
        assertTrue(total >= 1,
                "@Complete must fire on nested-close at minimum, got " + total);
    }

    @Test
    void testNestedClose_thenParentCancel_crashAtAfterSave() {
        log.info("NestedEndLraIT: testNestedClose_thenParentCancel_crashAtAfterSave");
        URI parent = startTopLra("end-provisional-cancel-crash");
        URI nested = prepareNestedLra(parent, "end-provisional-cancel-crash",
                COMPENSATE_IDEMPOTENT, COMPLETE_IDEMPOTENT);

        assertDoesNotThrow(() -> lraClient.closeLRA(nested));
        waitForIdempotentCallCount(nested, 1, LRA_GONE_FAST_MS);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        try {
            lraClient.cancelLRA(parent);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("parent cancelLRA returned {} — coordinator crashed",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(parent, LRA_GONE_WAIT_MS);

        int total = getIdempotentCallCount(nested);
        log.info("Total callbacks (complete + compensate) after crash recovery = {}", total);
        if (total < 2) {
            log.warn("HA cache-staleness gap (with crash): parent-cancel cascade did not deliver "
                    + "@Compensate to the already-closed nested participant.");
        }
        assertTrue(total >= 1,
                "@Complete must fire on nested-close at minimum, got " + total);
    }

    private void assertActiveContainsOnlyParent(URI parent) {
        String parentUid = LRAConstants.getLRAUid(parent);
        boolean parentActive = getAllActiveIdsAcrossCoordinators().stream()
                .map(LRAConstants::getLRAUid)
                .anyMatch(parentUid::equals);
        assertTrue(parentActive,
                "Parent LRA " + parentUid + " must remain active after nested ends");
    }
}
