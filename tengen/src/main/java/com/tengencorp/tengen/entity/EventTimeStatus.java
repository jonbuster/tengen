package com.tengencorp.tengen.entity;

/** Event-time classification made before rule evaluation. */
public enum EventTimeStatus {
    ON_TIME,
    LATE_ACCEPTED,
    TOO_LATE
}
