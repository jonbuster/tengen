package com.tengencorp.tengen.dto;

/** Full immutable snapshot returned by the revision detail endpoint. */
public record RuleRevisionDetail(
        RuleRevisionSummary revision,
        int snapshotSchemaVersion,
        RuleSnapshot snapshot) {
}
