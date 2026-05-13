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
 * Verifies that a participant which removed itself from a nested transaction is not called
 * when the cascade ends, even when a coordinator crash interrupts the leave or terminal flow.
 */
@QuarkusTest
class NestedLeaveIT extends TestBase {

    @Override
    protected String participantPath() {
        return "nested-participant";
    }

    private static final Logger log = Logger.getLogger(NestedLeaveIT.class);
    private static final long CRASH_RECOVERY_TIMEOUT_S = 15;

    @Test
    void testLeaveNested_beforeCancel() {
        log.info("NestedLeaveIT: testLeaveNested_beforeCancel");
        URI parent = startTopLra("nested-leave-cancel");
        URI nested = prepareNestedLra(parent, "nested-leave-cancel", COMPENSATE, COMPLETE);

        callLeave(nested);

        assertDoesNotThrow(() -> lraClient.cancelLRA(nested));

        waitForNoActiveLra(nested, LRA_GONE_HAPPY_PATH_MS);

        assertEquals(0, getCallCount(nested),
                "@Compensate must not be called after the participant left the nested LRA");
    }

    @Test
    void testLeaveNested_beforeClose() {
        log.info("NestedLeaveIT: testLeaveNested_beforeClose");
        URI parent = startTopLra("nested-leave-close");
        URI nested = prepareNestedLra(parent, "nested-leave-close", COMPENSATE, COMPLETE);

        callLeave(nested);

        assertDoesNotThrow(() -> lraClient.closeLRA(nested));

        waitForNoActiveLra(nested, LRA_GONE_HAPPY_PATH_MS);

        assertEquals(0, getCallCount(nested),
                "@Complete must not be called after the participant left the nested LRA");
    }

    @Test
    void testLeaveNested_beforeCancel_coordinatorCrashBeforeSave() {
        log.info("NestedLeaveIT: testLeaveNested_beforeCancel_coordinatorCrashBeforeSave");
        URI parent = startTopLra("nested-leave-cancel-crash-before");
        URI nested = prepareNestedLra(parent, "nested-leave-cancel-crash-before", COMPENSATE, COMPLETE);

        callLeave(nested);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_BEFORE_SAVE);

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s — coordinator crashed",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(0, getCallCount(nested),
                "Left participant must not receive @Compensate even after coordinator failover");
    }

    @Test
    void testLeaveNested_beforeCancel_coordinatorCrashAfterSave() {
        log.info("NestedLeaveIT: testLeaveNested_beforeCancel_coordinatorCrashAfterSave");
        URI parent = startTopLra("nested-leave-cancel-crash-after");
        URI nested = prepareNestedLra(parent, "nested-leave-cancel-crash-after", COMPENSATE, COMPLETE);

        callLeave(nested);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE);

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.NotFoundException ignored) {
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s — coordinator crashed",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(0, getCallCount(nested),
                "Left participant must not receive @Compensate after crash-and-recovery");
    }

    @Test
    void testLeaveNested_beforeClose_coordinatorCrashAfterSave() {
        log.info("NestedLeaveIT: testLeaveNested_beforeClose_coordinatorCrashAfterSave");
        URI parent = startTopLra("nested-leave-close-crash-after");
        URI nested = prepareNestedLra(parent, "nested-leave-close-crash-after", COMPENSATE, COMPLETE);

        callLeave(nested);

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE);

        try {
            lraClient.closeLRA(nested);
        } catch (jakarta.ws.rs.NotFoundException ignored) {
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("closeLRA returned %s — coordinator crashed",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);
        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(0, getCallCount(nested),
                "Left participant must not receive @Complete after crash-and-recovery");
    }

    @Test
    void testLeaveNested_coordinatorCrashBeforeSave_cancelDoesNotCompensate() {
        log.info("NestedLeaveIT: testLeaveNested_coordinatorCrashBeforeSave_cancelDoesNotCompensate");
        URI parent = startTopLra("nested-leave-itself-crash-before-cancel");
        URI nested = prepareNestedLra(parent, "nested-leave-itself-crash-before-cancel",
                COMPENSATE, COMPLETE);
        String compensatorLink = buildCompensatorLink(
                participantUri(COMPENSATE), participantUri(COMPLETE));

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.LEAVE_BEFORE_SAVE);

        try {
            lraClient.leaveLRA(nested, compensatorLink);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("leaveLRA returned %s after retry exhaustion",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.NotFoundException ignored) {
        } catch (jakarta.ws.rs.WebApplicationException ignored) {
        }

        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(0, getCallCount(nested),
                "@Compensate must not fire — leave should have persisted via @Retry failover");
    }

    @Test
    void testLeaveNested_coordinatorCrashAfterSave_cancelDoesNotCompensate() {
        log.info("NestedLeaveIT: testLeaveNested_coordinatorCrashAfterSave_cancelDoesNotCompensate");
        URI parent = startTopLra("nested-leave-itself-crash-after-cancel");
        URI nested = prepareNestedLra(parent, "nested-leave-itself-crash-after-cancel",
                COMPENSATE, COMPLETE);
        String compensatorLink = buildCompensatorLink(
                participantUri(COMPENSATE), participantUri(COMPLETE));

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.LEAVE_AFTER_SAVE);

        try {
            lraClient.leaveLRA(nested, compensatorLink);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("leaveLRA returned %s after retry exhaustion",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);

        try {
            lraClient.cancelLRA(nested);
        } catch (jakarta.ws.rs.NotFoundException ignored) {
        } catch (jakarta.ws.rs.WebApplicationException ignored) {
        }

        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(0, getCallCount(nested),
                "@Compensate must not fire after leave persisted (LEAVE_AFTER_SAVE)");
    }

    @Test
    void testLeaveNested_coordinatorCrashBeforeSave_closeDoesNotComplete() {
        log.info("NestedLeaveIT: testLeaveNested_coordinatorCrashBeforeSave_closeDoesNotComplete");
        URI parent = startTopLra("nested-leave-itself-crash-before-close");
        URI nested = prepareNestedLra(parent, "nested-leave-itself-crash-before-close",
                COMPENSATE, COMPLETE);
        String compensatorLink = buildCompensatorLink(
                participantUri(COMPENSATE), participantUri(COMPLETE));

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.LEAVE_BEFORE_SAVE);

        try {
            lraClient.leaveLRA(nested, compensatorLink);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("leaveLRA returned %s after retry exhaustion",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);

        try {
            lraClient.closeLRA(nested);
        } catch (jakarta.ws.rs.NotFoundException ignored) {
        } catch (jakarta.ws.rs.WebApplicationException ignored) {
        }

        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(0, getCallCount(nested),
                "@Complete must not fire — leave should have persisted via @Retry failover");
    }

    @Test
    void testLeaveNested_coordinatorCrashAfterSave_closeDoesNotComplete() {
        log.info("NestedLeaveIT: testLeaveNested_coordinatorCrashAfterSave_closeDoesNotComplete");
        URI parent = startTopLra("nested-leave-itself-crash-after-close");
        URI nested = prepareNestedLra(parent, "nested-leave-itself-crash-after-close",
                COMPENSATE, COMPLETE);
        String compensatorLink = buildCompensatorLink(
                participantUri(COMPENSATE), participantUri(COMPLETE));

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.LEAVE_AFTER_SAVE);

        try {
            lraClient.leaveLRA(nested, compensatorLink);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("leaveLRA returned %s after retry exhaustion",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_TIMEOUT_S);

        try {
            lraClient.closeLRA(nested);
        } catch (jakarta.ws.rs.NotFoundException ignored) {
        } catch (jakarta.ws.rs.WebApplicationException ignored) {
        }

        waitForNoActiveLra(nested, LRA_GONE_AFTER_RECOVERY_MS);

        assertEquals(0, getCallCount(nested),
                "@Complete must not fire after leave persisted (LEAVE_AFTER_SAVE)");
    }

    private void callLeave(URI lraId) {
        String compensatorLink = buildCompensatorLink(
                participantUri(COMPENSATE), participantUri(COMPLETE));

        URI removeUri = jakarta.ws.rs.core.UriBuilder.fromUri(lraId).path("remove").build();

        Response r = client.target(removeUri)
                .request()
                .put(Entity.text(compensatorLink));
        int status = r.getStatus();
        r.close();
        log.infof("LEAVE call for nested lraId=%s → %s returned HTTP %s", lraId, removeUri, status);
        assertEquals(200, status, "Leave endpoint must return 200; got " + status);
    }
}
