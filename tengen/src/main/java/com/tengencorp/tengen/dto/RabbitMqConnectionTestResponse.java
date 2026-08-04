package com.tengencorp.tengen.dto;

import java.time.Instant;

public record RabbitMqConnectionTestResponse(
        boolean successful,
        String category,
        String message,
        Instant testedAt,
        long configurationVersion) {
}
