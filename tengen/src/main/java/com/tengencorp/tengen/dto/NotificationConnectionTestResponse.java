package com.tengencorp.tengen.dto;

import java.time.Instant;

public record NotificationConnectionTestResponse(
        boolean successful,
        String category,
        String message,
        Instant testedAt) {
}
