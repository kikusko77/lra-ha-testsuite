package io.narayana.lra.ha.proxy;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.client.WebClient;
import java.io.Closeable;
import java.net.URI;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple reverse proxy for LRA coordinators, built with Vert.x.
 *
 * <p>
 * How it works:
 * <ol>
 * <li>Every incoming request goes into a FIFO queue.
 * <li>A dispatcher thread picks requests one by one and sends them to the
 * next coordinator in the healthy queue using the Vert.x {@link WebClient}.
 * <li>If a coordinator fails it moves to the crashed queue. A background
 * timer checks crashed coordinators via {@code /q/health} and moves them
 * back when they respond with HTTP 200.
 * <li>If a request waits longer than {@value #REQUEST_TIMEOUT_MS} ms it gets a 503.
 * </ol>
 */
public class CoordinatorProxyVertx implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(CoordinatorProxyVertx.class);

    private static final int HEALTH_CHECK_MS = 2_000;
    private static final int FORWARD_TIMEOUT_MS = 10_000;
    private static final int REQUEST_TIMEOUT_MS = 60_000;
    private static final int POLL_MS = 200;

    private final List<URI> backends;
    private final int port;

    private final Vertx vertx;
    private final WebClient webClient;
    private final HttpServer server;
    private Thread dispatcher;

    /** Incoming requests waiting to be forwarded, in arrival order. */
    private final BlockingDeque<PendingRequest> queue = new LinkedBlockingDeque<>();

    /** Coordinators ready to handle requests (index into {@link #backends}). */
    private final BlockingDeque<Integer> healthy = new LinkedBlockingDeque<>();

    /** Coordinators that failed — polled via {@code /q/health} until they recover. */
    private final Queue<Integer> crashed = new ConcurrentLinkedQueue<>();

    // -------------------------------------------------------------------------

    /**
     * One request sitting in the queue.
     * {@link #done} is flipped to {@code true} exactly once — either when a
     * response is written or when the request times out — so we never write
     * to the same exchange twice.
     */
    private static class PendingRequest {
        final HttpServerRequest request;
        final Buffer body;
        final AtomicBoolean done = new AtomicBoolean(false);

        PendingRequest(HttpServerRequest request, Buffer body) {
            this.request = request;
            this.body = body;
        }
    }

    // -------------------------------------------------------------------------

    /**
     * @param port local port to listen on
     * @param backends real coordinator base URIs, e.g. {@code http://localhost:8081/lra-coordinator}
     */
    public CoordinatorProxyVertx(int port, List<URI> backends) {
        this.port = port;
        this.backends = List.copyOf(backends);
        this.vertx = Vertx.vertx();
        this.webClient = WebClient.create(vertx);
        this.server = vertx.createHttpServer().requestHandler(this::enqueue);

        for (int i = 0; i < backends.size(); i++) {
            healthy.add(i);
        }
    }

    /** Starts the server, the dispatcher thread, and the health-check timer. */
    public void start() throws Exception {
        server.listen(port).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        dispatcher = new Thread(this::dispatchLoop, "lra-proxy-vertx-dispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();

        vertx.setPeriodic(HEALTH_CHECK_MS, id -> checkCrashed());

        LOG.info("CoordinatorProxyVertx started on :{} → {}", port, backends);
    }

    /** The URL that LRA clients and tests should use. */
    public URI proxyCoordinatorUri() {
        return URI.create("http://localhost:" + port + "/lra-coordinator");
    }

    @Override
    public void close() {
        if (dispatcher != null)
            dispatcher.interrupt();
        server.close();
        webClient.close();
        vertx.close();
    }

    // -------------------------------------------------------------------------

    /** Reads the body and pushes the request into the FIFO queue. */
    private void enqueue(HttpServerRequest request) {
        request.body().onSuccess(body -> {
            var req = new PendingRequest(request, body);
            queue.add(req);
            LOG.info("Queued {} {} (depth {})", request.method(), request.uri(), queue.size());

            // Time out the request if it waits too long.
            vertx.setTimer(REQUEST_TIMEOUT_MS, id -> {
                if (req.done.compareAndSet(false, true)) {
                    queue.remove(req);
                    request.response().setStatusCode(503).end();
                    LOG.warn("Timed out: {} {}", request.method(), request.uri());
                }
            });
        });
    }

    /**
     * Runs on a dedicated thread. Takes one request at a time from the front of
     * the queue and picks the next healthy coordinator. If no coordinator is
     * available the request goes back to the front and the thread waits briefly.
     */
    private void dispatchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            PendingRequest req;
            try {
                req = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (req.done.get())
                continue; // already timed out

            Integer idx = healthy.poll();
            if (idx == null) {
                queue.addFirst(req); // no coordinator yet — keep at front
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }

            forward(req, idx);
        }
    }

    /**
     * Sends the request to the given coordinator via the Vert.x WebClient.
     * Returns immediately — success/failure callbacks run on the Vert.x event loop.
     */
    private void forward(PendingRequest req, int idx) {
        if (req.done.get()) {
            healthy.addLast(idx);
            return;
        }

        URI backend = backends.get(idx);
        String url = backend.getScheme() + "://" + backend.getHost() + ":" + backend.getPort()
                + req.request.uri();

        LOG.info("→ [{}] {} {}", idx, req.request.method(), url);

        webClient.requestAbs(req.request.method(), url)
                .timeout(FORWARD_TIMEOUT_MS)
                .putHeaders(req.request.headers())
                .sendBuffer(req.body)
                .onSuccess(resp -> {
                    if (req.done.compareAndSet(false, true)) {
                        var r = req.request.response().setStatusCode(resp.statusCode());
                        resp.headers().forEach(e -> r.headers().add(e.getKey(), e.getValue()));
                        Buffer body = resp.body();
                        if (body != null && body.length() > 0)
                            r.end(body);
                        else
                            r.end();
                        LOG.info("← [{}] {}", idx, resp.statusCode());
                    }
                    healthy.addLast(idx);
                })
                .onFailure(err -> {
                    LOG.warn("✗ [{}] {} failed: {}", idx, backend, err.getMessage());
                    crashed.add(idx);
                    if (!req.done.get())
                        queue.addFirst(req); // retry from front
                });
    }

    /**
     * Called every {@value #HEALTH_CHECK_MS} ms. Checks each crashed coordinator
     * via {@code /q/health} and moves it back to the healthy queue on HTTP 200.
     */
    private void checkCrashed() {
        for (Integer idx : crashed) {
            URI base = backends.get(idx);
            String url = base.getScheme() + "://" + base.getHost() + ":" + base.getPort() + "/q/health";

            webClient.getAbs(url).timeout(HEALTH_CHECK_MS).send()
                    .onSuccess(r -> {
                        if (r.statusCode() == 200 && crashed.remove(idx)) {
                            healthy.addLast(idx);
                            LOG.info("↑ [{}] {} recovered", idx, base);
                        }
                    });
        }
    }
}
