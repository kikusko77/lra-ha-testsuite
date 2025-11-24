package io.narayana.lra.testcontainers.tests;

import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.testcontainers.CoordinatorClusterManager;
import io.naryana.lra.testcontainers.tests.LRAParticipant;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LraIT extends TestBase {

    private static final Logger log = LoggerFactory.getLogger(LraIT.class);
    private static CoordinatorClusterManager cluster;
    private static NarayanaLRAClient client;
    private static URI baseUri;

    private URI participantBaseUri;

    @BeforeAll
    void startParticipantApp() {
        cluster = new CoordinatorClusterManager();
        cluster.startCoordinators(1);
        String proxyUrl = cluster.getProxyUrl();
        client = new NarayanaLRAClient(URI.create(proxyUrl));
        participantBaseUri = URI.create("http://localhost:9080/");
    }

    @Test
    public void testChainOfInvocations() {
        log.debug("Starting testChainOfInvocations");

        URI lra1 = invokeParticipant(participantBaseUri, null, LRAParticipant.CREATE_OR_CONTINUE_LRA, 200);
        lrasToAfterFinish.add(lra1);

        Assertions.assertEquals(
                LRAStatus.Active,
                client.getStatus(lra1),
                "LRA should still be active. The identifier of the LRA was " + lra1);

        invokeParticipant(participantBaseUri, lra1, LRAParticipant.END_EXISTING_LRA, 200);

        Assertions.assertNull(
                client.getCurrent(),
                "The narayana implementation still thinks there is an active LRA associated with the current thread");
    }
}
