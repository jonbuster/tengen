package com.tengencorp.tengen.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompactEventResponseTest {

    @Test
    void projectsOnlyProducerSummaryFields() {
        EventResponse full = new EventResponse(
            Map.of("type", "payment"),
            "accepted",
            true,
            List.of("Large payment"),
            List.of("Large payment"),
            Map.of("Large payment", new AggregateResult("AGGREGATE", "SUM", 2500, 1000, 300, null)),
            Map.of(),
            List.of());

        CompactEventResponse compact = CompactEventResponse.from(full);

        assertThat(compact.status()).isEqualTo("accepted");
        assertThat(compact.matched()).isTrue();
        assertThat(compact.rules()).containsExactly("Large payment");
        assertThat(compact.queuedRules()).containsExactly("Large payment");
        assertThat(compact.suppressedRules()).isEmpty();
    }
}
