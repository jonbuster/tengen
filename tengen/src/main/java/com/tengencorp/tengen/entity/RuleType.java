package com.tengencorp.tengen.entity;

/** Rule type determines how an event is matched. */
public enum RuleType {
    /** Simple match — fires when the Aviator condition evaluates to true. */
    CONDITION,
    /** Windowed match — fires when the condition is true AND the aggregate reaches the threshold. */
    AGGREGATE,
    /** Ordered two-to-five-step match completed within an event-time window. */
    SEQUENCE
}
