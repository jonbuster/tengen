package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleActionState;
import com.tengencorp.tengen.entity.RuleActionWindow;
import com.tengencorp.tengen.repository.RuleActionStateRepository;
import com.tengencorp.tengen.repository.RuleActionWindowRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Coordinates durable, per-rule webhook cooldown and trigger state. */
@Service
public class WebhookCooldownService {

    private static final String GLOBAL_SCOPE = "";

    private final RuleActionStateRepository stateRepository;
    private final RuleActionWindowRepository windowRepository;

    public WebhookCooldownService(RuleActionStateRepository stateRepository,
                                  RuleActionWindowRepository windowRepository) {
        this.stateRepository = stateRepository;
        this.windowRepository = windowRepository;
    }

    /** Ensures the row exists, then locks it for trigger eligibility and reservation. */
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

    public boolean isEdgeSuppressed(RuleActionState state) {
        return state.wasLastMatched();
    }

    public void recordSuccessfulEdgeDelivery(RuleActionState state) {
        state.setLastMatched(true);
    }

    public void resetEdgeState(RuleActionState state) {
        state.setLastMatched(false);
    }

    /** Ensures the window row exists, then locks it for trigger eligibility and reservation. */
    public RuleActionWindow lockWindow(Rule rule, String groupKey, Instant windowStart) {
        String scopeKey = groupKey != null ? groupKey : GLOBAL_SCOPE;
        windowRepository.ensureExists(rule.getId(), scopeKey, windowStart);
        return windowRepository.findForUpdate(rule.getId(), scopeKey, windowStart)
            .orElseThrow(() -> new IllegalStateException("Once-per-window state was not created"));
    }

    public boolean isWindowDelivered(RuleActionWindow state) {
        return state.getDeliveredAt() != null;
    }

    public void recordWindowDelivery(RuleActionWindow state, Instant deliveredAt) {
        state.setDeliveredAt(deliveredAt);
    }
}
