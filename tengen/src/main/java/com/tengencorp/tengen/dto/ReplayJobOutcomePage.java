package com.tengencorp.tengen.dto;

import java.util.List;

public record ReplayJobOutcomePage(
        List<ReplayJobOutcomeResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
