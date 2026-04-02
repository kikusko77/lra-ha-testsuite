package io.narayana.lra.ha.proxy;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.Closeable;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quarkus test resource that starts the Vert.x-based coordinator proxy before
 * tests run and stops it when they finish.
 *
 * <p>
 * The proxy listens on port {@value #PROXY_PORT}, which matches the
 * {@code lra.coordinator.url} property, so the LRA client inside Quarkus
 * automatically talks to the proxy.
 *
 * <p>
 * The real coordinator addresses are defined in {@link #BACKENDS}. Tests that
 * need them directly (e.g. for fault-injection) should call {@link #getBackends()}.
 */
public class CoordinatorProxyResource implements QuarkusTestResourceLifecycleManager {

    private static final Logger LOG = LoggerFactory.getLogger(CoordinatorProxyResource.class);

    /** Port the proxy listens on — must match {@code lra.coordinator.url} in application.properties. */
    private static final int PROXY_PORT = 8080;

    /** Real coordinator backend addresses (matches the docker-compose setup). */
    private static final List<URI> BACKENDS = List.of(
            URI.create("http://localhost:8081/lra-coordinator"),
            URI.create("http://localhost:8082/lra-coordinator"),
            URI.create("http://localhost:8083/lra-coordinator"),
            URI.create("http://localhost:8084/lra-coordinator"));

    private Closeable proxy;

    @Override
    public Map<String, String> start() {
        try {
            var vertxProxy = new CoordinatorProxyVertx(PROXY_PORT, BACKENDS);
            vertxProxy.start();
            proxy = vertxProxy;
        } catch (Exception e) {
            throw new RuntimeException("Failed to start CoordinatorProxyVertx on port " + PROXY_PORT, e);
        }

        LOG.info("CoordinatorProxyVertx started on :{} → {}", PROXY_PORT, BACKENDS);
        return Map.of();
    }

    @Override
    public void stop() {
        if (proxy != null) {
            try {
                proxy.close();
            } catch (Exception e) {
                throw new RuntimeException("Failed to stop coordinator proxy", e);
            }
            proxy = null;
        }
    }

    /**
     * Returns the real coordinator backend URIs.
     * Call this in tests instead of reading any config property.
     */
    public static List<URI> getBackends() {
        return BACKENDS;
    }
}
