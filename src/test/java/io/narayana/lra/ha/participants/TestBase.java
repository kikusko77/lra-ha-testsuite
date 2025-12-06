package io.narayana.lra.ha.participants;

import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;

import io.narayana.lra.LRAConstants;
import io.narayana.lra.LRAData;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.ha.utils.CoordinatorClusterManager;
import io.narayana.lra.ha.utils.LraCoordinatorTestResource;
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
import java.net.URISyntaxException;
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

    protected CoordinatorClusterManager clusterManager;
    protected NarayanaLRAClient lraClient;
    protected String coordinatorUrl;
    public static final String ROOT_PATH = "/lra-unaware";
    public static final String RESOURCE_PATH = "/lra-work";

    protected Client client;
    protected List<URI> lrasToAfterFinish;

    @BeforeAll
    void beforeAll() {
        clusterManager = LraCoordinatorTestResource.getClusterManager();
        String proxyUrl = clusterManager.getProxyUrl();

        lraClient = new NarayanaLRAClient(URI.create(proxyUrl));
        coordinatorUrl = lraClient.getCoordinatorUrl();
    }

    @AfterAll
    void afterAll() {
        if (lraClient != null) {
            lraClient.close();
        }

        clusterManager = null;
    }

    @BeforeEach
    void beforeEach() {
        client = ClientBuilder.newClient();
        lrasToAfterFinish = new ArrayList<>();
    }

    @AfterEach
    void afterEach() {
        List<URI> lraURIList = lraClient.getAllLRAs().stream()
                .map(LRAData::getLraId)
                .toList();

        for (URI lraToFinish : lrasToAfterFinish) {
            if (lraURIList.contains(lraToFinish)) {
                lraClient.cancelLRA(lraToFinish);
            }
        }

        if (client != null) {
            client.close();
        }
    }

    protected JsonArray getAllRecords(URI lra) {
        String url = LRAConstants.getLRACoordinatorUrl(lra) + "/";

        try (Response response = client.target(url).path("").request().get()) {
            Assertions.assertTrue(
                    response.hasEntity(),
                    "Missing response body when querying for all LRAs");

            String allLRAs = response.readEntity(String.class);
            JsonReader jsonReader = Json.createReader(new StringReader(allLRAs));
            return jsonReader.readArray();
        }
    }

    protected URI invokeLraUnaware(URI baseUri, int expectedStatus) {
        try (Response response = client.target(baseUri)
                .path(ROOT_PATH)
                .path(RESOURCE_PATH)
                .request()
                .get()) {

            Assertions.assertTrue(
                    response.hasEntity(),
                    "Expecting a non empty body in response from " +
                            ROOT_PATH + "/" +
                            RESOURCE_PATH);

            String entity = response.readEntity(String.class);

            Assertions.assertEquals(
                    expectedStatus,
                    response.getStatus(),
                    "response from " +
                            ROOT_PATH + "/" +
                            RESOURCE_PATH +
                            " was " + entity);

            try {
                return new URI(entity);
            } catch (URISyntaxException e) {
                throw new IllegalStateException("response cannot be converted to URI: " + e.getMessage(), e);
            }
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

            Assertions.assertTrue(
                    response.hasEntity(),
                    "Expected response to contain LRA id or error message");

            String responseMessage = response.readEntity(String.class);

            Assertions.assertEquals(
                    expectedStatus,
                    response.getStatus(),
                    responseMessage);

            return URI.create(responseMessage);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }
}
