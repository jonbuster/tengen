package com.tengencorp.tengen.dto;

import java.util.List;

/** Stable page response for the Event Explorer list API. */
public record EventHistoryPage(
        List<EventHistorySummary> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
