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
import org.eclipse.microprofile.lra.annotation.AfterLRA;
import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.annotation.ParticipantStatus;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records the post-terminal-state notification, exposing both a happy-path endpoint and
 * one that fails the first call so the coordinator's retry path can be exercised.
 */
@ApplicationScoped
@Path("after-lra-participant")
public class AfterLraParticipant {

    private static final Logger log = LoggerFactory.getLogger(AfterLraParticipant.class);

    private final ConcurrentHashMap<String, String> receivedStatus = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> afterCallCounts = new ConcurrentHashMap<>();
    private final Set<String> afterWorkDone = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> afterIdempotentCounts = new ConcurrentHashMap<>();

    @AfterLRA
    @PUT
    @Path("after")
    public Response after(
            @HeaderParam(LRA.LRA_HTTP_ENDED_CONTEXT_HEADER) URI lraId,
            LRAStatus status) {
        String uid = lraId.toASCIIString();
        int n = afterCallCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        String statusName = status != null ? status.name() : "null";
        receivedStatus.put(uid, statusName);
        boolean first = afterWorkDone.add(uid);
        log.info("AFTER-LRA lraId={} status={} call#{} firstWork={}", lraId, statusName, n, first);
        return Response.ok().build();
    }

    /**
     * Returns a server error on the first call so the coordinator must retry, then succeeds;
     * the participant-side guard keeps the side effect at exactly one execution.
     */
    @AfterLRA
    @PUT
    @Path("after-idempotent")
    public Response afterIdempotent(
            @HeaderParam(LRA.LRA_HTTP_ENDED_CONTEXT_HEADER) URI lraId,
            LRAStatus status) {
        String uid = lraId.toASCIIString();
        int n = afterIdempotentCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        String statusName = status != null ? status.name() : "null";
        receivedStatus.put(uid, statusName);
        boolean first = afterWorkDone.add(uid); // side effect: happens only once
        log.info("AFTER-LRA-IDEMPOTENT lraId={} status={} call#{} firstWork={}", lraId, statusName, n, first);
        if (n == 1) {
            log.warn("AFTER-LRA-IDEMPOTENT lraId={} returning 500 on first call to trigger coordinator retry", lraId);
            return Response.serverError().build();
        }
        return Response.ok().build();
    }

    @Complete
    @PUT
    @Path("complete")
    public Response complete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("COMPLETE lraId={}", lraId);
        return Response.ok(ParticipantStatus.Completed.name()).build();
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
    @Path("complete-fail")
    public Response completeFail(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("COMPLETE-FAIL lraId={}", lraId);
        return Response.status(Response.Status.CONFLICT)
                .entity(ParticipantStatus.FailedToComplete.name())
                .build();
    }

    @Compensate
    @PUT
    @Path("compensate-fail")
    public Response compensateFail(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("COMPENSATE-FAIL lraId={}", lraId);
        return Response.status(Response.Status.CONFLICT)
                .entity(ParticipantStatus.FailedToCompensate.name())
                .build();
    }

    @GET
    @Path("after-status")
    public String afterStatus(@QueryParam("lraId") String lraId) {
        return receivedStatus.getOrDefault(lraId, "none");
    }

    @GET
    @Path("after-call-count")
    public int afterCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = afterCallCounts.get(lraId);
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("after-idempotent-call-count")
    public int afterIdempotentCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = afterIdempotentCounts.get(lraId);
        return c == null ? 0 : c.get();
    }

    @GET
    @Path("after-work-done")
    public int afterWorkDone(@QueryParam("lraId") String lraId) {
        return afterWorkDone.contains(lraId) ? 1 : 0;
    }
}
