package io.naryana.lra.ha;

import io.narayana.lra.LRAConstants;
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
import org.eclipse.microprofile.lra.annotation.AfterLRA;
import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.Forget;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.annotation.ParticipantStatus;
import org.eclipse.microprofile.lra.annotation.Status;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.eclipse.microprofile.lra.annotation.ws.rs.Leave;
import org.jboss.logging.Logger;

@ApplicationScoped
@Path("nested-participant")
public class NestedParticipant {

    private static final Logger log = Logger.getLogger(NestedParticipant.class);

    private final ConcurrentHashMap<String, AtomicInteger> compensateIdempotentCallCounts = new ConcurrentHashMap<>();
    private final Set<String> compensateIdempotentWorkDone = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> asyncCompensateCallCounts = new ConcurrentHashMap<>();
    private final Set<String> asyncCompensateCalled = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> compensateUnreachableCallCounts = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, AtomicInteger> completeIdempotentCallCounts = new ConcurrentHashMap<>();
    private final Set<String> completeIdempotentWorkDone = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> asyncCompleteCallCounts = new ConcurrentHashMap<>();
    private final Set<String> asyncCompleteCalled = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> completeUnreachableCallCounts = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, AtomicInteger> asyncStatusCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> statusGoneCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> statusIntermediateCompensateCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> statusIntermediateCompleteCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> postAsyncCompensateStatusCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> postAsyncCompleteStatusCalls = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, AtomicInteger> forgetCallCounts = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, String> afterReceivedStatus = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> afterCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> afterIdempotentCounts = new ConcurrentHashMap<>();
    private final Set<String> afterWorkDone = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, AtomicInteger> totalCallbackCounts = new ConcurrentHashMap<>();

    @Compensate
    @PUT
    @Path("compensate")
    public Response compensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId,
            @HeaderParam(LRA.LRA_HTTP_PARENT_CONTEXT_HEADER) URI parentId) {
        totalCallbackCounts.computeIfAbsent(LRAConstants.getLRAUid(lraId), k -> new AtomicInteger()).incrementAndGet();
        log.infof("NESTED COMPENSATE lraId=%s parentId=%s", lraId, parentId);
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

    @Compensate
    @PUT
    @Path("compensate-idempotent")
    public Response compensateIdempotent(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId,
            @HeaderParam(LRA.LRA_HTTP_PARENT_CONTEXT_HEADER) URI parentId) {
        String uid = LRAConstants.getLRAUid(lraId);
        int n = compensateIdempotentCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        boolean first = compensateIdempotentWorkDone.add(uid);
        log.infof("NESTED COMPENSATE-IDEMPOTENT lraId=%s parentId=%s call#%s first=%s", lraId, parentId, n, first);
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

    @Compensate
    @PUT
    @Path("compensate-async")
    public Response compensateAsync(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId,
            @HeaderParam(LRA.LRA_HTTP_PARENT_CONTEXT_HEADER) URI parentId) {
        String uid = LRAConstants.getLRAUid(lraId);
        int n = asyncCompensateCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        asyncCompensateCalled.add(uid);
        log.infof("NESTED COMPENSATE-ASYNC lraId=%s parentId=%s call#%s", lraId, parentId, n);
        return Response.accepted().build();
    }

    @Compensate
    @PUT
    @Path("compensate-fail")
    public Response compensateFail(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId,
            @HeaderParam(LRA.LRA_HTTP_PARENT_CONTEXT_HEADER) URI parentId) {
        log.infof("NESTED COMPENSATE-FAIL lraId=%s parentId=%s", lraId, parentId);
        return Response.status(Response.Status.CONFLICT)
                .entity(ParticipantStatus.FailedToCompensate.name())
                .build();
    }

    @Compensate
    @PUT
    @Path("compensate-unreachable")
    public Response compensateUnreachable(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = LRAConstants.getLRAUid(lraId);
        int call = compensateUnreachableCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        if (call == 1) {
            log.warnf("NESTED COMPENSATE-UNREACHABLE lraId=%s simulating crash on first call", lraId);
            return Response.status(503).build();
        }
        log.infof("NESTED COMPENSATE-UNREACHABLE lraId=%s recovered on call#%s", lraId, call);
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

    @Complete
    @PUT
    @Path("complete")
    public Response complete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId,
            @HeaderParam(LRA.LRA_HTTP_PARENT_CONTEXT_HEADER) URI parentId) {
        totalCallbackCounts.computeIfAbsent(LRAConstants.getLRAUid(lraId), k -> new AtomicInteger()).incrementAndGet();
        log.infof("NESTED COMPLETE lraId=%s parentId=%s", lraId, parentId);
        return Response.ok(ParticipantStatus.Completed.name()).build();
    }

    @Complete
    @PUT
    @Path("complete-idempotent")
    public Response completeIdempotent(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId,
            @HeaderParam(LRA.LRA_HTTP_PARENT_CONTEXT_HEADER) URI parentId) {
        String uid = LRAConstants.getLRAUid(lraId);
        int n = completeIdempotentCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        boolean first = completeIdempotentWorkDone.add(uid);
        log.infof("NESTED COMPLETE-IDEMPOTENT lraId=%s parentId=%s call#%s first=%s", lraId, parentId, n, first);
        return Response.ok(ParticipantStatus.Completed.name()).build();
    }

    @Complete
    @PUT
    @Path("complete-async")
    public Response completeAsync(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId,
            @HeaderParam(LRA.LRA_HTTP_PARENT_CONTEXT_HEADER) URI parentId) {
        String uid = LRAConstants.getLRAUid(lraId);
        int n = asyncCompleteCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        asyncCompleteCalled.add(uid);
        log.infof("NESTED COMPLETE-ASYNC lraId=%s parentId=%s call#%s", lraId, parentId, n);
        return Response.accepted().build();
    }

    @Complete
    @PUT
    @Path("complete-fail")
    public Response completeFail(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId,
            @HeaderParam(LRA.LRA_HTTP_PARENT_CONTEXT_HEADER) URI parentId) {
        log.infof("NESTED COMPLETE-FAIL lraId=%s parentId=%s", lraId, parentId);
        return Response.status(Response.Status.CONFLICT)
                .entity(ParticipantStatus.FailedToComplete.name())
                .build();
    }

    @Complete
    @PUT
    @Path("complete-unreachable")
    public Response completeUnreachable(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = LRAConstants.getLRAUid(lraId);
        int call = completeUnreachableCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        if (call == 1) {
            log.warnf("NESTED COMPLETE-UNREACHABLE lraId=%s simulating crash on first call", lraId);
            return Response.status(503).build();
        }
        log.infof("NESTED COMPLETE-UNREACHABLE lraId=%s recovered on call#%s", lraId, call);
        return Response.ok(ParticipantStatus.Completed.name()).build();
    }

    @Status
    @GET
    @Path("status-for-async")
    public Response statusForAsync(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = LRAConstants.getLRAUid(lraId);
        int n = asyncStatusCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        ParticipantStatus ps = asyncCompensateCalled.contains(uid)
                ? ParticipantStatus.Compensated
                : ParticipantStatus.Active;
        log.infof("NESTED STATUS-FOR-ASYNC lraId=%s call#%s → %s", lraId, n, ps);
        return Response.ok(ps.name()).build();
    }

    @Status
    @GET
    @Path("status-for-async-complete")
    public Response statusForAsyncComplete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = LRAConstants.getLRAUid(lraId);
        int n = asyncStatusCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        ParticipantStatus ps = asyncCompleteCalled.contains(uid)
                ? ParticipantStatus.Completed
                : ParticipantStatus.Active;
        log.infof("NESTED STATUS-FOR-ASYNC-COMPLETE lraId=%s call#%s → %s", lraId, n, ps);
        return Response.ok(ps.name()).build();
    }

    @Status
    @GET
    @Path("status-for-forget-compensate")
    public Response statusForForgetCompensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = LRAConstants.getLRAUid(lraId);
        int n = asyncStatusCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        ParticipantStatus ps = asyncCompensateCalled.contains(uid)
                ? ParticipantStatus.FailedToCompensate
                : ParticipantStatus.Active;
        log.infof("NESTED STATUS-FOR-FORGET-COMPENSATE lraId=%s call#%s → %s", lraId, n, ps);
        return Response.ok(ps.name()).build();
    }

    @Status
    @GET
    @Path("status-for-forget-complete")
    public Response statusForForgetComplete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = LRAConstants.getLRAUid(lraId);
        int n = asyncStatusCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        ParticipantStatus ps = asyncCompleteCalled.contains(uid)
                ? ParticipantStatus.FailedToComplete
                : ParticipantStatus.Active;
        log.infof("NESTED STATUS-FOR-FORGET-COMPLETE lraId=%s call#%s → %s", lraId, n, ps);
        return Response.ok(ps.name()).build();
    }

    @Status
    @GET
    @Path("status-gone")
    public Response statusGone(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = LRAConstants.getLRAUid(lraId);
        int n = statusGoneCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        boolean called = asyncCompensateCalled.contains(uid) || asyncCompleteCalled.contains(uid);
        log.infof("NESTED STATUS-GONE lraId=%s call#%s asyncCalled=%s", lraId, n, called);
        if (called) {
            return Response.status(Response.Status.GONE).build();
        }
        return Response.ok(ParticipantStatus.Active.name()).build();
    }

    @Status
    @GET
    @Path("status-intermediate-compensate")
    public Response statusIntermediateCompensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = LRAConstants.getLRAUid(lraId);
        int n = statusIntermediateCompensateCallCounts.computeIfAbsent(uid, k -> new AtomicInteger())
                .incrementAndGet();
        ParticipantStatus ps;
        if (!asyncCompensateCalled.contains(uid)) {
            ps = ParticipantStatus.Active;
        } else {
            int postN = postAsyncCompensateStatusCalls.computeIfAbsent(uid, k -> new AtomicInteger())
                    .incrementAndGet();
            ps = postN == 1 ? ParticipantStatus.Compensating : ParticipantStatus.Compensated;
        }
        log.infof("NESTED STATUS-INTERMEDIATE-COMPENSATE lraId=%s call#%s → %s", lraId, n, ps);
        return Response.ok(ps.name()).build();
    }

    @Status
    @GET
    @Path("status-intermediate-complete")
    public Response statusIntermediateComplete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = LRAConstants.getLRAUid(lraId);
        int n = statusIntermediateCompleteCallCounts.computeIfAbsent(uid, k -> new AtomicInteger())
                .incrementAndGet();
        ParticipantStatus ps;
        if (!asyncCompleteCalled.contains(uid)) {
            ps = ParticipantStatus.Active;
        } else {
            int postN = postAsyncCompleteStatusCalls.computeIfAbsent(uid, k -> new AtomicInteger())
                    .incrementAndGet();
            ps = postN == 1 ? ParticipantStatus.Completing : ParticipantStatus.Completed;
        }
        log.infof("NESTED STATUS-INTERMEDIATE-COMPLETE lraId=%s call#%s → %s", lraId, n, ps);
        return Response.ok(ps.name()).build();
    }

    @Forget
    @DELETE
    @Path("forget")
    public Response forget(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId,
            @HeaderParam(LRA.LRA_HTTP_PARENT_CONTEXT_HEADER) URI parentId) {
        String uid = LRAConstants.getLRAUid(lraId);
        int n = forgetCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        log.infof("NESTED FORGET lraId=%s parentId=%s call#%s", lraId, parentId, n);
        return Response.ok().build();
    }

    @AfterLRA
    @PUT
    @Path("after")
    public Response after(
            @HeaderParam(LRA.LRA_HTTP_ENDED_CONTEXT_HEADER) URI endedLraId,
            @HeaderParam(LRA.LRA_HTTP_PARENT_CONTEXT_HEADER) URI parentId,
            LRAStatus status) {
        String uid = LRAConstants.getLRAUid(endedLraId);
        int n = afterCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        String statusName = status != null ? status.name() : "null";
        afterReceivedStatus.put(uid, statusName);
        boolean first = afterWorkDone.add(uid);
        log.infof("NESTED AFTER-LRA endedLraId=%s parentId=%s status=%s call#%s firstWork=%s",
                endedLraId, parentId, statusName, n, first);
        return Response.ok().build();
    }

    @AfterLRA
    @PUT
    @Path("after-idempotent")
    public Response afterIdempotent(
            @HeaderParam(LRA.LRA_HTTP_ENDED_CONTEXT_HEADER) URI endedLraId,
            @HeaderParam(LRA.LRA_HTTP_PARENT_CONTEXT_HEADER) URI parentId,
            LRAStatus status) {
        String uid = LRAConstants.getLRAUid(endedLraId);
        int n = afterIdempotentCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        String statusName = status != null ? status.name() : "null";
        afterReceivedStatus.put(uid, statusName);
        boolean first = afterWorkDone.add(uid);
        log.infof("NESTED AFTER-LRA-IDEMPOTENT endedLraId=%s parentId=%s status=%s call#%s firstWork=%s",
                endedLraId, parentId, statusName, n, first);
        if (n == 1) {
            log.warnf("NESTED AFTER-LRA-IDEMPOTENT endedLraId=%s returning 500 to trigger retry", endedLraId);
            return Response.serverError().build();
        }
        return Response.ok().build();
    }

    @Leave
    @PUT
    @Path("leave")
    public Response leave(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.infof("NESTED LEAVE called lraId=%s — filter will remove this participant", lraId);
        return Response.ok().build();
    }

    private static String key(String lraId) {
        return LRAConstants.getLRAUid(URI.create(lraId));
    }

    @GET
    @Path("idempotent-call-count")
    public int idempotentCallCount(@QueryParam("lraId") String lraId) {
        String uid = key(lraId);
        AtomicInteger compensate = compensateIdempotentCallCounts.get(uid);
        AtomicInteger complete = completeIdempotentCallCounts.get(uid);
        AtomicInteger total = totalCallbackCounts.get(uid);
        int sum = (compensate == null ? 0 : compensate.get())
                + (complete == null ? 0 : complete.get())
                + (total == null ? 0 : total.get());
        return sum;
    }

    @GET
    @Path("idempotent-work-done")
    public int idempotentWorkDone(@QueryParam("lraId") String lraId) {
        String uid = key(lraId);
        return (compensateIdempotentWorkDone.contains(uid) || completeIdempotentWorkDone.contains(uid)) ? 1 : 0;
    }

    @GET
    @Path("async-call-count")
    public int asyncCallCount(@QueryParam("lraId") String lraId) {
        String uid = key(lraId);
        AtomicInteger compensate = asyncCompensateCallCounts.get(uid);
        AtomicInteger complete = asyncCompleteCallCounts.get(uid);
        return (compensate == null ? 0 : compensate.get())
                + (complete == null ? 0 : complete.get());
    }

    @GET
    @Path("async-status-call-count")
    public int asyncStatusCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = asyncStatusCallCounts.get(key(lraId));
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("async-compensate-call-count")
    public int asyncCompensateCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = asyncCompensateCallCounts.get(key(lraId));
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("async-complete-call-count")
    public int asyncCompleteCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = asyncCompleteCallCounts.get(key(lraId));
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("status-gone-call-count")
    public int statusGoneCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = statusGoneCallCounts.get(key(lraId));
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("status-intermediate-compensate-call-count")
    public int statusIntermediateCompensateCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = statusIntermediateCompensateCallCounts.get(key(lraId));
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("status-intermediate-complete-call-count")
    public int statusIntermediateCompleteCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = statusIntermediateCompleteCallCounts.get(key(lraId));
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("forget-call-count")
    public int forgetCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = forgetCallCounts.get(key(lraId));
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("after-status")
    public String afterStatus(@QueryParam("lraId") String lraId) {
        return afterReceivedStatus.getOrDefault(key(lraId), "none");
    }

    @GET
    @Path("after-call-count")
    public int afterCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = afterCallCounts.get(key(lraId));
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("after-idempotent-call-count")
    public int afterIdempotentCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = afterIdempotentCounts.get(key(lraId));
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("after-work-done")
    public int afterWorkDone(@QueryParam("lraId") String lraId) {
        return afterWorkDone.contains(key(lraId)) ? 1 : 0;
    }
}
