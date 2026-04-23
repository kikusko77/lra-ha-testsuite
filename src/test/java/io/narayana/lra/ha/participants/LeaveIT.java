package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for the {@code @Leave} lifecycle in the HA setup.
 *
 * <p>
 * The core property under test: once a participant successfully calls the
 * {@code @Leave} endpoint while an LRA context is active, the coordinator removes
 * it from that LRA. Neither {@code @Compensate} nor {@code @Complete} will be
 * delivered to it when the LRA ends.
 *
 * <p>
 * Test structure:
 * <ul>
 * <li>Happy-path leave-before-cancel / leave-before-close — callCount must stay at 0.</li>
 * <li>Sanity (no-leave) baseline for cancel / close — callCount must reach 1.</li>
 * <li>HA crash scenarios: coordinator crashes after the participant already left,
 * so recovery must still finish the LRA cleanly without calling the
 * left-behind participant.</li>
 * </ul>
 */
@QuarkusTest
class LeaveIT extends TestBase {

    @Override
    protected String participantPath() {
        return "leave-participant";
    }

    private static final Logger log = LoggerFactory.getLogger(LeaveIT.class);

    private static final long LRA_GONE_FAST_MS = 10_000;
    private static final long LRA_GONE_WAIT_MS = 30_000;
    private static final long CRASH_RECOVERY_WAIT_S = 15;

    /**
     * Participant leaves the LRA, then the LRA is cancelled.
     * {@code @Compensate} must never be called — callCount stays at 0.
     */
    @Test
    void testLeaveBeforeCancel() {
        log.info("LeaveIT: testLeaveBeforeCancel");
        URI lra = prepareLeaveLra("leave-cancel");

        callLeave(lra);

        assertDoesNotThrow(() -> lraClient.cancelLRA(lra));

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);

        assertEquals(0, getIdempotentCallCount(lra),
                "@Compensate must not be called after the participant left the LRA");
    }

    /**
     * Participant leaves the LRA, then the LRA is closed.
     * {@code @Complete} must never be called — callCount stays at 0.
     */
    @Test
    void testLeaveBeforeClose() {
        log.info("LeaveIT: testLeaveBeforeClose");
        URI lra = prepareLeaveLra("leave-close");

        callLeave(lra);

        assertDoesNotThrow(() -> lraClient.closeLRA(lra));

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);

        assertEquals(0, getIdempotentCallCount(lra),
                "@Complete must not be called after the participant left the LRA");
    }

    /**
     * Sanity: participant does NOT leave; LRA is cancelled.
     * {@code @Compensate} must be called exactly once.
     */
    @Test
    void testNoLeave_cancelCallsCompensate() {
        log.info("LeaveIT: testNoLeave_cancelCallsCompensate");
        URI lra = prepareLeaveLra("no-leave-cancel");

        assertDoesNotThrow(() -> lraClient.cancelLRA(lra));

        waitForIdempotentCallCount(lra, 1, LRA_GONE_FAST_MS);

        assertEquals(1, getIdempotentCallCount(lra),
                "@Compensate must be called exactly once when the participant has not left");
    }

    /**
     * Sanity: participant does NOT leave; LRA is closed.
     * {@code @Complete} must be called exactly once.
     */
    @Test
    void testNoLeave_closeCallsComplete() {
        log.info("LeaveIT: testNoLeave_closeCallsComplete");
        URI lra = prepareLeaveLra("no-leave-close");

        assertDoesNotThrow(() -> lraClient.closeLRA(lra));

        waitForIdempotentCallCount(lra, 1, LRA_GONE_FAST_MS);

        assertEquals(1, getIdempotentCallCount(lra),
                "@Complete must be called exactly once when the participant has not left");
    }

    /**
     * Participant leaves, then the coordinator crashes before persisting the
     * Cancelling state (END_BEFORE_SAVE). The HA proxy fails over to
     * coordinator-2, which sees the LRA as still Active and cancels it cleanly.
     * Because the participant already left, callCount must remain 0.
     */
    @Test
    void testLeaveBeforeCancel_coordinatorCrashBeforeSave() {
        log.info("LeaveIT: testLeaveBeforeCancel_coordinatorCrashBeforeSave");
        URI lra = prepareLeaveLra("leave-cancel-crash-before-save");

        callLeave(lra);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_BEFORE_SAVE.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} — coordinator crashed, proxy fails over",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);

        assertEquals(0, getIdempotentCallCount(lra),
                "Left participant must not receive @Compensate even after coordinator failover");
    }

    /**
     * Participant leaves, then the coordinator crashes after persisting
     * Cancelling (END_AFTER_SAVE). Recovery drives the cancel to completion;
     * because the participant left before the crash, callCount must remain 0.
     */
    @Test
    void testLeaveBeforeCancel_coordinatorCrashAfterSave() {
        log.info("LeaveIT: testLeaveBeforeCancel_coordinatorCrashAfterSave");
        URI lra = prepareLeaveLra("leave-cancel-crash-after-save");

        callLeave(lra);

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

        assertEquals(0, getIdempotentCallCount(lra),
                "Left participant must not receive @Compensate after crash-and-recovery");
    }

    /**
     * Participant leaves, then the coordinator crashes after persisting
     * Closing (END_AFTER_SAVE on close). Recovery drives the close to completion;
     * because the participant left before the crash, callCount must remain 0.
     */
    @Test
    void testLeaveBeforeClose_coordinatorCrashAfterSave() {
        log.info("LeaveIT: testLeaveBeforeClose_coordinatorCrashAfterSave");
        URI lra = prepareLeaveLra("leave-close-crash-after-save");

        callLeave(lra);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("closeLRA returned 404, treating as already processed");
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} — coordinator crashed as expected",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForNoActiveLra(lra, LRA_GONE_WAIT_MS);

        assertEquals(0, getIdempotentCallCount(lra),
                "Left participant must not receive @Complete after crash-and-recovery");
    }

    /**
     * Starts an LRA and enrolls the leave-participant as a compensator.
     * Uses the shared {@code callCounts} map tracked by {@link io.naryana.lra.ha.LeaveParticipant}
     * for both compensate and complete, so {@link #getIdempotentCallCount} gives the
     * total number of callbacks delivered to this participant.
     */
    private URI prepareLeaveLra(String scenario) {
        return prepareLra(participantClientId(scenario), COMPENSATE, COMPLETE);
    }

    /**
     * Removes this participant from the LRA by calling {@code PUT {lraId}/remove} directly,
     * bypassing the {@code @Leave} / {@code ServerLRAFilter} path to avoid a host mismatch
     * between the enrollment URL ({@code host.docker.internal:9081}) and the filter-built URL
     * ({@code localhost:9081}). The body must be a Link header string so the coordinator
     * matches the participant by compensator URL rather than recovery URL.
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
        log.info("LEAVE call for lraId={} returned HTTP {}", lraId, status);
        assertEquals(200, status, "Leave endpoint must return 200; got " + status);
    }
}
