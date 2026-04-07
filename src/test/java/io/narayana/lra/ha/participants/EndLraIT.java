package io.narayana.lra.ha.participants;

import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@QuarkusTest
class EndLraIT extends TestBase {

    private static final Logger log = LoggerFactory.getLogger(EndLraIT.class);

    @Test
    void testCancelLraBeforeSave() {
        log.info("Starting testCancelLraBeforeSave");
        URI lra = prepareLraWithParticipant("cancel-before");

        injectEnable(nextRoutedCoordinator(), InjectPoint.END_BEFORE_SAVE.name());

        assertDoesNotThrow(() -> lraClient.cancelLRA(lra));

        waitForNoActiveLra(lra, 5_000);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.stream().distinct().count();

        assertEquals(0, unique, "Expected no active LRAs after cancel but got ids=" + activeIds);
    }

    @Test
    void testCancelLraAfterSave() {
        log.info("Starting testCancelLraAfterSave");
        URI lra = prepareLraWithParticipant("cancel-after");

        injectEnable(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("cancelLRA returned 404 after failover, treating as already finished: {}", lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} after failover for {}, accepting for post-check",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", lra);
        }

        waitForNoActiveLra(lra, 5_000);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.stream().distinct().count();

        assertEquals(0, unique, "Expected no active LRAs after cancel but got ids=" + activeIds);
    }

    @Test
    void testCloseLraBeforeSave() {
        log.info("Starting testCloseLraBeforeSave");
        URI lra = prepareLraWithParticipant("close-before");

        injectEnable(nextRoutedCoordinator(), InjectPoint.END_BEFORE_SAVE.name());

        assertDoesNotThrow(() -> lraClient.closeLRA(lra));

        waitForNoActiveLra(lra, 5_000);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.stream().distinct().count();

        assertEquals(0, unique, "Expected no active LRAs after close but got ids=" + activeIds);
    }

    @Test
    void testCloseLraAfterSave() {
        log.info("Starting testCloseLraAfterSave");
        URI lra = prepareLraWithParticipant("close-after");

        injectEnable(nextRoutedCoordinator(), InjectPoint.END_AFTER_SAVE.name());

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("closeLRA returned 404 after failover, treating as already finished: {}", lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} after failover for {}, accepting for post-check",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", lra);
        }

        waitForNoActiveLra(lra, 5_000);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.stream().distinct().count();

        assertEquals(0, unique, "Expected no active LRAs after close but got ids=" + activeIds);
    }

    @Test
    void testCancelLraDuringCleanup() {
        log.info("Starting testCancelLraDuringCleanup");
        URI lra = prepareLraWithParticipant("cancel-during-cleanup");

        injectEnable(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("cancelLRA returned 404 after failover, treating as already finished: {}", lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("cancelLRA returned {} during cleanup failover for {}, accepting for post-check",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown",
                    lra);
        }

        waitForNoActiveLra(lra, 5_000);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.stream().distinct().count();

        assertEquals(0, unique, "Expected no active LRAs after cancel but got ids=" + activeIds);
    }

    @Test
    void testCancelLraAfterCleanup() {
        log.info("Starting testCancelLraAfterCleanup");
        URI lra = prepareLraWithParticipant("cancel-after-cleanup");

        injectEnable(nextRoutedCoordinator(), InjectPoint.END_AFTER_CLEANUP.name());

        try {
            lraClient.cancelLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("cancelLRA returned 404 after failover, treating as already finished: {}", lra);
        }

        waitForNoActiveLra(lra, 5_000);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.stream().distinct().count();

        assertEquals(0, unique, "Expected no active LRAs after cancel but got ids=" + activeIds);
    }

    @Test
    void testCloseLraDuringCleanup() {
        log.info("Starting testCloseLraDuringCleanup");
        URI lra = prepareLraWithParticipant("close-during-cleanup");

        injectEnable(nextRoutedCoordinator(), InjectPoint.END_DURING_CLEANUP.name());

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("closeLRA returned 404 after failover, treating as already finished: {}", lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} during cleanup failover for {}, accepting for post-check",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown",
                    lra);
        }

        waitForNoActiveLra(lra, 5_000);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.stream().distinct().count();

        assertEquals(0, unique, "Expected no active LRAs after close but got ids=" + activeIds);
    }

    @Test
    void testCloseLraAfterCleanup() {
        log.info("Starting testCloseLraAfterCleanup");
        URI lra = prepareLraWithParticipant("close-after-cleanup");

        injectEnable(nextRoutedCoordinator(), InjectPoint.END_AFTER_CLEANUP.name());

        try {
            lraClient.closeLRA(lra);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.info("closeLRA returned 404 after failover, treating as already finished: {}", lra);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            log.info("closeLRA returned {} after failover for {}, accepting for post-check",
                    e.getResponse() != null ? e.getResponse().getStatus() : "unknown", lra);
        }

        waitForNoActiveLra(lra, 5_000);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.stream().distinct().count();

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
        log.info("Started LRA: {}", lra);

        URI compensateUri = participantUri("compensate");
        URI completeUri = participantUri("complete");
        String compensatorLink = buildCompensatorLink(compensateUri, completeUri);

        URI recoveryUrl = lraClient.enlistCompensator(lra, 30L, compensatorLink, new StringBuilder());
        assertNotNull(recoveryUrl);
        log.info("Participant enlisted, recoveryUrl={}", recoveryUrl);

        return lra;
    }
}
