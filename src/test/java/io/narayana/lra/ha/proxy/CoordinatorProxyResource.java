package io.narayana.lra.ha.proxy;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quarkus test resource that starts the Vert.x-based coordinator proxy before
 * tests run and stops it when they finish.
 *
 * <p>
 * The proxy listens on the fixed local port {@code 8080}, matching the checked-in
 * {@code lra.coordinator.url} test configuration.
 *
 * <p>
 * The real coordinator addresses are defined in {@link #BACKENDS}. Tests that
 * need them directly (e.g. for fault-injection) should call {@link #getBackends()}.
 */
public class CoordinatorProxyResource implements QuarkusTestResourceLifecycleManager {

    private static final Logger LOG = LoggerFactory.getLogger(CoordinatorProxyResource.class);
    private static final Duration BACKEND_CHECK_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration BACKEND_BOOT_TIMEOUT = Duration.ofMinutes(2);
    private static final String COORDINATOR_URL_KEY = "lra.coordinator.url";
    private static final int PROXY_PORT = 8080;
    private static final String PROXY_COORDINATOR_URL = "http://localhost:8080/lra-coordinator";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(BACKEND_CHECK_TIMEOUT)
            .build();

    private static volatile CoordinatorProxyVertx currentProxy;
    private static final List<URI> BACKENDS = List.of(
            URI.create("http://127.0.0.1:8081/lra-coordinator"),
            URI.create("http://127.0.0.1:8082/lra-coordinator"),
            URI.create("http://127.0.0.1:8083/lra-coordinator"),
            URI.create("http://127.0.0.1:8084/lra-coordinator"));

    private Closeable proxy;
    private String previousCoordinatorUrl;

    @Override
    public Map<String, String> start() {
        waitForAnyBackend();

        try {
            var vertxProxy = new CoordinatorProxyVertx(PROXY_PORT, BACKENDS);
            vertxProxy.start();
            proxy = vertxProxy;
            currentProxy = vertxProxy;
            vertxProxy.resetRoutingOrder(readyBackendIndexes());

            previousCoordinatorUrl = System.getProperty(COORDINATOR_URL_KEY);
            System.setProperty(COORDINATOR_URL_KEY, PROXY_COORDINATOR_URL);
            LOG.info("CoordinatorProxyVertx started on {} → {}", vertxProxy.proxyCoordinatorUri(), BACKENDS);
            return Map.of(COORDINATOR_URL_KEY, PROXY_COORDINATOR_URL);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start CoordinatorProxyVertx on port " + PROXY_PORT, e);
        }
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
            currentProxy = null;
        }

        if (previousCoordinatorUrl == null) {
            System.clearProperty(COORDINATOR_URL_KEY);
        } else {
            System.setProperty(COORDINATOR_URL_KEY, previousCoordinatorUrl);
        }
        previousCoordinatorUrl = null;
    }

    public static List<URI> getBackends() {
        return BACKENDS;
    }

    public static void resetProxyRouting() {
        CoordinatorProxyVertx proxy = currentProxy;
        if (proxy == null) {
            throw new IllegalStateException("Coordinator proxy is not running");
        }
        proxy.resetRoutingOrder(readyBackendIndexes());
    }

    public static URI nextRoutedBackend() {
        CoordinatorProxyVertx proxy = currentProxy;
        if (proxy == null) {
            throw new IllegalStateException("Coordinator proxy is not running");
        }

        Integer index = proxy.peekNextHealthyIndex();
        if (index == null) {
            throw new IllegalStateException("Coordinator proxy has no healthy backend available");
        }

        return BACKENDS.get(index);
    }

    private void waitForAnyBackend() {
        try {
            Awaitility.await("waiting for any coordinator backend to become reachable")
                    .atMost(BACKEND_BOOT_TIMEOUT)
                    .pollInterval(Duration.ofSeconds(1))
                    .until(CoordinatorProxyResource::anyBackendReachable);
            LOG.info("At least one coordinator backend is reachable: {}", BACKENDS);
        } catch (ConditionTimeoutException e) {
            String states = BACKENDS.stream()
                    .map(base -> base + "=" + (isBackendReachable(base) ? "up" : "down"))
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException(
                    "No coordinator became reachable within " + BACKEND_BOOT_TIMEOUT.toSeconds()
                            + " seconds. States: " + states + ". Start the HA stack manually from lra-ha-testsuite "
                            + "before running tests.",
                    e);
        }
    }

    private static boolean anyBackendReachable() {
        return !readyBackendIndexes().isEmpty();
    }

    private static boolean isBackendReachable(URI base) {
        URI readyUri = URI.create(base.getScheme() + "://" + base.getHost() + ":" + base.getPort() + "/q/health/ready");
        HttpRequest request = HttpRequest.newBuilder(readyUri)
                .GET()
                .timeout(BACKEND_CHECK_TIMEOUT)
                .build();

        try {
            HttpResponse<Void> response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while checking coordinator backend " + base, e);
        }
    }

    private static List<Integer> readyBackendIndexes() {
        return IntStream.range(0, BACKENDS.size())
                .filter(i -> isBackendReachable(BACKENDS.get(i)))
                .boxed()
                .toList();
    }
}
