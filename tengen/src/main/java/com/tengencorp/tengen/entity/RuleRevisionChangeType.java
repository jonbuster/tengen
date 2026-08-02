package com.tengencorp.tengen.entity;

/** Lifecycle operation recorded in the immutable rule history. */
public enum RuleRevisionChangeType {
    CREATED,
    UPDATED,
    ACTIVATED,
    DEACTIVATED,
    ARCHIVED,
    UNARCHIVED,
    RESTORED
}
