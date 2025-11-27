package io.narayana.lra.testcontainers;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.HashMap;
import java.util.Map;

public class LraCoordinatorTestResource implements QuarkusTestResourceLifecycleManager {

    private static CoordinatorClusterManager clusterManager;

    public static CoordinatorClusterManager getClusterManager() {
        return clusterManager;
    }

    @Override
    public Map<String, String> start() {
        clusterManager = new CoordinatorClusterManager();
        clusterManager.startCoordinators(1);

        String proxyUrl = clusterManager.getProxyUrl(); // e.g. http://localhost:8080/lra-coordinator

        Map<String, String> cfg = new HashMap<>();
        cfg.put("quarkus.lra.coordinator-url", proxyUrl);
        // Optionally also expose host:
        // cfg.put("quarkus.http.host", "0.0.0.0");
        return cfg;
    }

    @Override
    public void stop() {
        if (clusterManager != null) {
            clusterManager.getCoordinators().forEach(c -> {
                try {
                    c.stop();
                } catch (Exception ignored) {
                }
            });
        }
    }
}
