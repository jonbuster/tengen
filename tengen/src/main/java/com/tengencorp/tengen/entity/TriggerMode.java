package com.tengencorp.tengen.entity;

/** Controls when a webhook action fires after a rule matches. */
public enum TriggerMode {
    /** Deliver a webhook for every logical rule match. */
    EVERY_MATCH,
    /** Deliver a webhook only when the rule changes from non-matching to matching. */
    EDGE
}
