package io.naryana.lra.ha;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DELETE;
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
import org.eclipse.microprofile.lra.annotation.Forget;
import org.eclipse.microprofile.lra.annotation.ParticipantStatus;
import org.eclipse.microprofile.lra.annotation.Status;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.jboss.logging.Logger;

/**
 * Drives the cleanup-after-failure path: returns 202, then reports a failed terminal
 * status to the coordinator's poll, which in turn triggers the cleanup callback.
 */
@ApplicationScoped
@Path("forget-participant")
public class ForgetParticipant {

    private static final Logger log = Logger.getLogger(ForgetParticipant.class);

    private final Set<String> asyncCompensateCalled = ConcurrentHashMap.newKeySet();
    private final Set<String> asyncCompleteCalled = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> asyncCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> asyncStatusCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> forgetCallCounts = new ConcurrentHashMap<>();

    @Compensate
    @PUT
    @Path("compensate-async")
    public Response compensateAsync(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = asyncCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        asyncCompensateCalled.add(uid);
        log.infof("COMPENSATE-ASYNC lraId=%s call#%s", lraId, n);
        return Response.accepted().build();
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

    /**
     * Reports Active until compensate-async has been called, then FailedToCompensate,
     * which causes the coordinator to call @Forget.
     */
    @Status
    @GET
    @Path("status-for-forget-compensate")
    public Response statusForForgetCompensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = asyncStatusCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        ParticipantStatus ps = asyncCompensateCalled.contains(uid)
                ? ParticipantStatus.FailedToCompensate
                : ParticipantStatus.Active;
        log.infof("STATUS-FOR-FORGET-COMPENSATE lraId=%s call#%s → %s", lraId, n, ps);
        return Response.ok(ps.name()).build();
    }

    /**
     * Reports Active until complete-async has been called, then FailedToComplete,
     * which causes the coordinator to call @Forget.
     */
    @Status
    @GET
    @Path("status-for-forget-complete")
    public Response statusForForgetComplete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = asyncStatusCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        ParticipantStatus ps = asyncCompleteCalled.contains(uid)
                ? ParticipantStatus.FailedToComplete
                : ParticipantStatus.Active;
        log.infof("STATUS-FOR-FORGET-COMPLETE lraId=%s call#%s → %s", lraId, n, ps);
        return Response.ok(ps.name()).build();
    }

    @Forget
    @DELETE
    @Path("forget")
    public Response forget(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = forgetCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        log.infof("FORGET lraId=%s call#%s", lraId, n);
        return Response.ok().build();
    }

    @Compensate
    @PUT
    @Path("compensate")
    public Response compensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.infof("COMPENSATE lraId=%s", lraId);
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

    @Complete
    @PUT
    @Path("complete")
    public Response complete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.infof("COMPLETE lraId=%s", lraId);
        return Response.ok(ParticipantStatus.Completed.name()).build();
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

    @GET
    @Path("forget-call-count")
    public int forgetCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = forgetCallCounts.get(lraId);
        return c == null ? 0 : c.get();
    }
}
