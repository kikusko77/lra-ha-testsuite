package io.narayana.lra.ha.participants;

/**
 * Endpoint path constants shared across the IT classes; each constant resolves to a
 * different concrete URL depending on which participant resource the subclass selects.
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

    // -- Status endpoints used by the async lifecycle suites --------------------
    String STATUS_FOR_ASYNC = "status-for-async";
    String STATUS_FOR_ASYNC_COMPLETE = "status-for-async-complete";
    String STATUS_FOR_FORGET_COMPENSATE = "status-for-forget-compensate";
    String STATUS_FOR_FORGET_COMPLETE = "status-for-forget-complete";

    // -- Status endpoints used by the dedicated polling suite -------------------
    String COMPENSATE_ASYNC_STATUS = "compensate-async";
    String COMPLETE_ASYNC_STATUS = "complete-async";
    String STATUS_GONE = "status-gone";
    String STATUS_INTERMEDIATE_COMPENSATE = "status-intermediate-compensate";
    String STATUS_INTERMEDIATE_COMPLETE = "status-intermediate-complete";

    // -- Post-terminal-state notification endpoints -----------------------------
    String AFTER_LRA = "after";

    // -- Diagnostic endpoints --------------------------------------------------
    String CALL_COUNT = "call-count";
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
}
