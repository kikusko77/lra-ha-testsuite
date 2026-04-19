package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for the {@code @AfterLRA} annotation per the MicroProfile LRA spec.
 *
 * <p>
 * Key spec properties exercised:
 * <ol>
 * <li>The coordinator calls {@code @AfterLRA} after the LRA reaches a
 * terminal state, passing the final {@link LRAStatus}.</li>
 * <li>All four terminal states are covered: {@code Closed}, {@code Cancelled},
 * {@code FailedToClose}, {@code FailedToCancel}.</li>
 * <li>The coordinator <em>must</em> retry {@code @AfterLRA} if it returns an
 * unexpected HTTP status — so implementations must be idempotent.</li>
 * <li>HA crash scenarios: coordinator crashes before or after {@code @AfterLRA}
 * is dispatched; recovery must complete the notification.</li>
 * </ol>
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
    private static final long CRASH_RECOVERY_WAIT_S = 120;

    /**
     * Close the LRA → {@code @AfterLRA} must be called with {@code Closed}.
     */
    @Test
    void testAfterLra_onClose_receivesClosedStatus() {
        log.info("AfterLraIT: testAfterLra_onClose_receivesClosedStatus");
        URI lra = prepareLraWithAfterLra(
                participantClientId("after-close"),
                COMPENSATE, COMPLETE, AFTER_LRA);

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {}", e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);
        waitForAfterCallCount(lra, 1, LRA_GONE_FAST_MS);

        assertEquals(LRAStatus.Closed.name(), getAfterLraStatus(lra),
                "@AfterLRA must receive Closed when the LRA is closed");
        assertEquals(1, getAfterCallCount(lra),
                "@AfterLRA must be called exactly once in the happy path");
    }

    /**
     * Cancel the LRA → {@code @AfterLRA} must be called with {@code Cancelled}.
     */
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

    /**
     * {@code @Complete} returns 409 (FailedToComplete) → LRA moves to
     * {@code FailedToClose} → {@code @AfterLRA} must receive that status.
     */
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

    /**
     * {@code @Compensate} returns 409 (FailedToCompensate) → LRA moves to
     * {@code FailedToCancel} → {@code @AfterLRA} must receive that status.
     */
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

    /**
     * The {@code after-idempotent} endpoint returns 500 on the first call, forcing
     * a coordinator retry. The idempotent guard in the participant ensures the
     * side effect (work done) is performed exactly once regardless of how many
     * times the endpoint is called.
     *
     * <p>
     * Per spec: "If the method annotated with @AfterLRA returns an unexpected HTTP
     * status or never reaches the caller then the implementation MUST invoke the same
     * method again."
     */
    @Test
    void testAfterLra_idempotency_sideEffectPerformedOnce() {
        log.info("AfterLraIT: testAfterLra_idempotency_sideEffectPerformedOnce");
        URI lra = prepareLraWithAfterLra(
                participantClientId("after-idempotent"),
                COMPENSATE, COMPLETE, AFTER_LRA_IDEMPOTENT);

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {}", e.getResponse() != null ? e.getResponse().getStatus() : "unknown");
        }

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);
        // Wait for at least 2 calls: the initial 500 and the retry
        waitForAfterIdempotentCount(lra, 2, LRA_GONE_WAIT_MS);

        int calls = getAfterIdempotentCallCount(lra);
        int work = getAfterWorkDone(lra);

        log.info("afterIdempotentCalls={} workDone={}", calls, work);

        assertTrue(calls >= 2,
                "Coordinator must retry @AfterLRA after receiving 500; got " + calls + " calls");
        assertEquals(1, work,
                "Side effect must be performed exactly once regardless of retry count");
    }

    // -------------------------------------------------------------------------
    // HA crash scenarios
    // -------------------------------------------------------------------------

    /**
     * Coordinator crashes after persisting the Cancelling state (END_AFTER_SAVE)
     * but before dispatching participant callbacks and {@code @AfterLRA}.
     * Recovery must complete the cancel, call {@code @Compensate}, and then
     * deliver the {@code @AfterLRA} notification with {@code Cancelled}.
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
     * Coordinator crashes at END_DURING_CLEANUP — after participant callbacks were
     * dispatched but before the final cleanup, which includes the {@code @AfterLRA}
     * notification. Recovery must deliver the {@code @AfterLRA} notification.
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

    private void waitForAfterIdempotentCount(URI lraId, int expected, long timeoutMs) {
        try {
            org.awaitility.Awaitility.await("waiting for @AfterLRA idempotent count >= " + expected)
                    .atMost(java.time.Duration.ofMillis(timeoutMs))
                    .pollInterval(java.time.Duration.ofMillis(200))
                    .until(() -> getAfterIdempotentCallCount(lraId) >= expected);
        } catch (org.awaitility.core.ConditionTimeoutException ignored) {
        }
    }
}
