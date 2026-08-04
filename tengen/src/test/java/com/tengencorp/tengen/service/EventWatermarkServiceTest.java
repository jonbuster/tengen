package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.EventStreamWatermark;
import com.tengencorp.tengen.entity.EventTimeStatus;
import com.tengencorp.tengen.repository.EventStreamWatermarkRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EventWatermarkServiceTest {

    private static final Instant MAX_OCCURRED = Instant.parse("2026-08-03T10:10:00Z");
    private static final Instant WATERMARK = Instant.parse("2026-08-03T10:05:00Z");

    @Test
    void firstEventIsOnTimeAndCreatesNoPriorWatermarkSnapshot() {
        EventStreamWatermark state = state(MAX_OCCURRED, WATERMARK);
        EventStreamWatermarkRepository repository = proxy(1, state);

        EventTimeDecision decision = service(repository, 300).classify(
            "payment", "billing", MAX_OCCURRED);

        assertThat(decision.status()).isEqualTo(EventTimeStatus.ON_TIME);
        assertThat(decision.watermarkAtDecision()).isNull();
    }

    @Test
    void exactWatermarkBoundaryIsTooLate() {
        EventStreamWatermark state = state(MAX_OCCURRED, WATERMARK);
        EventStreamWatermarkRepository repository = proxy(0, state);

        EventTimeDecision decision = service(repository, 300).classify(
            "payment", "billing", WATERMARK);

        assertThat(decision.status()).isEqualTo(EventTimeStatus.TOO_LATE);
        assertThat(decision.watermarkAtDecision()).isEqualTo(WATERMARK);
        assertThat(state.getMaxOccurredAt()).isEqualTo(MAX_OCCURRED);
    }

    @Test
    void eventInsideGracePeriodIsLateAccepted() {
        EventStreamWatermark state = state(MAX_OCCURRED, WATERMARK);
        EventStreamWatermarkRepository repository = proxy(0, state);

        EventTimeDecision decision = service(repository, 300).classify(
            "payment", "billing", Instant.parse("2026-08-03T10:07:00Z"));

        assertThat(decision.status()).isEqualTo(EventTimeStatus.LATE_ACCEPTED);
        assertThat(state.getMaxOccurredAt()).isEqualTo(MAX_OCCURRED);
        assertThat(state.getWatermarkAt()).isEqualTo(WATERMARK);
    }

    @Test
    void newerEventAdvancesMaximumAndWatermark() {
        EventStreamWatermark state = state(MAX_OCCURRED, WATERMARK);
        EventStreamWatermarkRepository repository = proxy(0, state);

        EventTimeDecision decision = service(repository, 300).classify(
            "payment", "billing", Instant.parse("2026-08-03T10:20:00Z"));

        assertThat(decision.status()).isEqualTo(EventTimeStatus.ON_TIME);
        assertThat(decision.watermarkAtDecision()).isEqualTo(WATERMARK);
        assertThat(state.getMaxOccurredAt()).isEqualTo(Instant.parse("2026-08-03T10:20:00Z"));
        assertThat(state.getWatermarkAt()).isEqualTo(Instant.parse("2026-08-03T10:15:00Z"));
    }

    @Test
    void increasingGracePeriodDoesNotReopenAnAlreadyClosedWatermark() {
        EventStreamWatermark state = state(MAX_OCCURRED, WATERMARK);
        EventStreamWatermarkRepository repository = proxy(0, state);

        EventTimeDecision decision = service(repository, 900).classify(
            "payment", "billing", Instant.parse("2026-08-03T10:04:00Z"));

        assertThat(decision.status()).isEqualTo(EventTimeStatus.TOO_LATE);
        assertThat(decision.watermarkAtDecision()).isEqualTo(WATERMARK);
        assertThat(state.getWatermarkAt()).isEqualTo(WATERMARK);
    }

    @Test
    void idleAdvancementMovesWatermarkWithoutInventingObservedEventTime() {
        EventStreamWatermark state = state(null, WATERMARK);
        EventStreamWatermarkRepository repository = proxy(0, state);

        Instant advanced = service(repository, 300).advanceIdle(
            "payment.completed", "billing", Instant.parse("2026-08-03T10:20:00Z"));

        assertThat(advanced).isEqualTo(Instant.parse("2026-08-03T10:15:00Z"));
        assertThat(state.getMaxOccurredAt()).isNull();
    }

    private EventWatermarkService service(EventStreamWatermarkRepository repository,
                                          long allowedLatenessSeconds) {
        return new EventWatermarkService(repository, allowedLatenessSeconds);
    }

    private EventStreamWatermarkRepository proxy(int ensureResult, EventStreamWatermark state) {
        return (EventStreamWatermarkRepository) Proxy.newProxyInstance(
            EventStreamWatermarkRepository.class.getClassLoader(),
            new Class<?>[] {EventStreamWatermarkRepository.class},
            (ignored, method, arguments) -> switch (method.getName()) {
                case "ensureExists" -> ensureResult;
                case "ensureIdleExists" -> 0;
                case "findForUpdate" -> Optional.of(state);
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private EventStreamWatermark state(Instant maxOccurredAt, Instant watermarkAt) {
        return new EventStreamWatermark("payment", "billing", maxOccurredAt, watermarkAt,
            Instant.parse("2026-08-03T10:00:00Z"));
    }
}
