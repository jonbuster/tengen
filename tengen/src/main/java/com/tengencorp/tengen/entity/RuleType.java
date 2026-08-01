package com.tengencorp.tengen.entity;

/**
 * Rule type determines whether a windowed aggregate is evaluated.
 */
public enum RuleType {
    /** Simple match — fires when the Aviator condition evaluates to true. */
    CONDITION,
    /** Windowed match — fires when the condition is true AND the aggregate reaches the threshold. */
    AGGREGATE
}
