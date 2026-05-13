package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a participant which removed itself from an active transaction is not
 * called when it ends, even when a coordinator crash interrupts the leave or terminal flow.
 */
@QuarkusTest
class LeaveIT extends TestBase {

    @Override
    protected String participantPath() {
        return "leave-participant";
    }

    private static final Logger log = Logger.getLogger(LeaveIT.class);
    private static final long CRASH_RECOVERY_TIMEOUT_S = 15;

    @Test
    void testLeaveBeforeCancel() {
        log.info("LeaveIT: testLeaveBeforeCancel");
        URI lra = prepareLeaveLra("leave-cancel");

        callLeave(lra);

        assertDoesNotThrow(() -> lraClient.cancelLRA(lra));

        waitForNoActiveLra(lra, LRA_GONE_HAPPY_PATH_MS);

        assertEquals(0, getCallCount(lra),
                "@Compensate must not be called after the participant left the LRA");
    }

    @Test
    void testLeaveBeforeClose() {
        log.info("LeaveIT: testLeaveBeforeClose");
        URI lra = prepareLeaveLra("leave-close");

        callLeave(lra);

        assertDoesNotThrow(() -> lraClient.closeLRA(lra));

        waitForNoActiveLra(lra, LRA_GONE_HAPPY_PATH_MS);

        assertEquals(0, getCallCount(lra),
                "@Complete must not be called after the participant left the LRA");
    }

    /**
     * Crash hits before the cancel decision is persisted; proxy failover finishes the cancel
     * cleanly without calling the participant that already left.
     */
    @Test
    void testLeaveBeforeCancel_coordinatorCrashBeforeSave() {
        log.info("LeaveIT: testLeaveBeforeCancel_coordinatorCrashBeforeSave");
        URI lra = prepareLeaveLra("leave-cancel-crash-before-save");

        callLeave(lra);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_BEFORE_SAVE.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s — coordinator crashed, proxy fails over",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(0, getCallCount(lra),
                "Left participant must not receive @Compensate even after coordinator failover");
    }

    @Test
    void testLeaveBeforeCancel_coordinatorCrashAfterSave() {
        log.info("LeaveIT: testLeaveBeforeCancel_coordinatorCrashAfterSave");
        URI lra = prepareLeaveLra("leave-cancel-crash-after-save");

        callLeave(lra);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s after failover, treating as already processed",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(0, getCallCount(lra),
                "Left participant must not receive @Compensate after crash-and-recovery");
    }

    @Test
    void testLeaveBeforeClose_coordinatorCrashAfterSave() {
        log.info("LeaveIT: testLeaveBeforeClose_coordinatorCrashAfterSave");
        URI lra = prepareLeaveLra("leave-close-crash-after-save");

        callLeave(lra);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE.name());

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("closeLRA returned %s after failover, treating as already processed",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(0, getCallCount(lra),
                "Left participant must not receive @Complete after crash-and-recovery");
    }

    @Test
    void testLeave_coordinatorCrashBeforeSave_cancelDoesNotCallCompensate() {
        log.info("LeaveIT: testLeave_coordinatorCrashBeforeSave_cancelDoesNotCallCompensate");
        URI lra = prepareLeaveLra("leave-itself-crash-before-save-cancel");

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.LEAVE_BEFORE_SAVE.name());

        triggerLeaveViaRetry(lra);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException ignored) {
        }

        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(0, getCallCount(lra),
                "@Compensate must not fire — leave should have persisted via @Retry failover");
    }

    @Test
    void testLeave_coordinatorCrashAfterSave_cancelDoesNotCallCompensate() {
        log.info("LeaveIT: testLeave_coordinatorCrashAfterSave_cancelDoesNotCallCompensate");
        URI lra = prepareLeaveLra("leave-itself-crash-after-save-cancel");

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.LEAVE_AFTER_SAVE.name());

        triggerLeaveViaRetry(lra);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException ignored) {
        }

        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(0, getCallCount(lra),
                "@Compensate must not fire after leave persisted (LEAVE_AFTER_SAVE)");
    }

    @Test
    void testLeave_coordinatorCrashBeforeSave_closeDoesNotCallComplete() {
        log.info("LeaveIT: testLeave_coordinatorCrashBeforeSave_closeDoesNotCallComplete");
        URI lra = prepareLeaveLra("leave-itself-crash-before-save-close");

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.LEAVE_BEFORE_SAVE.name());

        triggerLeaveViaRetry(lra);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException ignored) {
        }

        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(0, getCallCount(lra),
                "@Complete must not fire — leave should have persisted via @Retry failover");
    }

    @Test
    void testLeave_coordinatorCrashAfterSave_closeDoesNotCallComplete() {
        log.info("LeaveIT: testLeave_coordinatorCrashAfterSave_closeDoesNotCallComplete");
        URI lra = prepareLeaveLra("leave-itself-crash-after-save-close");

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.LEAVE_AFTER_SAVE.name());

        triggerLeaveViaRetry(lra);

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException ignored) {
        }

        waitForNoActiveLra(lra, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(0, getCallCount(lra),
                "@Complete must not fire after leave persisted (LEAVE_AFTER_SAVE)");
    }

    private URI prepareLeaveLra(String scenario) {
        return prepareLra(participantClientId(scenario), COMPENSATE, COMPLETE);
    }

    private void triggerLeaveViaRetry(URI lra) {
        String compensatorLink = buildCompensatorLink(
                participantUri(COMPENSATE), participantUri(COMPLETE));
        try {
            lraClient.leaveLRA(lra, compensatorLink);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("leaveLRA returned %s after retry exhaustion",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }
    }

    /**
     * Calls the coordinator's remove endpoint directly to avoid a host mismatch between
     * the docker-internal enrollment URL and the localhost URL the JAX-RS filter would build.
     */
    private void callLeave(URI lraId) {
        String compensatorLink = buildCompensatorLink(
                participantUri(COMPENSATE), participantUri(COMPLETE));

        URI removeUri = URI.create(lraId.toASCIIString() + "/remove");

        Response r = client.target(removeUri)
                .request()
                .put(Entity.text(compensatorLink));
        int status = r.getStatus();
        r.close();
        log.infof("LEAVE call for lraId=%s returned HTTP %s", lraId, status);
        assertEquals(200, status, "Leave endpoint must return 200; got " + status);
    }
}
