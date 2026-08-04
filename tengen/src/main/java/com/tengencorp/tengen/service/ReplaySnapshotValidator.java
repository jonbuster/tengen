package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.RuleSnapshot;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleType;
import org.springframework.stereotype.Service;

/** Validates an immutable snapshot without attaching it to the live JPA context. */
@Service
public class ReplaySnapshotValidator {

    private final RuleValidationService validationService;

    public ReplaySnapshotValidator(RuleValidationService validationService) {
        this.validationService = validationService;
    }

    public void validate(RuleSnapshot snapshot) {
        if (snapshot == null || snapshot.ruleType() == null) {
            throw new IllegalArgumentException("Rule revision snapshot is incomplete");
        }
        if (snapshot.ruleType() != RuleType.CONDITION
                && snapshot.ruleType() != RuleType.AGGREGATE) {
            throw new IllegalArgumentException(
                "Replay supports CONDITION and AGGREGATE rule revisions only");
        }

        Rule candidate = new Rule();
        candidate.setName(snapshot.name());
        candidate.setRuleType(snapshot.ruleType());
        candidate.setAction(snapshot.action());
        candidate.setCallbackUrl(snapshot.callbackUrl());
        candidate.setCooldownSeconds(snapshot.cooldownSeconds());
        candidate.setTriggerMode(snapshot.triggerMode());
        candidate.setEventType(snapshot.eventType());
        candidate.setSource(snapshot.source());
        candidate.setConditionScript(snapshot.conditionScript());
        candidate.setWindowSeconds(snapshot.windowSeconds());
        candidate.setAggType(snapshot.aggType());
        candidate.setAggField(snapshot.aggField());
        candidate.setGroupBy(snapshot.groupBy());
        candidate.setThreshold(snapshot.threshold());
        candidate.setActive(snapshot.active());

        try {
            validationService.validate(candidate);
        } catch (IllegalArgumentException exception) {
            // Keep expressions, callback URLs, and event data out of the API error.
            throw new IllegalArgumentException("Rule revision snapshot failed current validation");
        }
    }
}
