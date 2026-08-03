package com.tengencorp.tengen.entity;

/** Action result recorded for a logically matched rule. */
public enum EventRuleActionOutcome {
    LOG_ONLY,
    WEBHOOK_QUEUED,
    WEBHOOK_SUPPRESSED
}
