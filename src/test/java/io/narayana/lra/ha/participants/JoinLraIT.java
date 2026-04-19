package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@QuarkusTest
class JoinLraIT extends TestBase {

    private static final Logger log = LoggerFactory.getLogger(JoinLraIT.class);

    @TestHTTPResource("/")
    URI baseUri;

    @Test
    void testJoinLraDuplicates() {
        log.info("Starting testJoinLraIT after save");
        injectResetAll();
        String clientId = uniqueClientId("bookGame");
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

        URI injectedCoordinator = nextRoutedCoordinator();
        log.info("Injecting JOIN_AFTER_SAVE on coordinator {}", injectedCoordinator);
        enableFailurePoint(injectedCoordinator, InjectPoint.JOIN_AFTER_SAVE.name());
        log.info("Injected join hold, calling joinLRA again (same participant, will timeout+retry)");

        URI recoveryUrl = lraClient.joinLRA(lra, 30L, compensateUri, completeUri,
                null, null, null, null, new StringBuilder());
        log.info("joinLRA recoveryUrl: {}", recoveryUrl);
        assertNotNull(recoveryUrl);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.size();

        assertEquals(
                1,
                unique,
                "Expected exactly one unique active LRA but got ids=" + activeIds);

    }

    @Test
    void testJoinBeforeSaveCrashStillEnlistsOnce() {
        log.info("Starting testJoinLraIT before save");
        injectResetAll();
        String clientId = uniqueClientId("bookGame");
        URI lra = lraClient.startLRA(
                null,
                clientId,
                30L,
                ChronoUnit.SECONDS,
                true);

        log.info("Started LRA: {}", lra);

        URI compensateUri = participantUri("compensate");
        URI completeUri = participantUri("complete");

        URI injectedCoordinator = nextRoutedCoordinator();
        log.info("Injecting JOIN_BEFORE_SAVE on coordinator {}", injectedCoordinator);
        enableFailurePoint(injectedCoordinator, InjectPoint.JOIN_BEFORE_SAVE.name());

        URI recoveryUrl = lraClient.joinLRA(lra, 30L, compensateUri, completeUri,
                null, null, null, null, new StringBuilder());
        assertNotNull(recoveryUrl);
        log.info("RecoveryUrl after failover: {}", recoveryUrl);

        List<String> all = getActiveIds();
        log.info("Active ids across cluster (raw): {}", all);

        long unique = all.size();

        assertEquals(
                1,
                unique,
                "Expected exactly one unique active LRA across cluster but got ids=" + all);
        lrasToAfterFinish.add(lra);
    }

    private String uniqueClientId(String action) {
        return "io.naryana.lra.ha.LRAParticipant#" + action + "-" + System.nanoTime();
    }
}
