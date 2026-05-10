package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StartLraIT extends TestBase {

    private static final Logger log = Logger.getLogger(StartLraIT.class);

    @Test
    void testStartLraDuplicates() {
        log.info("StartLraIT: testStartLraDuplicates");
        injectResetAll();
        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.START.name());
        URI lra = lraClient.startLRA(
                null,
                "io.naryana.lra.ha.LRAParticipant#bookGame",
                30L,
                ChronoUnit.SECONDS,
                true);

        lrasToAfterFinish.add(lra);
        List<String> activeIds = getActiveIds();
        long unique = activeIds.size();

        assertEquals(
                1,
                unique,
                "Expected exactly one unique active LRA but got ids=" + activeIds);
    }
}
