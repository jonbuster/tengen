package com.tengencorp.tengen.entity;

public enum ReplayJobStatus {
    QUEUED,
    RUNNING,
    PAUSE_REQUESTED,
    PAUSED,
    CANCEL_REQUESTED,
    CANCELLED,
    COMPLETED,
    FAILED
}
