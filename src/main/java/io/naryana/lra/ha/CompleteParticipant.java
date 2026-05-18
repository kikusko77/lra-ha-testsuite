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
import org.jboss.logging.Logger;

/**
 * Provides every close-callback variant along with an idempotent cancel endpoint for
 * mutual-exclusion checks and diagnostic call counters.
 */
@ApplicationScoped
@Path("complete-participant")
public class CompleteParticipant {

    private static final Logger log = Logger.getLogger(CompleteParticipant.class);

    private final ConcurrentHashMap<String, AtomicInteger> idempotentCallCounts = new ConcurrentHashMap<>();
    private final Set<String> idempotentWorkDone = ConcurrentHashMap.newKeySet();
    private final Set<String> asyncCompleteCalled = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> asyncCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> asyncStatusCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> unreachableCallCounts = new ConcurrentHashMap<>();

    @Complete
    @PUT
    @Path("complete")
    public Response complete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.infof("COMPLETE lraId=%s", lraId);
        return Response.ok(ParticipantStatus.Completed.name()).build();
    }

    @Complete
    @PUT
    @Path("complete-idempotent")
    public Response completeIdempotent(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = idempotentCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        boolean first = idempotentWorkDone.add(uid);
        log.infof("COMPLETE-IDEMPOTENT lraId=%s call#%s first=%s", lraId, n, first);
        return Response.ok(ParticipantStatus.Completed.name()).build();
    }

    @Complete
    @PUT
    @Path("complete-async")
    public Response completeAsync(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = asyncCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        asyncCompleteCalled.add(uid);
        log.infof("COMPLETE-ASYNC lraId=%s call#%s", lraId, n);
        return Response.accepted().build();
    }

    @Complete
    @PUT
    @Path("complete-fail")
    public Response completeFail(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.infof("COMPLETE-FAIL lraId=%s", lraId);
        return Response.status(Response.Status.CONFLICT)
                .entity(ParticipantStatus.FailedToComplete.name())
                .build();
    }

    @Complete
    @PUT
    @Path("complete-unreachable")
    public Response completeUnreachable(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int call = unreachableCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        if (call == 1) {
            log.warnf("COMPLETE-UNREACHABLE lraId=%s simulating crash on first call", lraId);
            return Response.status(503).build();
        }
        log.infof("COMPLETE-UNREACHABLE lraId=%s recovered on call#%s", lraId, call);
        return Response.ok(ParticipantStatus.Completed.name()).build();
    }

    /**
     * Reports Active until complete-async has been called, then Completed.
     */
    @Status
    @GET
    @Path("status-for-async-complete")
    public Response statusForAsyncComplete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = asyncStatusCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        ParticipantStatus ps = asyncCompleteCalled.contains(uid)
                ? ParticipantStatus.Completed
                : ParticipantStatus.Active;
        log.infof("STATUS-FOR-ASYNC-COMPLETE lraId=%s call#%s → %s", lraId, n, ps);
        return Response.ok(ps.name()).build();
    }

    @Compensate
    @PUT
    @Path("compensate")
    public Response compensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.infof("COMPENSATE lraId=%s", lraId);
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

    /**
     * Tracked compensate — used by testCompensate_notCalledOnClose to verify
     *
     * @Compensate is never invoked when the LRA is closed.
     */
    @Compensate
    @PUT
    @Path("compensate-idempotent")
    public Response compensateIdempotent(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = idempotentCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        boolean first = idempotentWorkDone.add(uid);
        log.infof("COMPENSATE-IDEMPOTENT lraId=%s call#%s first=%s", lraId, n, first);
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

    @GET
    @Path("call-count")
    public int callCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = idempotentCallCounts.get(lraId);
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("idempotent-work-done")
    public int idempotentWorkDone(@QueryParam("lraId") String lraId) {
        return idempotentWorkDone.contains(lraId) ? 1 : 0;
    }

    @GET
    @Path("async-call-count")
    public int asyncCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = asyncCallCounts.get(lraId);
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("async-status-call-count")
    public int asyncStatusCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = asyncStatusCallCounts.get(lraId);
        return c == null ? 0 : c.get();
    }
}
