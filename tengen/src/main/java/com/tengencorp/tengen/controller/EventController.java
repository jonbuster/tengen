package com.tengencorp.tengen.controller;
import com.tengencorp.tengen.dto.EventRequest;
import com.tengencorp.tengen.dto.EventResponse;

import com.tengencorp.tengen.security.ApiKeyPrincipal;
import com.tengencorp.tengen.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<EventResponse> ingest(@Valid @RequestBody EventRequest request,
                                                Authentication authentication) {
        Long apiKeyId = (authentication instanceof ApiKeyPrincipal principal) ? principal.getKeyId() : null;
        return ResponseEntity.ok(eventService.process(request, apiKeyId));
    }
}
