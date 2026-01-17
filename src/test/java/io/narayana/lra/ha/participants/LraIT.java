package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.naryana.lra.ha.LRAParticipant;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LraIT extends TestBase {

    private static final Logger log = LoggerFactory.getLogger(LraIT.class);

    private static URI participantBaseUri;

    @BeforeAll
    static void setupUrls() {
        // For local dev: port-forward participant svc to localhost:8081
        //   kubectl port-forward svc/lra-participant 8081:8081
        String participantUrl = System.getProperty(
                "participant.baseUrl",
                System.getenv().getOrDefault("PARTICIPANT_BASE_URL", "http://localhost:8081"));
        participantBaseUri = URI.create(participantUrl);
    }

    @Test
    void testFullLifecycle() throws Exception {
        URI lra = invokeParticipant(
                participantBaseUri,
                null,
                LRAParticipant.CREATE_OR_CONTINUE_LRA,
                200);

        lrasToAfterFinish.add(lra);
        URI lraForClient = normalizeLraUriForLocal(lra);

        // ACTIVE
        assertEquals(LRAStatus.Active, getStatusEventually(lraForClient));

        // END
        invokeParticipant(
                participantBaseUri,
                lra,
                LRAParticipant.END_EXISTING_LRA,
                200);

        // CLOSED
        assertEquals(LRAStatus.Closed, getStatusEventually(lraForClient));
    }

    protected LRAStatus getStatusEventually(URI lraForClient) throws InterruptedException {
        Exception last = null;

        for (int i = 0; i < 30; i++) { // ~6s total
            try {
                return lraClient.getStatus(lraForClient);
            } catch (Exception e) {
                last = e;
                log.warn("getStatus failed attempt {} for {}: {}", i + 1, lraForClient, e.toString());
                Thread.sleep(200);
            }
        }
        throw new AssertionError("LRA status not available. Last error: " + (last == null ? "none" : last), last);
    }

    private URI normalizeLraUriForLocal(URI lraFromCluster) {
        return URI.create(
                lraFromCluster.toString()
                        .replace("http://lra-coordinator:8090", "http://localhost:8090"));
    }

}
