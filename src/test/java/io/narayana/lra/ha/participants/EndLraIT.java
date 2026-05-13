package io.narayana.lra.ha.participants;

import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EndLraIT extends TestBase {

    private static final Logger log = Logger.getLogger(EndLraIT.class);
    private static final long LRA_GONE_FAST_MS = 5_000;

    @Test
    void testCancelLraBeforeSave() {
        log.info("Starting testCancelLraBeforeSave");
        URI lra = prepareLraWithParticipant("cancel-before");

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_BEFORE_SAVE);

        assertDoesNotThrow(() -> lraClient.cancelLRA(lra));

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.size();

        assertEquals(0, unique, "Expected no active LRAs after cancel but got ids=" + activeIds);
    }

    @Test
    void testCancelLraAfterSave() {
        log.info("Starting testCancelLraAfterSave");
        URI lra = prepareLraWithParticipant("cancel-after");

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.infof("cancelLRA returned 404 after failover, treating as already finished: %s", lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s after failover for %s, accepting for post-check",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", lra);
        }

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.size();

        assertEquals(0, unique, "Expected no active LRAs after cancel but got ids=" + activeIds);
    }

    @Test
    void testCloseLraBeforeSave() {
        log.info("Starting testCloseLraBeforeSave");
        URI lra = prepareLraWithParticipant("close-before");

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_BEFORE_SAVE);

        assertDoesNotThrow(() -> lraClient.closeLRA(lra));

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.size();

        assertEquals(0, unique, "Expected no active LRAs after close but got ids=" + activeIds);
    }

    @Test
    void testCloseLraAfterSave() {
        log.info("Starting testCloseLraAfterSave");
        URI lra = prepareLraWithParticipant("close-after");

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_SAVE);

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.infof("closeLRA returned 404 after failover, treating as already finished: %s", lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("closeLRA returned %s after failover for %s, accepting for post-check",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", lra);
        }

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.size();

        assertEquals(0, unique, "Expected no active LRAs after close but got ids=" + activeIds);
    }

    @Test
    void testCancelLraDuringCleanup() {
        log.info("Starting testCancelLraDuringCleanup");
        URI lra = prepareLraWithParticipant("cancel-during-cleanup");

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_DURING_CLEANUP);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.infof("cancelLRA returned 404 after failover, treating as already finished: %s", lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("cancelLRA returned %s during cleanup failover for %s, accepting for post-check",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown",
                    lra);
        }

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.size();

        assertEquals(0, unique, "Expected no active LRAs after cancel but got ids=" + activeIds);
    }

    @Test
    void testCancelLraAfterCleanup() {
        log.info("Starting testCancelLraAfterCleanup");
        URI lra = prepareLraWithParticipant("cancel-after-cleanup");

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_CLEANUP);

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.infof("cancelLRA returned 404 after failover, treating as already finished: %s", lra);
        }

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.size();

        assertEquals(0, unique, "Expected no active LRAs after cancel but got ids=" + activeIds);
    }

    @Test
    void testCloseLraDuringCleanup() {
        log.info("Starting testCloseLraDuringCleanup");
        URI lra = prepareLraWithParticipant("close-during-cleanup");

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_DURING_CLEANUP);

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.infof("closeLRA returned 404 after failover, treating as already finished: %s", lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("closeLRA returned %s during cleanup failover for %s, accepting for post-check",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown",
                    lra);
        }

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.size();

        assertEquals(0, unique, "Expected no active LRAs after close but got ids=" + activeIds);
    }

    @Test
    void testCloseLraAfterCleanup() {
        log.info("Starting testCloseLraAfterCleanup");
        URI lra = prepareLraWithParticipant("close-after-cleanup");

        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.END_AFTER_CLEANUP);

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.infof("closeLRA returned 404 after failover, treating as already finished: %s", lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.infof("closeLRA returned %s after failover for %s, accepting for post-check",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", lra);
        }

        waitForNoActiveLra(lra, LRA_GONE_FAST_MS);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.size();

        assertEquals(0, unique, "Expected no active LRAs after close but got ids=" + activeIds);
    }

    private URI prepareLraWithParticipant(String suffix) {
        injectResetAll();

        String clientId = "io.narayana.lra.ha.LRAParticipant#" + suffix + "-" + System.nanoTime();

        URI lra = lraClient.startLRA(
                null,
                clientId,
                30L,
                ChronoUnit.SECONDS,
                true);

        lrasToAfterFinish.add(lra);
        log.infof("Started LRA: %s", lra);

        URI compensateUri = participantUri("compensate");
        URI completeUri = participantUri("complete");

        URI recoveryUrl = lraClient.joinLRA(lra, 30L, compensateUri, completeUri,
                null, null, null, null, new StringBuilder());
        assertNotNull(recoveryUrl);
        log.infof("Participant enlisted, recoveryUrl=%s", recoveryUrl);

        return lra;
    }
}
