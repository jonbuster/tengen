package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.RuleRevision;
import com.tengencorp.tengen.entity.RuleRevisionChangeType;

import java.time.Instant;

/** Compact row used by the rule history list. */
public record RuleRevisionSummary(
        Long id,
        Long ruleId,
        int revision,
        RuleRevisionChangeType changeType,
        String actor,
        Instant changedAt,
        Integer restoredFromRevision) {

    public static RuleRevisionSummary from(RuleRevision revision) {
        return new RuleRevisionSummary(
            revision.getId(),
            revision.getRuleId(),
            revision.getRevision() != null ? revision.getRevision() : 1,
            revision.getChangeType(),
            revision.getActor(),
            revision.getChangedAt(),
            revision.getRestoredFromRevision());
    }
}
