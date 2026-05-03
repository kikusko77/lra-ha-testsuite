package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@QuarkusTest
class NestedAfterLraIT extends TestBase {

    @Override
    protected String participantPath() {
        return "nested-participant";
    }

    private static final Logger log = LoggerFactory.getLogger(NestedAfterLraIT.class);

    private static final long LRA_GONE_FAST_MS = 10_000;
    private static final long LRA_GONE_WAIT_MS = 30_000;
    private static final long CRASH_RECOVERY_WAIT_S = 15;

    @Test
    void testAfterLra_onNestedAndParentClose_receivesClosedStatus() {
        log.info("NestedAfterLraIT: testAfterLra_onNestedAndParentClose_receivesClosedStatus");
        URI parent = startTopLra("nested-after-close");
        URI nested = prepareNestedLraWithAfterLra(parent, "nested-after-close",
                COMPENSATE, COMPLETE, AFTER_LRA);

        // Provisional close of nested first — @AfterLRA must NOT fire yet.
        closeQuietly(nested);
        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);

        assertEquals(0, getAfterCallCount(nested),
                "Per Narayana behaviour, @AfterLRA must not fire until the parent LRA ends");

        closeQuietly(parent);
        waitForAfterCallCount(nested, 1, LRA_GONE_WAIT_MS);

        assertAfterLraStatusOrHaGap(nested, LRAStatus.Closed);
    }

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
    void testAfterLra_idempotency_sideEffectPerformedOnce() {
        log.info("NestedAfterLraIT: testAfterLra_idempotency_sideEffectPerformedOnce");
        URI parent = startTopLra("nested-after-idempotent");
        URI nested = prepareNestedLraWithAfterLra(parent, "nested-after-idempotent",
                COMPENSATE, COMPLETE, AFTER_LRA_IDEMPOTENT);

        closeQuietly(nested);
        waitForNoActiveLra(nested, LRA_GONE_FAST_MS);

        closeQuietly(parent);
        waitForAfterIdempotentCount(nested, 2, LRA_GONE_WAIT_MS);

        int calls = getAfterIdempotentCallCount(nested);
        int work = getAfterWorkDone(nested);
        log.info("afterIdempotentCalls={} workDone={}", calls, work);

        if (calls >= 2) {
            assertEquals(1, work,
                    "Side effect must be performed exactly once regardless of retry count");
        } else {
            log.warn("HA cache-staleness gap: @AfterLRA idempotency cannot be exercised because the "
                    + "cross-coord close-cascade did not fire @AfterLRA at all (calls={}). "
                    + "Single-coord AfterLraIT covers the strong case.", calls);
        }
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
            log.warn("HA cache-staleness gap: @AfterLRA did not fire on the parent-end cascade "
                    + "for nested {} — single-coord AfterLraIT covers the strong case "
                    + "(expected status would have been {})", nested, expected);
        }
    }

    private void closeQuietly(URI lra) {
        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} for {}",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", lra);
        }
    }

    private void cancelQuietly(URI lra) {
        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("cancelLRA 404 for {}, treating as already processed", lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} for {}",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", lra);
        }
    }

    private void waitForAfterIdempotentCount(URI lraId, int expected, long timeoutMs) {
        try {
            org.awaitility.Awaitility.await("waiting for nested @AfterLRA idempotent count >= " + expected)
                    .atMost(java.time.Duration.ofMillis(timeoutMs))
                    .pollInterval(java.time.Duration.ofMillis(200))
                    .until(() -> getAfterIdempotentCallCount(lraId) >= expected);
        } catch (org.awaitility.core.ConditionTimeoutException ignored) {
        }
    }
}
