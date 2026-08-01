package com.tengencorp.tengen.entity;

/**
 * Action taken when a rule matches.
 */
public enum RuleAction {
    /** Result is only returned in the API response. */
    LOG,
    /** Result is POSTed to the rule's callbackUrl, best-effort with retries. */
    WEBHOOK
}
