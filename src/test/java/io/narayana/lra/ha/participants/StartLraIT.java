package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@QuarkusTest
class StartLraIT extends TestBase {

    private static final Logger log = LoggerFactory.getLogger(StartLraIT.class);

    @Test
    void testStartLraDuplicates() {
        log.info("StartLraIT: testStartLraDuplicates");
        injectResetAll();
        injectEnable(nextRoutedCoordinator(), InjectPoint.START.name());
        URI lra = lraClient.startLRA(
                null,
                "io.naryana.lra.ha.LRAParticipant#bookGame",
                30L,
                ChronoUnit.SECONDS,
                true);

        lrasToAfterFinish.add(lra);
        List<String> activeIds = getActiveIds();
        long unique = activeIds.stream().distinct().count();

        assertEquals(
                1,
                unique,
                "Expected exactly one unique active LRA but got ids=" + activeIds);
    }
}
