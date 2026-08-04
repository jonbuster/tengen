package com.tengencorp.tengen.dto;

import java.util.List;

public record ReplayJobPage(
        List<ReplayJobResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
