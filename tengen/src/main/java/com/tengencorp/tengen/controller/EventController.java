package com.tengencorp.tengen.controller;
import com.tengencorp.tengen.dto.EventRequest;

import com.tengencorp.tengen.security.ApiKeyPrincipal;
import com.tengencorp.tengen.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {

    public static final String IDEMPOTENCY_REPLAYED_HEADER = "X-Idempotency-Replayed";

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<Object> ingest(@Valid @RequestBody EventRequest request,
                                         Authentication authentication,
                                         @RequestHeader(value = "Idempotency-Key", required = false)
                                         String idempotencyKey) {
        Long apiKeyId = (authentication instanceof ApiKeyPrincipal principal) ? principal.getKeyId() : null;
        var result = eventService.processWithMetadata(request, apiKeyId, idempotencyKey);
        return ResponseEntity.ok()
            .header(IDEMPOTENCY_REPLAYED_HEADER, Boolean.toString(result.replayed()))
            .body(result.responseBody());
    }
}
