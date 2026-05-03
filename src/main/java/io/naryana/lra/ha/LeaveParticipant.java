package io.naryana.lra.ha;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.ParticipantStatus;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.eclipse.microprofile.lra.annotation.ws.rs.Leave;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Participant that can remove itself from an active transaction; the coordinator then
 * skips it on every subsequent terminal callback.
 */
@ApplicationScoped
@Path("leave-participant")
public class LeaveParticipant {

    private static final Logger log = LoggerFactory.getLogger(LeaveParticipant.class);

    private final ConcurrentHashMap<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();

    @Compensate
    @PUT
    @Path("compensate")
    public Response compensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = callCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        log.info("COMPENSATE lraId={} call#{}", lraId, n);
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

    @Complete
    @PUT
    @Path("complete")
    public Response complete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int n = callCounts.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        log.info("COMPLETE lraId={} call#{}", lraId, n);
        return Response.ok(ParticipantStatus.Completed.name()).build();
    }

    /**
     * Calling this endpoint while an LRA context header is present causes the
     * Narayana filter to remove this participant from that LRA. The method body
     * intentionally does nothing: the filter acts before the method executes.
     */
    @Leave
    @PUT
    @Path("leave")
    public Response leave(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("LEAVE called for lraId={} — filter will remove this participant", lraId);
        return Response.ok().build();
    }

    /**
     * Returns the total number of compensate or complete calls received for the
     * given LRA. Tests assert this is 0 after a successful leave, and 1 in the
     * no-leave baseline.
     */
    @GET
    @Path("idempotent-call-count")
    public int callCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = callCounts.get(lraId);
        return c == null ? 0 : c.get();
    }
}
