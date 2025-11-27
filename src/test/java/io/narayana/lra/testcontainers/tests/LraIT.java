package io.narayana.lra.testcontainers.tests;

import io.narayana.lra.testcontainers.LraCoordinatorTestResource;
import io.naryana.lra.testcontainers.tests.LRAParticipant;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@QuarkusTest
@QuarkusTestResource(LraCoordinatorTestResource.class)
public class LraIT extends TestBase {

    private static final Logger log = LoggerFactory.getLogger(LraIT.class);

    // Let Quarkus inject the correct test URL (http://localhost:8081)
    @TestHTTPResource
    URI participantBaseUri;

    @Test
    public void testChainOfInvocations() {
        log.debug("Starting testChainOfInvocations");

        URI lra1 = invokeParticipant(participantBaseUri, null, LRAParticipant.CREATE_OR_CONTINUE_LRA, 200);
        lrasToAfterFinish.add(lra1);

        Assertions.assertEquals(
                LRAStatus.Active,
                lraClient.getStatus(lra1),
                "LRA should still be active. The identifier of the LRA was " + lra1);

        invokeParticipant(participantBaseUri, lra1, LRAParticipant.END_EXISTING_LRA, 200);

        Assertions.assertNull(
                lraClient.getCurrent(),
                "The narayana implementation still thinks there is an active LRA associated with the current thread");
    }
}