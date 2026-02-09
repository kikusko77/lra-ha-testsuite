package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.naryana.lra.ha.LRAParticipant;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
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
    void testFullLifecycle() {
        snapshotAllLrasAcrossCoordinators();
        holdAfterCurrentPush(coordinatorUris.get(0), 5000, "io.naryana.lra.ha.LRAParticipant#bookGame", 2);
        holdAfterCurrentPush(coordinatorUris.get(1), 5000, "io.naryana.lra.ha.LRAParticipant#bookGame", 2);
        holdAfterCurrentPush(coordinatorUris.get(2), 5000, "io.naryana.lra.ha.LRAParticipant#bookGame", 0);
        URI lra = invokeParticipant(baseUri, null, LRAParticipant.CREATE_OR_CONTINUE_LRA, 200);

        snapshotAllLrasAcrossCoordinators();

        log.info("Started: {}", lra);

        lrasToAfterFinish.add(lra);
        assertEquals(LRAStatus.Active, lraClient.getStatus(lra));
    }
}
