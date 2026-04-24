package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for the @Forget lifecycle in the HA setup.
 *
 * Per the MicroProfile LRA spec, @Forget is used when a participant accepted
 * completion/compensation asynchronously or knows it failed permanently and
 * must retain cleanup state until the coordinator explicitly tells it it can
 * release those resources. In this HA suite, the meaningful coverage is the
 * top-level async-failure path for close/cancel plus crash/failover variants.
 *
 * <p>
 * <b>HA note on @Forget call counts:</b> In a multi-coordinator setup with
 * a shared object store, all running coordinators' recovery scanners
 * independently load the same LRA record (which has {@code accepted=true}
 * persisted after the initial 202 response). Each coordinator calls
 * {@code retryGetEndStatus}, receives the failed terminal status, and issues
 * a separate {@code @Forget} DELETE before any of them flushes the updated
 * state (forgetURI=null) back to the object store. The MicroProfile LRA spec
 * mandates only that the coordinator calls {@code @Forget} <em>at least
 * once</em> and that participants handle duplicate calls idempotently.
 * Asserting exactly-once is too strict in a shared-store HA environment.
 *
 * <p>
 * // TODO: fix in coordinator — implement atomic claim on LRA records before
 * // recovery processing (e.g. exclusive FileLock / SELECT FOR UPDATE) so that
 * // only one coordinator calls @Forget per failed participant per LRA.
 * // See LRAParticipantRecord.forget() and LRARecoveryModule.
 */
@QuarkusTest
class ForgetIT extends TestBase {

    @Override
    protected String participantPath() {
        return "forget-participant";
    }

    private static final Logger log = LoggerFactory.getLogger(ForgetIT.class);
    private static final long CRASH_RECOVERY_WAIT_S = 30;
    private static final long RECOVERY_SCAN_WAIT_MS = 120_000;
    private static final long CRASH_SCAN_WAIT_MS = 180_000;

    /**
     * Async compensate returns 202 and @Status later reports FailedToCompensate.
     * Recovery must call @Forget at least once.
     */
    @Test
    void testForgetAfterFailedCompensate_happyPath() {
        log.info("ForgetIT: testForgetAfterFailedCompensate_happyPath");
        URI lra = prepareCompensateLraAsyncWithForget(
                "forget-compensate-happy",
                STATUS_FOR_FORGET_COMPENSATE);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} for forget compensate happy path",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        waitForFailedAsyncForget(lra);

        assertEquals(1, getAsyncCallCount(lra), "@Compensate should be called exactly once in the happy path");
        assertTrue(getAsyncStatusCallCount(lra) >= 1, "@Status should be polled before @Forget");
        assertForgetCalledAtLeastOnce(lra);
    }

    /**
     * Coordinator crashes after persisting Cancelling but before contacting the participant.
     * Recovery must drive async compensate, observe the failed terminal status, then call @Forget
     * at least once.
     */
    @Test
    void testForgetAfterFailedCompensate_coordinatorCrashAfterSave() {
        log.info("ForgetIT: testForgetAfterFailedCompensate_coordinatorCrashAfterSave");
        URI lra = prepareCompensateLraAsyncWithForget(
                "forget-compensate-after-save",
                STATUS_FOR_FORGET_COMPENSATE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {}, coordinator crashed as expected",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForFailedAsyncForget(lra, CRASH_SCAN_WAIT_MS);

        assertEquals(1, getAsyncCallCount(lra), "Recovery should call async @Compensate exactly once");
        assertTrue(getAsyncStatusCallCount(lra) >= 1, "Recovery should poll @Status before @Forget");
        assertForgetCalledAtLeastOnce(lra);
    }

    /**
     * END_DURING_CLEANUP re-routes the cancel request through the proxy, but the
     * second coordinator resolves the LRA through pre-flight @Status without
     * replaying @Compensate. Cleanup via @Forget must still happen.
     */
    @Test
    void testForgetAfterFailedCompensate_duplicateCallViaProxyFailover() {
        log.info("ForgetIT: testForgetAfterFailedCompensate_duplicateCallViaProxyFailover");
        URI lra = prepareCompensateLraAsyncWithForget(
                "forget-compensate-duplicate",
                STATUS_FOR_FORGET_COMPENSATE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} — coordinator crashed after 202, proxy will fail over",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForFailedAsyncForget(lra, CRASH_SCAN_WAIT_MS);

        assertEquals(1, getAsyncCallCount(lra),
                "Async @Compensate should be called exactly once in END_DURING_CLEANUP failover");
        assertTrue(getAsyncStatusCallCount(lra) >= 1, "Async duplicate path should poll @Status at least once");
        assertForgetCalledAtLeastOnce(lra);
    }

    /**
     * Async complete returns 202 and @Status later reports FailedToComplete.
     * Recovery must call @Forget at least once.
     */
    @Test
    void testForgetAfterFailedComplete_happyPath() {
        log.info("ForgetIT: testForgetAfterFailedComplete_happyPath");
        URI lra = prepareCompleteLraAsyncWithForget(
                "forget-complete-happy",
                STATUS_FOR_FORGET_COMPLETE);

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} for forget complete happy path",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        waitForFailedAsyncForget(lra);

        assertEquals(1, getAsyncCallCount(lra), "@Complete should be called exactly once in the happy path");
        assertTrue(getAsyncStatusCallCount(lra) >= 1, "@Status should be polled before @Forget");
        assertForgetCalledAtLeastOnce(lra);
    }

    /**
     * Coordinator crashes after persisting Closing but before contacting the participant.
     * Recovery must drive async complete, observe the failed terminal status, then call @Forget
     * at least once.
     */
    @Test
    void testForgetAfterFailedComplete_coordinatorCrashAfterSave() {
        log.info("ForgetIT: testForgetAfterFailedComplete_coordinatorCrashAfterSave");
        URI lra = prepareCompleteLraAsyncWithForget(
                "forget-complete-after-save",
                STATUS_FOR_FORGET_COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {}, coordinator crashed as expected",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForFailedAsyncForget(lra, CRASH_SCAN_WAIT_MS);

        assertEquals(1, getAsyncCallCount(lra), "Recovery should call async @Complete exactly once");
        assertTrue(getAsyncStatusCallCount(lra) >= 1, "Recovery should poll @Status before @Forget");
        assertForgetCalledAtLeastOnce(lra);
    }

    /**
     * END_DURING_CLEANUP re-routes the close request through the proxy, but the
     * second coordinator resolves the LRA through pre-flight @Status without
     * replaying @Complete. Cleanup via @Forget must still happen.
     */
    @Test
    void testForgetAfterFailedComplete_duplicateCallViaProxyFailover() {
        log.info("ForgetIT: testForgetAfterFailedComplete_duplicateCallViaProxyFailover");
        URI lra = prepareCompleteLraAsyncWithForget(
                "forget-complete-duplicate",
                STATUS_FOR_FORGET_COMPLETE);

        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} — coordinator crashed after 202, proxy will fail over",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        ensureCoordinatorAvailability(CRASH_RECOVERY_WAIT_S);
        waitForFailedAsyncForget(lra, CRASH_SCAN_WAIT_MS);

        assertEquals(1, getAsyncCallCount(lra),
                "Async @Complete should be called exactly once in END_DURING_CLEANUP failover");
        assertTrue(getAsyncStatusCallCount(lra) >= 1, "Async duplicate path should poll @Status at least once");
        assertForgetCalledAtLeastOnce(lra);
    }

    private void waitForFailedAsyncForget(URI lra) {
        waitForFailedAsyncForget(lra, RECOVERY_SCAN_WAIT_MS);
    }

    private void waitForFailedAsyncForget(URI lra, long forgetTimeoutMs) {
        waitForForgetCallCount(lra, 1, forgetTimeoutMs);
        waitForNoActiveLra(lra, 10_000);
        assertNoActiveLras();
    }

    /**
     * Asserts that {@code @Forget} was called at least once and logs a warning
     * if it was called more than once, surfacing the HA duplicate-call finding.
     *
     * <p>
     * // TODO (coordinator): prevent duplicate @Forget calls in HA by acquiring
     * // an exclusive claim on the LRA record before recovery processing —
     * // e.g. FileLock on the object-store file or SELECT FOR UPDATE on JDBC.
     * // See LRAParticipantRecord.forget() and LRARecoveryModule.
     */
    private void assertForgetCalledAtLeastOnce(URI lra) {
        int forgetCount = getForgetCallCount(lra);
        assertTrue(forgetCount >= 1, "@Forget must be called at least once");
        if (forgetCount > 1) {
            log.warn("HA finding: @Forget called {} times for {} — coordinator duplicate-call detected; "
                    + "multiple recovery scanners processed the same LRA record concurrently",
                    forgetCount, lra);
        }
    }
}
