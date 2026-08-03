package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.EventResponse;

/** Producer response body and replay metadata for an accepted event request. */
public record EventIngestionResult(Object responseBody, boolean replayed,
                                   EventResponse fullResponse) {
}
