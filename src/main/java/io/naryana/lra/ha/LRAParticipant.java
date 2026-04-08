/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.naryana.lra.ha;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
@Path(LRAParticipant.RESOURCE_PATH)
public class LRAParticipant {
    public static final String RESOURCE_PATH = "lra-participant";

    public static final String CREATE_OR_CONTINUE_LRA = "start-lra";
    public static final String END_EXISTING_LRA = "end-lra";
    public static final String AFTER_LRA = "after-lra";
    public static final String COMPLETE_LRA = "complete";
    public static final String COMPENSATE_LRA = "compensate";
    public static final String LRA_STATUS = "status";
    public static final String FORGET_LRA = "forget";

    /** Idempotent compensate: side effect runs once per LRA regardless of how many times called. */
    public static final String COMPENSATE_IDEMPOTENT = "compensate-idempotent";
    /** Async compensate: returns 202, status reported via STATUS_FOR_ASYNC. */
    public static final String COMPENSATE_ASYNC = "compensate-async";
    /** Status endpoint for the async compensate path. */
    public static final String STATUS_FOR_ASYNC = "status-for-async";
    /** Failing compensate: always returns 409 → FailedToCompensate. */
    public static final String COMPENSATE_FAIL = "compensate-fail";
    /**
     * Transient-failure compensate: first call per LRA returns 503,
     * subsequent calls return 200. Simulates a participant crash that recovers.
     */
    public static final String COMPENSATE_UNREACHABLE = "compensate-unreachable";

    /** Idempotent complete: side effect runs once per LRA regardless of how many times called. */
    public static final String COMPLETE_IDEMPOTENT = "complete-idempotent";
    /** Async complete: returns 202, status reported via STATUS_FOR_ASYNC_COMPLETE. */
    public static final String COMPLETE_ASYNC = "complete-async";
    /** Status endpoint for the async complete path. */
    public static final String STATUS_FOR_ASYNC_COMPLETE = "status-for-async-complete";
    /** Failing complete: always returns 409 → FailedToComplete. */
    public static final String COMPLETE_FAIL = "complete-fail";
    /**
     * Transient-failure complete: first call per LRA returns 503,
     * subsequent calls return 200. Simulates a participant crash that recovers.
     */
    public static final String COMPLETE_UNREACHABLE = "complete-unreachable";

    /** Diagnostic: total times the idempotent endpoint (compensate or complete) was called for a given LRA. */
    public static final String IDEMPOTENT_CALL_COUNT = "idempotent-call-count";
    /** Diagnostic: whether the idempotent work was actually performed (0 or 1). */
    public static final String IDEMPOTENT_WORK_DONE = "idempotent-work-done";
    /** Diagnostic: total times an async compensate or complete endpoint was called for a given LRA. */
    public static final String ASYNC_CALL_COUNT = "async-call-count";
    /** Diagnostic: total times a status endpoint was polled for a given LRA. */
    public static final String ASYNC_STATUS_CALL_COUNT = "async-status-call-count";
    /** Control: reset all participant test state. */
    public static final String RESET_PARTICIPANT_STATE = "reset-participant-state";

    private static final Logger log = LoggerFactory.getLogger(LRAParticipant.class);
    /** Total idempotent-endpoint calls per LRA (used by both compensate-idempotent and complete-idempotent). */
    private final ConcurrentHashMap<String, AtomicInteger> idempotentCallCounts = new ConcurrentHashMap<>();
    /** LRAs for which the idempotent side effect has been executed (at most once). */
    private final Set<String> idempotentWorkDone = ConcurrentHashMap.newKeySet();

    /** LRAs for which compensate-async has been called (used by status-for-async). */
    private final Set<String> asyncCompensateCalled = ConcurrentHashMap.newKeySet();
    /** LRAs for which complete-async has been called (used by status-for-async-complete). */
    private final Set<String> asyncCompleteCalled = ConcurrentHashMap.newKeySet();
    /** Total async-endpoint calls per LRA (shared by compensate-async and complete-async). */
    private final ConcurrentHashMap<String, AtomicInteger> asyncCallCounts = new ConcurrentHashMap<>();
    /** Total status-endpoint polls per LRA (shared by both status endpoints). */
    private final ConcurrentHashMap<String, AtomicInteger> asyncStatusCallCounts = new ConcurrentHashMap<>();
    /** Call count per LRA; first call returns 503, later calls return 200 (shared by both unreachable endpoints). */
    private final ConcurrentHashMap<String, AtomicInteger> unreachableCallCounts = new ConcurrentHashMap<>();

    @LRA(end = false)
    @GET
    @Path(CREATE_OR_CONTINUE_LRA)
    public Response bookGame(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("START-LRA called, lraId={}", lraId);
        return Response.status(Response.Status.OK).entity(lraId.toASCIIString()).build();
    }

    @LRA(end = true)
    @GET
    @Path(END_EXISTING_LRA)
    public Response endTripBooking(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("END-LRA called, lraId={}", lraId);
        return Response.status(Response.Status.OK).entity(lraId.toASCIIString()).build();
    }

    @AfterLRA
    @PUT
    @Path(AFTER_LRA)
    public Response bookingProcessed(@HeaderParam(LRA.LRA_HTTP_ENDED_CONTEXT_HEADER) URI lraId, LRAStatus status) {
        log.info("AFTER-LRA callback received, lraId={}, status={}", lraId, status);
        return Response.status(Response.Status.OK).entity(lraId.toASCIIString()).build();
    }

    @Complete
    @PUT
    @Path(COMPLETE_LRA)
    public Response complete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("COMPLETE callback received, lraId={}", lraId);
        return Response.ok(Response.Status.OK).build();
    }

    @Compensate
    @PUT
    @Path(COMPENSATE_LRA)
    public Response compensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("COMPENSATE callback received, lraId={}", lraId);
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

    @Status
    @GET
    @Path(LRA_STATUS)
    public Response status(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("STATUS callback received, lraId={}", lraId);
        return Response.ok(ParticipantStatus.Completed.name()).build();
    }

    @Forget
    @DELETE
    @Path(FORGET_LRA)
    public Response forget(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("FORGET callback received, lraId={}", lraId);
        return Response.ok().build();
    }

    /**
     * Idempotent compensate — safe to call multiple times.
     * The side effect runs at most once per LRA; every call is still counted
     * so tests can check how many retries the coordinator made.
     */
    @Compensate
    @PUT
    @Path(COMPENSATE_IDEMPOTENT)
    public Response compensateIdempotent(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int totalCalls = idempotentCallCounts
                .computeIfAbsent(uid, k -> new AtomicInteger())
                .incrementAndGet();

        boolean firstTime = idempotentWorkDone.add(uid);
        if (firstTime) {
            log.info("COMPENSATE-IDEMPOTENT: doing work for lraId={} (call #{})", lraId, totalCalls);
        } else {
            log.info("COMPENSATE-IDEMPOTENT: skipping duplicate work for lraId={} (call #{})", lraId, totalCalls);
        }
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

    /**
     * Async compensate — returns 202 Accepted immediately and records the call.
     * The coordinator must poll the status endpoint to learn when compensation is done.
     */
    @Compensate
    @PUT
    @Path(COMPENSATE_ASYNC)
    public Response compensateAsync(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int totalCalls = asyncCallCounts
                .computeIfAbsent(uid, k -> new AtomicInteger())
                .incrementAndGet();
        asyncCompensateCalled.add(uid);
        log.info("COMPENSATE-ASYNC called for lraId={} (call #{}), returning 202", lraId, totalCalls);
        return Response.accepted().build();
    }

    /**
     * Status endpoint for the async compensate path.
     * Reports Active until compensateAsync has been called, then Compensated.
     * This allows the coordinator's pre-flight status probe to distinguish
     * between "not contacted yet" and "already handled in a previous attempt".
     */
    @Status
    @GET
    @Path(STATUS_FOR_ASYNC)
    public Response statusForAsync(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int totalCalls = asyncStatusCallCounts
                .computeIfAbsent(uid, k -> new AtomicInteger())
                .incrementAndGet();
        ParticipantStatus ps = asyncCompensateCalled.contains(uid)
                ? ParticipantStatus.Compensated
                : ParticipantStatus.Active;
        log.info("STATUS-FOR-ASYNC for lraId={} (call #{}) → {}", lraId, totalCalls, ps);
        return Response.ok(ps.name()).build();
    }

    /** Always returns 409 Conflict to force the LRA into FailedToCancel state. */
    @Compensate
    @PUT
    @Path(COMPENSATE_FAIL)
    public Response compensateFail(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("COMPENSATE-FAIL called for lraId={}, returning 409", lraId);
        return Response.status(Response.Status.CONFLICT)
                .entity(ParticipantStatus.FailedToCompensate.name())
                .build();
    }

    /** Returns 503 on the first call and 200 on subsequent calls, simulating a temporary failure. */
    @Compensate
    @PUT
    @Path(COMPENSATE_UNREACHABLE)
    public Response compensateUnreachable(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int call = unreachableCallCounts
                .computeIfAbsent(uid, k -> new AtomicInteger())
                .incrementAndGet();

        if (call == 1) {
            log.warn("COMPENSATE-UNREACHABLE: simulating crash on first call for lraId={}", lraId);
            return Response.status(503).build();
        }
        log.info("COMPENSATE-UNREACHABLE: recovered on call #{} for lraId={}", call, lraId);
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

    /**
     * Idempotent complete — safe to call multiple times.
     * Shares {@code idempotentCallCounts} and {@code idempotentWorkDone} with
     * {@link #compensateIdempotent}; safe because each test uses a unique lraId.
     */
    @Complete
    @PUT
    @Path(COMPLETE_IDEMPOTENT)
    public Response completeIdempotent(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int totalCalls = idempotentCallCounts
                .computeIfAbsent(uid, k -> new AtomicInteger())
                .incrementAndGet();
        boolean firstTime = idempotentWorkDone.add(uid);
        if (firstTime) {
            log.info("COMPLETE-IDEMPOTENT: doing work for lraId={} (call #{})", lraId, totalCalls);
        } else {
            log.info("COMPLETE-IDEMPOTENT: skipping duplicate work for lraId={} (call #{})", lraId, totalCalls);
        }
        return Response.ok(ParticipantStatus.Completed.name()).build();
    }

    /**
     * Async complete — returns 202 Accepted immediately and records the call.
     * The coordinator must poll {@link #statusForAsyncComplete} to learn when completion is done.
     */
    @Complete
    @PUT
    @Path(COMPLETE_ASYNC)
    public Response completeAsync(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int totalCalls = asyncCallCounts
                .computeIfAbsent(uid, k -> new AtomicInteger())
                .incrementAndGet();
        asyncCompleteCalled.add(uid);
        log.info("COMPLETE-ASYNC called for lraId={} (call #{}), returning 202", lraId, totalCalls);
        return Response.accepted().build();
    }

    /**
     * Status endpoint for the async complete path.
     * Reports Active until {@link #completeAsync} has been called, then Completed.
     */
    @Status
    @GET
    @Path(STATUS_FOR_ASYNC_COMPLETE)
    public Response statusForAsyncComplete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int totalCalls = asyncStatusCallCounts
                .computeIfAbsent(uid, k -> new AtomicInteger())
                .incrementAndGet();
        ParticipantStatus ps = asyncCompleteCalled.contains(uid)
                ? ParticipantStatus.Completed
                : ParticipantStatus.Active;
        log.info("STATUS-FOR-ASYNC-COMPLETE for lraId={} (call #{}) → {}", lraId, totalCalls, ps);
        return Response.ok(ps.name()).build();
    }

    /** Always returns 409 Conflict to force the LRA into FailedToComplete state. */
    @Complete
    @PUT
    @Path(COMPLETE_FAIL)
    public Response completeFail(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.info("COMPLETE-FAIL called for lraId={}, returning 409", lraId);
        return Response.status(Response.Status.CONFLICT)
                .entity(ParticipantStatus.FailedToComplete.name())
                .build();
    }

    /** Returns 503 on the first call and 200 on subsequent calls, simulating a temporary failure. */
    @Complete
    @PUT
    @Path(COMPLETE_UNREACHABLE)
    public Response completeUnreachable(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String uid = lraId.toASCIIString();
        int call = unreachableCallCounts
                .computeIfAbsent(uid, k -> new AtomicInteger())
                .incrementAndGet();
        if (call == 1) {
            log.warn("COMPLETE-UNREACHABLE: simulating crash on first call for lraId={}", lraId);
            return Response.status(503).build();
        }
        log.info("COMPLETE-UNREACHABLE: recovered on call #{} for lraId={}", call, lraId);
        return Response.ok(ParticipantStatus.Completed.name()).build();
    }

    /**
     * Returns the total number of times an idempotent endpoint ({@link #compensateIdempotent}
     * or {@link #completeIdempotent}) was called for the given LRA, including retries.
     */
    @GET
    @Path(IDEMPOTENT_CALL_COUNT)
    public int idempotentCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger count = idempotentCallCounts.get(lraId);
        return count == null ? 0 : count.get();
    }

    /**
     * Returns {@code 1} if the idempotent side effect was performed for the given LRA,
     * {@code 0} otherwise. Always {@code ≤ 1} for a correct idempotent implementation.
     */
    @GET
    @Path(IDEMPOTENT_WORK_DONE)
    public int idempotentWorkDone(@QueryParam("lraId") String lraId) {
        return idempotentWorkDone.contains(lraId) ? 1 : 0;
    }

    /**
     * Returns the total number of times an async endpoint ({@link #compensateAsync}
     * or {@link #completeAsync}) was called for the given LRA, including retries.
     */
    @GET
    @Path(ASYNC_CALL_COUNT)
    public int asyncCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger count = asyncCallCounts.get(lraId);
        return count == null ? 0 : count.get();
    }

    /**
     * Returns the total number of times a status endpoint was polled for the given LRA.
     */
    @GET
    @Path(ASYNC_STATUS_CALL_COUNT)
    public int asyncStatusCallCount(@QueryParam("lraId") String lraId) {
        AtomicInteger count = asyncStatusCallCounts.get(lraId);
        return count == null ? 0 : count.get();
    }

    /**
     * Resets all in-memory participant state. Call this at the start of each test
     * for a clean slate.
     */
    @POST
    @Path(RESET_PARTICIPANT_STATE)
    public Response resetParticipantState() {
        idempotentCallCounts.clear();
        idempotentWorkDone.clear();
        asyncCompensateCalled.clear();
        asyncCompleteCalled.clear();
        asyncCallCounts.clear();
        asyncStatusCallCounts.clear();
        unreachableCallCounts.clear();
        log.info("Participant state reset");
        return Response.ok().build();
    }
}
