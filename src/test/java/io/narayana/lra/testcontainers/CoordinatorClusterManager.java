package io.narayana.lra.testcontainers;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.Network;

public class CoordinatorClusterManager {

    private final Network network = Network.newNetwork();
    private final List<LraCoordinatorContainer> coordinators = new ArrayList<>();
    private ProxyContainer proxy;
    private Path sharedObjectStore;

    public void startCoordinators(int count) {
        try {
            sharedObjectStore = Files.createTempDirectory("lra-objectstore-");
            System.out.println("✓ Shared object store created at: " + sharedObjectStore);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start registry HTTP server", e);
        }

        for (int i = 1; i <= count; i++) {
            startCoordinator("coord-" + i);
        }

        proxy = new ProxyContainer(network);
        proxy.start();
    }

    private void startCoordinator(String id) {
        LraCoordinatorContainer c = new LraCoordinatorContainer(id, sharedObjectStore.toString())
                .withNetwork(network)
                .withNetworkAliases(id);

        c.start();
        coordinators.add(c);
    }

    public void stopCoordinator(String id) {
        LraCoordinatorContainer toStop = coordinators.stream()
                .filter(c -> id.equals(c.getId()))
                .findFirst()
                .orElse(null);

        if (toStop == null) {
            System.out.println("No coordinator found with id: " + id);
            return;
        }

        System.out.println("Stopping coordinator " + id + " (containerId=" + toStop.getContainerId() + ")");
        toStop.stop();
        coordinators.remove(toStop);
    }

    public String getProxyUrl() {
        return proxy.getUrl() + "/lra-coordinator";
    }

    public URI getSharedObjectStorePath() {
        return sharedObjectStore.toUri();
    }

    public List<LraCoordinatorContainer> getCoordinators() {
        return coordinators;
    }
}
