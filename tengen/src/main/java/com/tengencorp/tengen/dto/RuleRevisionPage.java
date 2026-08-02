package com.tengencorp.tengen.dto;

import java.util.List;

/** Stable paginated response for rule revision history. */
public record RuleRevisionPage(
        List<RuleRevisionSummary> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
