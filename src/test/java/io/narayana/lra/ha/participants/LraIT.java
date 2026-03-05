package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@QuarkusTest
class LraIT extends TestBase {

    private static final Logger log = LoggerFactory.getLogger(LraIT.class);

    @TestHTTPResource("/")
    URI baseUri;

    @Test
    void testStartLraDuplicates() {
        injectResetAll();
        injectEnable(firstReachableCoordinator(), InjectPoint.START.name());
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

    @Test
    void testJoinLraDuplicates() {
        injectResetAll();
        URI lra = lraClient.startLRA(
                null,
                "io.naryana.lra.ha.LRAParticipant#bookGame",
                30L,
                ChronoUnit.SECONDS,
                true);

        lrasToAfterFinish.add(lra);
        log.info("Started LRA: {}", lra);

        URI compensateUri = UriBuilder.fromUri(baseUri).path("lra-participant").path("compensate").build();
        URI completeUri = UriBuilder.fromUri(baseUri).path("lra-participant").path("complete").build();
        String compensatorLink = buildCompensatorLink(compensateUri, completeUri);

        injectEnable(firstReachableCoordinator(), InjectPoint.JOIN_AFTER_SAVE.name());
        log.info("Injected join hold, calling enlistCompensator again (same participant, will timeout+retry)");

        URI recoveryUrl = lraClient.enlistCompensator(lra, 30L, compensatorLink, new StringBuilder());
        log.info("EnlistCompensator recoveryUrl: {}", recoveryUrl);
        assertNotNull(recoveryUrl);

        List<String> activeIds = getActiveIds();
        long unique = activeIds.stream().distinct().count();

        assertEquals(
                1,
                unique,
                "Expected exactly one unique active LRA but got ids=" + activeIds);

    }

    private String buildCompensatorLink(URI compensate, URI complete) {
        return "<" + compensate.toASCIIString() + ">; rel=\"compensate\"; type=\"text/plain\""
                + ",<" + complete.toASCIIString() + ">; rel=\"complete\"; type=\"text/plain\"";
    }

    @Test
    void testJoinBeforeSaveCrashStillEnlistsOnce() {
        injectResetAll();
        URI lra = lraClient.startLRA(
                null,
                "io.naryana.lra.ha.LRAParticipant#bookGame",
                30L,
                ChronoUnit.SECONDS,
                true);

        lrasToAfterFinish.add(lra);
        log.info("Started LRA: {}", lra);

        URI compensateUri = UriBuilder.fromUri(baseUri).path("lra-participant").path("compensate").build();
        URI completeUri = UriBuilder.fromUri(baseUri).path("lra-participant").path("complete").build();
        String compensatorLink = buildCompensatorLink(compensateUri, completeUri);

        injectEnable(firstReachableCoordinator(), InjectPoint.JOIN_BEFORE_SAVE.name());

        URI recoveryUrl = lraClient.enlistCompensator(lra, 30L, compensatorLink, new StringBuilder());
        assertNotNull(recoveryUrl);
        log.info("RecoveryUrl after failover: {}", recoveryUrl);

        List<String> all = getActiveIds();
        log.info("Active ids across cluster (raw): {}", all);

        long unique = all.stream().distinct().count();

        assertEquals(
                1,
                unique,
                "Expected exactly one unique active LRA across cluster but got ids=" + all);
    }
}
