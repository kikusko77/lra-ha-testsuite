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
        URI lra = invokeParticipant(
                baseUri,
                null,
                LRAParticipant.CREATE_OR_CONTINUE_LRA,
                200);

        lrasToAfterFinish.add(lra);
        // ACTIVE
        assertEquals(LRAStatus.Active, lraClient.getStatus(lra));

        // END
        invokeParticipant(
                baseUri,
                lra,
                LRAParticipant.END_EXISTING_LRA,
                200);

        // CLOSED
        assertEquals(LRAStatus.Closed, lraClient.getStatus(lra));
    }
}
