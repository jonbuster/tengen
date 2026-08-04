package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.EventResponse;
import com.tengencorp.tengen.entity.Event;

/** Producer response body and replay metadata for an accepted event request. */
public record EventIngestionResult(Object responseBody, boolean replayed,
                                   EventResponse fullResponse, Event event) {

    public EventIngestionResult(Object responseBody, boolean replayed, EventResponse fullResponse) {
        this(responseBody, replayed, fullResponse, null);
    }
}
