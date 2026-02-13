package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@QuarkusTest
class LraIT extends TestBase {

    private static final Logger log = LoggerFactory.getLogger(LraIT.class);

    @TestHTTPResource("/")
    URI baseUri;

    @Test
    void testLraDuplicates() {
        snapshotAllLrasAcrossCoordinators();
        holdAfterCurrentPush(coordinatorUris.get(0), 5000, "io.naryana.lra.ha.LRAParticipant#bookGame", 2);
        holdAfterCurrentPush(coordinatorUris.get(1), 5000, "io.naryana.lra.ha.LRAParticipant#bookGame", 1);
        URI lra = lraClient.startLRAWithRetryFlag(
                null,
                "io.naryana.lra.ha.LRAParticipant#bookGame",
                30L,
                ChronoUnit.SECONDS,
                true);
        snapshotAllLrasAcrossCoordinators();

        log.info("Started: {}", lra);

        lrasToAfterFinish.add(lra);
        assertEquals(LRAStatus.Active, lraClient.getStatus(lra));
    }
}
