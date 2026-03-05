package io.narayana.lra.ha.participants;

public enum InjectPoint {
    START,
    JOIN_BEFORE_SAVE,
    JOIN_AFTER_SAVE,
    CLOSE,
    CANCEL
}
