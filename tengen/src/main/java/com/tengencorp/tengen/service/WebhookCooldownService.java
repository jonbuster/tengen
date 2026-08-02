package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleActionState;
import com.tengencorp.tengen.repository.RuleActionStateRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Coordinates durable, per-rule webhook cooldown state. */
@Service
public class WebhookCooldownService {

    private static final String GLOBAL_SCOPE = "";

    private final RuleActionStateRepository stateRepository;

    public WebhookCooldownService(RuleActionStateRepository stateRepository) {
        this.stateRepository = stateRepository;
    }

    /** Ensures the row exists, then locks it for the duration of delivery. */
    public RuleActionState lockState(Rule rule, String groupKey) {
        String scopeKey = groupKey != null ? groupKey : GLOBAL_SCOPE;
        stateRepository.ensureExists(rule.getId(), scopeKey);
        return stateRepository.findForUpdate(rule.getId(), scopeKey)
            .orElseThrow(() -> new IllegalStateException("Webhook cooldown state was not created"));
    }

    public boolean isSuppressed(RuleActionState state, int cooldownSeconds, Instant now) {
        Instant lastDelivery = state.getLastSuccessfulDeliveryAt();
        return lastDelivery != null && lastDelivery.plusSeconds(cooldownSeconds).isAfter(now);
    }

    public void recordSuccessfulDelivery(RuleActionState state, Instant deliveredAt) {
        state.setLastSuccessfulDeliveryAt(deliveredAt);
    }
}
