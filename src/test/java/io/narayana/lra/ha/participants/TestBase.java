package io.narayana.lra.ha.participants;

import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;

import io.narayana.lra.LRAConstants;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.naryana.lra.ha.LRAParticipant;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonReader;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class TestBase {

    protected static NarayanaLRAClient lraClient;
    protected static String coordinatorUrl;

    protected Client client;
    protected List<URI> lrasToAfterFinish;

    @BeforeAll
    void beforeAll() {
        // IMPORTANT: point client to a reachable coordinator.
        // This should match one of your docker-exposed coordinator URLs.
        String coordinatorBaseUrl = System.getProperty(
                "coordinator.baseUrl",
                System.getenv().getOrDefault("COORDINATOR_BASE_URL", "http://localhost:8081/lra-coordinator"));

        lraClient = new NarayanaLRAClient(URI.create(coordinatorBaseUrl));
    }

    @AfterAll
    void afterAll() {
        if (lraClient != null)
            lraClient.close();
    }

    @BeforeEach
    void beforeEach() {
        client = ClientBuilder.newClient();
        lrasToAfterFinish = new ArrayList<>();
    }

    @AfterEach
    void afterEach() {
        // Just try to cancel what we created, and ignore failures.
        for (URI lraToFinish : lrasToAfterFinish) {
            try {
                lraClient.cancelLRA(lraToFinish);
            } catch (Exception ignored) {
                // ignore: it may already be closed/unknown, or coordinator unreachable at shutdown
            }
        }

        if (client != null) {
            client.close();
        }
    }

    protected JsonArray getAllRecords(URI lra) {
        String url = LRAConstants.getLRACoordinatorUrl(lra) + "/";

        try (Response response = client.target(url).path("").request().get()) {
            Assertions.assertTrue(response.hasEntity(), "Missing response body when querying for all LRAs");
            String allLRAs = response.readEntity(String.class);
            JsonReader jsonReader = Json.createReader(new StringReader(allLRAs));
            return jsonReader.readArray();
        }
    }

    protected URI invokeParticipant(URI baseUri, URI lraId, String resourcePath, int expectedStatus) {
        Response response = null;
        try {
            Invocation.Builder builder = client.target(
                    UriBuilder.fromUri(baseUri)
                            .path(LRAParticipant.RESOURCE_PATH)
                            .path(resourcePath)
                            .build())
                    .request();

            if (lraId != null) {
                builder.header(LRA_HTTP_CONTEXT_HEADER, lraId.toASCIIString());
            }

            response = builder.get();

            Assertions.assertTrue(response.hasEntity(), "Expected response to contain LRA id or error message");

            String responseMessage = response.readEntity(String.class);

            Assertions.assertEquals(expectedStatus, response.getStatus(), responseMessage);

            return URI.create(responseMessage);
        } finally {
            if (response != null)
                response.close();
        }
    }
}
