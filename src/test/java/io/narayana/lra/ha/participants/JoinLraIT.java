package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JoinLraIT extends TestBase {

    private static final Logger log = Logger.getLogger(JoinLraIT.class);

    @TestHTTPResource("/")
    URI baseUri;

    @Test
    void testJoinLraDuplicates() {
        log.info("Starting testJoinLraIT after save");
        injectResetAll();
        String clientId = uniqueClientId("join-duplicates");
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

        URI coordinatorWithFailure = nextRoutedCoordinator();
        log.infof("Injecting JOIN_AFTER_SAVE on coordinator %s", coordinatorWithFailure);
        enableFailurePoint(coordinatorWithFailure, FailurePoint.JOIN_AFTER_SAVE);
        log.info("Injected join hold, calling joinLRA again (same participant, will timeout+retry)");

        URI recoveryUrl = lraClient.joinLRA(lra, 30L, compensateUri, completeUri,
                null, null, null, null, new StringBuilder());
        log.infof("joinLRA recoveryUrl: %s", recoveryUrl);
        assertNotNull(recoveryUrl);

        List<String> activeIds = getActiveLras();
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
        String clientId = uniqueClientId("join-before-save");
        URI lra = lraClient.startLRA(
                null,
                clientId,
                30L,
                ChronoUnit.SECONDS,
                true);

        log.infof("Started LRA: %s", lra);

        URI compensateUri = participantUri("compensate");
        URI completeUri = participantUri("complete");

        URI coordinatorWithFailure = nextRoutedCoordinator();
        log.infof("Injecting JOIN_BEFORE_SAVE on coordinator %s", coordinatorWithFailure);
        enableFailurePoint(coordinatorWithFailure, FailurePoint.JOIN_BEFORE_SAVE);

        URI recoveryUrl = lraClient.joinLRA(lra, 30L, compensateUri, completeUri,
                null, null, null, null, new StringBuilder());
        assertNotNull(recoveryUrl);
        log.infof("RecoveryUrl after failover: %s", recoveryUrl);

        List<String> all = getActiveLras();
        log.infof("Active ids across cluster (raw): %s", all);

        long unique = all.size();

        assertEquals(
                1,
                unique,
                "Expected exactly one unique active LRA across cluster but got ids=" + all);
        lrasToAfterFinish.add(lra);
    }
}
