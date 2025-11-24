package io.narayana.lra.testcontainers.tests;

import static org.junit.jupiter.api.Assertions.*;

import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.testcontainers.CoordinatorClusterManager;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CoordinatorClusterIntegrationTestIT {

    private CoordinatorClusterManager cluster;
    private NarayanaLRAClient client;

    @BeforeAll
    void setUp() {
        cluster = new CoordinatorClusterManager();
        cluster.startCoordinators(1);
        String proxyUrl = cluster.getProxyUrl();
        client = new NarayanaLRAClient(URI.create(proxyUrl));
    }

    @Test
    public void testCoordinatorLifecycle() throws Exception {
        System.out.println("Shared object store = " + cluster.getSharedObjectStorePath());

        URI lraId = client.startLRA("test-lra");
        System.out.println("Started LRA = " + lraId);

        // DEBUG: Check what files are in the object store
        Path objectStore = Path.of(cluster.getSharedObjectStorePath());
        try (var stream = Files.walk(objectStore)) {
            stream.forEach(p -> System.out.println("  Object store file: " + p));
        }

        // Wait a bit for persistence
        Thread.sleep(500);

        // 2️⃣ Check ACTIVE
        try {
            LRAStatus status = client.getStatus(lraId);
            System.out.println("Status = " + status);
            assertEquals(LRAStatus.Active, status);
        } catch (Exception e) {
            System.err.println("FAILED to get status for: " + lraId);
            System.err.println("Error: " + e.getMessage());

            // Check object store again
            try (var stream = Files.walk(objectStore)) {
                stream.forEach(p -> System.out.println("  Object store (after error): " + p));
            }
            throw e;
        }

        client.closeLRA(lraId);
    }
}
