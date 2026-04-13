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
 * Participant dedicated to AfterLraIT.
 *
 * <p>
 * The {@code @AfterLRA} annotation is separate from the compensate/complete
 * lifecycle: the coordinator calls the annotated PUT method once the LRA reaches
 * a terminal state ({@code Closed}, {@code Cancelled}, {@code FailedToClose},
 * {@code FailedToComplete}), passing the final {@link LRAStatus} as the entity
 * body. Per spec the coordinator MUST retry if the method returns an unexpected
 * HTTP status, so implementations must be idempotent.
 *
 * <p>
 * Endpoint variants provided:
 * <ul>
 * <li>{@code after} — records the received {@link LRAStatus} and call count; used
 * by the four terminal-state tests and HA crash tests.</li>
 * <li>{@code after-idempotent} — same as {@code after} but intentionally returns
 * 500 on the first call to trigger a coordinator retry, used by the
 * idempotency test.</li>
 * </ul>
 *
 * <p>
 * Both {@code @Complete} and {@code @Compensate} stubs are provided (sync,
 * returning 200) to support enrollment. Failing variants ({@code complete-fail},
 * {@code compensate-fail}) drive the {@code FailedToClose} / {@code FailedToCancel}
 * terminal states needed by those tests.
 */
@ApplicationScoped
@Path("after-lra-participant")
public class AfterLraParticipant {

    private static final Logger log = LoggerFactory.getLogger(AfterLraParticipant.class);

    /** The last-seen LRAStatus per LRA (overwritten on retry — tests assert idempotent side effects). */
    private final ConcurrentHashMap<String, String> receivedStatus = new ConcurrentHashMap<>();

    /** Total @AfterLRA call count per LRA (may be > 1 if coordinator retries). */
    private final ConcurrentHashMap<String, AtomicInteger> afterCallCounts = new ConcurrentHashMap<>();

    /** Set of LRA ids for which @AfterLRA work was actually performed (the idempotent guard). */
    private final Set<String> afterWorkDone = ConcurrentHashMap.newKeySet();

    /** Call count specifically for the idempotent after endpoint. */
    private final ConcurrentHashMap<String, AtomicInteger> afterIdempotentCounts = new ConcurrentHashMap<>();

    /**
     * Standard @AfterLRA endpoint. Records the status for every call; performs
     * the idempotent side-effect only on the first call. Always returns 200.
     */
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
     * Idempotency test variant: returns 500 on the first call so the coordinator
     * retries, then 200 on subsequent calls. The idempotent guard in
     * {@code afterWorkDone} ensures the side effect is performed exactly once
     * regardless of how many times the coordinator calls this method.
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

    /** Returns the last {@link LRAStatus} name received by @AfterLRA for this LRA, or "none". */
    @GET
    @Path("after-status")
    public String afterStatus(@QueryParam("lraId") String lraId) {
        return receivedStatus.getOrDefault(lraId, "none");
    }

    /** Returns how many times the {@code after} endpoint was called for this LRA. */
    @GET
    @Path("after-call-count")
    public int afterCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = afterCallCounts.get(lraId);
        return c == null ? 0 : c.get();
    }

    /** Returns how many times the {@code after-idempotent} endpoint was called for this LRA. */
    @GET
    @Path("after-idempotent-call-count")
    public int afterIdempotentCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger c = afterIdempotentCounts.get(lraId);
        return c == null ? 0 : c.get();
    }

    /** Returns 1 if the idempotent side-effect was performed for this LRA, 0 otherwise. */
    @GET
    @Path("after-work-done")
    public int afterWorkDone(@QueryParam("lraId") String lraId) {
        return afterWorkDone.contains(lraId) ? 1 : 0;
    }
}
