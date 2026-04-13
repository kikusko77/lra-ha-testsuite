package io.naryana.lra.ha;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.ParticipantStatus;
import org.eclipse.microprofile.lra.annotation.Status;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Participant dedicated to StatusIT.
 *
 * <p>
 * Covers two spec-defined {@code @Status} scenarios that are not exercised by
 * the async-compensate/complete tests in CompensateIT / CompleteIT:
 *
 * <ol>
 * <li><b>410 Gone</b> — the spec says a 410 response from {@code @Status} has the
 * same effect as 200: the participant signals it already completed/compensated
 * and no longer tracks the LRA. The coordinator must resolve the LRA without
 * replaying the {@code @Compensate} or {@code @Complete} callback.
 * Endpoints: {@code compensate-async} / {@code complete-async} paired with
 * {@code status-gone}.</li>
 *
 * <li><b>Intermediate state</b> — the coordinator must keep polling {@code @Status}
 * until it reaches a terminal state ({@code Compensated} / {@code Completed}).
 * The first poll returns the in-progress state ({@code Compensating} /
 * {@code Completing}); subsequent polls return the terminal state.
 * Endpoints: {@code compensate-async} / {@code complete-async} paired with
 * {@code status-intermediate-compensate} / {@code status-intermediate-complete}.
 * </li>
 * </ol>
 *
 * <p>
 * All async endpoints share the same {@code asyncCallCounts} map (keyed by LRA
 * id) because each test uses a unique LRA, so counts never collide across tests.
 * Separate status-call-count maps are kept per status variant so tests can assert
 * the exact number of polls.
 */
@ApplicationScoped
@Path("status-participant")
public class StatusParticipant {

    private static final Logger log = LoggerFactory.getLogger(StatusParticipant.class);

    // Tracks whether async compensate / complete was ever called for an LRA
    private final Set<String> asyncCompensateCalled = ConcurrentHashMap.newKeySet();
    private final Set<String> asyncCompleteCalled = ConcurrentHashMap.newKeySet();

    // Call-count maps shared by async compensate and complete endpoints
    private final ConcurrentHashMap<String, AtomicInteger> asyncCompensateCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> asyncCompleteCallCounts = new ConcurrentHashMap<>();

    // Status call counts — one map per status endpoint variant
    private final ConcurrentHashMap<String, AtomicInteger> statusGoneCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> statusIntermediateCompensateCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> statusIntermediateCompleteCallCounts = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, AtomicInteger> postAsyncCompensateStatusCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> postAsyncCompleteStatusCalls = new ConcurrentHashMap<>();


    @Compensate
    @PUT
    @Path("compensate-async")
    public Response compensateAsync(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = asyncCompensateCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        asyncCompensateCalled.add(uid);
        log.info("COMPENSATE-ASYNC lraId={} call#{}", lraId, n);
        return Response.accepted().build();
    }

    @Complete
    @PUT
    @Path("complete-async")
    public Response completeAsync(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = asyncCompleteCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        asyncCompleteCalled.add(uid);
        log.info("COMPLETE-ASYNC lraId={} call#{}", lraId, n);
        return Response.accepted().build();
    }

    @Status
    @GET
    @Path("status-gone")
    public Response statusGone(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = statusGoneCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        boolean called = asyncCompensateCalled.contains(uid) || asyncCompleteCalled.contains(uid);
        log.info("STATUS-GONE lraId={} call#{} asyncCalled={}", lraId, n, called);
        if (called) {
            // 410 = "I already acted, I no longer remember this LRA"
            return Response.status(Response.Status.GONE).build();
        }
        return Response.ok(ParticipantStatus.Active.name()).build();
    }


    @Status
    @GET
    @Path("status-intermediate-compensate")
    public Response statusIntermediateCompensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = statusIntermediateCompensateCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        ParticipantStatus ps;
        if (!asyncCompensateCalled.contains(uid)) {
            // @Compensate not yet called (e.g. a coordinator pre-flight check) — still Active
            ps = ParticipantStatus.Active;
        } else {
            // Count calls that arrive AFTER the async callback was delivered.
            // The first such call simulates the in-progress state; subsequent calls report the terminal state.
            int postN = postAsyncCompensateStatusCalls.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
            ps = postN == 1 ? ParticipantStatus.Compensating : ParticipantStatus.Compensated;
        }
        log.info("STATUS-INTERMEDIATE-COMPENSATE lraId={} call#{} → {}", lraId, n, ps);
        return Response.ok(ps.name()).build();
    }

    @Status
    @GET
    @Path("status-intermediate-complete")
    public Response statusIntermediateComplete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = statusIntermediateCompleteCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        ParticipantStatus ps;
        if (!asyncCompleteCalled.contains(uid)) {
            // @Complete not yet called (e.g. a coordinator pre-flight check) — still Active
            ps = ParticipantStatus.Active;
        } else {
            // Count calls that arrive AFTER the async callback was delivered.
            // The first such call simulates the in-progress state; subsequent calls report the terminal state.
            int postN = postAsyncCompleteStatusCalls.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
            ps = postN == 1 ? ParticipantStatus.Completing : ParticipantStatus.Completed;
        }
        log.info("STATUS-INTERMEDIATE-COMPLETE lraId={} call#{} → {}", lraId, n, ps);
        return Response.ok(ps.name()).build();
    }

    @Compensate
    @PUT
    @Path("compensate")
    public Response compensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("COMPENSATE lraId={}", lraId);
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

    @Complete
    @PUT
    @Path("complete")
    public Response complete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("COMPLETE lraId={}", lraId);
        return Response.ok(ParticipantStatus.Completed.name()).build();
    }

    @GET
    @Path("async-compensate-call-count")
    public int asyncCompensateCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = asyncCompensateCallCounts.get(lraId);
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("async-complete-call-count")
    public int asyncCompleteCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = asyncCompleteCallCounts.get(lraId);
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("status-gone-call-count")
    public int statusGoneCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = statusGoneCallCounts.get(lraId);
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("status-intermediate-compensate-call-count")
    public int statusIntermediateCompensateCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = statusIntermediateCompensateCallCounts.get(lraId);
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("status-intermediate-complete-call-count")
    public int statusIntermediateCompleteCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = statusIntermediateCompleteCallCounts.get(lraId);
        return c == null ? 0 : c.get();
    }
}
