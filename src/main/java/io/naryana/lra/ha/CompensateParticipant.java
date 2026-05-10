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
 * Provides every cancellation-callback variant (synchronous, idempotent, asynchronous,
 * permanent failure, transient failure) along with diagnostic call counters.
 */
@ApplicationScoped
@Path("compensate-participant")
public class CompensateParticipant {

    private static final Logger log = Logger.getLogger(CompensateParticipant.class);

    private final ConcurrentHashMap<String, AtomicInteger> idempotentCallCounts = new ConcurrentHashMap<>();
    private final Set<String> idempotentWorkDone = ConcurrentHashMap.newKeySet();
    private final Set<String> asyncCompensateCalled = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> asyncCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> asyncStatusCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> unreachableCallCounts = new ConcurrentHashMap<>();

    @Compensate
    @PUT
    @Path("compensate")
    public Response compensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.infof("COMPENSATE lraId=%s", lraId);
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

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

    @Compensate
    @PUT
    @Path("compensate-fail")
    public Response compensateFail(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.infof("COMPENSATE-FAIL lraId=%s", lraId);
        return Response.status(Response.Status.CONFLICT)
                .entity(ParticipantStatus.FailedToCompensate.name())
                .build();
    }

    @Compensate
    @PUT
    @Path("compensate-unreachable")
    public Response compensateUnreachable(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int call = unreachableCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        if (call == 1) {
            log.warnf("COMPENSATE-UNREACHABLE lraId=%s simulating crash on first call", lraId);
            return Response.status(503).build();
        }
        log.infof("COMPENSATE-UNREACHABLE lraId=%s recovered on call#%s", lraId, call);
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

    /**
     * Reports Active until compensate-async has been called, then Compensated.
     */
    @Status
    @GET
    @Path("status-for-async")
    public Response statusForAsync(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = asyncStatusCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        ParticipantStatus ps = asyncCompensateCalled.contains(uid)
                ? ParticipantStatus.Compensated
                : ParticipantStatus.Active;
        log.infof("STATUS-FOR-ASYNC lraId=%s call#%s → %s", lraId, n, ps);
        return Response.ok(ps.name()).build();
    }

    @Complete
    @PUT
    @Path("complete")
    public Response complete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.infof("COMPLETE lraId=%s", lraId);
        return Response.ok(ParticipantStatus.Completed.name()).build();
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
