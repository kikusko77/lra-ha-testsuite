package io.narayana.lra.ha.participants;

/**
 * Participant endpoint path segments shared across all IT test classes.
 *
 * Each constant names a sub-path that exists on one or more participant
 * resources. The concrete resource is selected by the IT subclass via
 * {@link TestBase#participantPath()}, so the same constant (e.g. {@code COMPENSATE})
 * resolves to a different URL depending on which participant is under test.
 *
 * {@link TestBase} implements this interface so subclasses can reference every
 * constant unqualified (e.g. {@code COMPENSATE} rather than
 * {@code ParticipantEndpoints.COMPENSATE}).
 */
interface ParticipantEndpoints {

    // -- Core lifecycle ---------------------------------------------------------
    String COMPENSATE = "compensate";
    String COMPLETE = "complete";
    String COMPENSATE_IDEMPOTENT = "compensate-idempotent";
    String COMPLETE_IDEMPOTENT = "complete-idempotent";
    String COMPENSATE_ASYNC = "compensate-async";
    String COMPLETE_ASYNC = "complete-async";
    String COMPENSATE_FAIL = "compensate-fail";
    String COMPLETE_FAIL = "complete-fail";
    String COMPENSATE_UNREACHABLE = "compensate-unreachable";
    String COMPLETE_UNREACHABLE = "complete-unreachable";
    String FORGET = "forget";

    // -- @Status endpoints (CompensateIT / CompleteIT) --------------------------
    String STATUS_FOR_ASYNC = "status-for-async";
    String STATUS_FOR_ASYNC_COMPLETE = "status-for-async-complete";
    String STATUS_FOR_FORGET_COMPENSATE = "status-for-forget-compensate";
    String STATUS_FOR_FORGET_COMPLETE = "status-for-forget-complete";

    // -- @Status endpoints (StatusIT) ------------------------------------------
    String COMPENSATE_ASYNC_STATUS = "compensate-async";
    String COMPLETE_ASYNC_STATUS = "complete-async";
    String STATUS_GONE = "status-gone";
    String STATUS_INTERMEDIATE_COMPENSATE = "status-intermediate-compensate";
    String STATUS_INTERMEDIATE_COMPLETE = "status-intermediate-complete";

    // -- @AfterLRA endpoints (AfterLraIT) --------------------------------------
    String AFTER_LRA = "after";
    String AFTER_LRA_IDEMPOTENT = "after-idempotent";

    // -- Diagnostic endpoints --------------------------------------------------
    String IDEMPOTENT_CALL_COUNT = "idempotent-call-count";
    String IDEMPOTENT_WORK_DONE = "idempotent-work-done";
    String ASYNC_CALL_COUNT = "async-call-count";
    String ASYNC_STATUS_CALL_COUNT = "async-status-call-count";
    String FORGET_CALL_COUNT = "forget-call-count";
    String ASYNC_COMPENSATE_CALL_COUNT = "async-compensate-call-count";
    String ASYNC_COMPLETE_CALL_COUNT = "async-complete-call-count";
    String STATUS_GONE_CALL_COUNT = "status-gone-call-count";
    String STATUS_INTERMEDIATE_COMPENSATE_CALL_COUNT = "status-intermediate-compensate-call-count";
    String STATUS_INTERMEDIATE_COMPLETE_CALL_COUNT = "status-intermediate-complete-call-count";
    String AFTER_STATUS = "after-status";
    String AFTER_CALL_COUNT = "after-call-count";
    String AFTER_IDEMPOTENT_CALL_COUNT = "after-idempotent-call-count";
    String AFTER_WORK_DONE = "after-work-done";
}
