package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.EventRequest;
import com.tengencorp.tengen.entity.ApiKey;
import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.EventTimeStatus;
import com.tengencorp.tengen.entity.RabbitMqConnector;
import com.tengencorp.tengen.repository.ApiKeyRepository;
import com.tengencorp.tengen.repository.EventIdempotencyRepository;
import com.tengencorp.tengen.repository.EventRepository;
import com.tengencorp.tengen.repository.EventRuleOutcomeRepository;
import com.tengencorp.tengen.repository.RuleRepository;
import com.tengencorp.tengen.helper.EventRequestHasher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventServiceWatermarkTest {

    @Test
    void missingOptOutKeepsTheDefaultWatermarkBehavior() {
        Fixture fixture = fixture();
        when(fixture.watermarkService.classify(any(), any(), any()))
            .thenReturn(new EventTimeDecision(EventTimeStatus.ON_TIME, null));

        EventIngestionResult result = fixture.service.processRabbitMq(
            request(), 1L, new RabbitMqConnector());

        verify(fixture.watermarkService).classify(eq("payment"), eq("billing"), any());
        assertThat(result.event().getWatermarkApplied()).isTrue();
        assertThat(result.event().getEventTimeStatus()).isEqualTo(EventTimeStatus.ON_TIME);
    }

    @Test
    void explicitOptOutSkipsWatermarkingButStillProcessesTheEvent() {
        Fixture fixture = fixture();

        EventIngestionResult result = fixture.service.processRabbitMq(
            request(), 1L, new RabbitMqConnector(), false);

        verifyNoInteractions(fixture.watermarkService);
        assertThat(result.event().getWatermarkApplied()).isFalse();
        assertThat(result.event().getEventTimeStatus()).isNull();
        verify(fixture.ruleRepository).findActiveRulesForEvent(
            eq("payment"), eq("billing"), any(), any());
    }

    private Fixture fixture() {
        EventRepository eventRepository = mock(EventRepository.class);
        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
        ApiKey apiKey = new ApiKey();
        apiKey.setId(1L);
        when(apiKeyRepository.findById(1L)).thenReturn(Optional.of(apiKey));

        RuleRepository ruleRepository = mock(RuleRepository.class);
        when(ruleRepository.findActiveRulesForEvent(any(), any(), any(), any())).thenReturn(List.of());

        EventWatermarkService watermarkService = mock(EventWatermarkService.class);
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        when(apiKeyService.isValid(any(ApiKey.class), any(Event.class))).thenReturn(true);

        EventService service = new EventService(
            eventRepository,
            apiKeyRepository,
            ruleRepository,
            mock(RuleEngine.class),
            mock(AbsenceRuleService.class),
            mock(WebhookOutboxService.class),
            mock(WebhookCooldownService.class),
            apiKeyService,
            mock(EventIdempotencyRepository.class),
            mock(EventRequestHasher.class),
            mock(ObjectMapper.class),
            new SimpleMeterRegistry(),
            300,
            mock(EventRuleOutcomeRepository.class),
            watermarkService);
        return new Fixture(service, ruleRepository, watermarkService);
    }

    private EventRequest request() {
        return new EventRequest("payment", "billing", null, Map.of("amount", 100));
    }

    private record Fixture(EventService service,
                           RuleRepository ruleRepository,
                           EventWatermarkService watermarkService) {
    }
}
