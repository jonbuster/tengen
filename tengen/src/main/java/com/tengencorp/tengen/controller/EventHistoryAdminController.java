package com.tengencorp.tengen.controller;

import com.tengencorp.tengen.dto.EventHistoryDetail;
import com.tengencorp.tengen.dto.EventHistoryPage;
import com.tengencorp.tengen.service.EventHistoryAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** JWT-protected admin API for inspecting ingested events and their traces. */
@RestController
@RequestMapping("/api/event-history")
public class EventHistoryAdminController {

    private final EventHistoryAdminService eventHistoryAdminService;

    public EventHistoryAdminController(EventHistoryAdminService eventHistoryAdminService) {
        this.eventHistoryAdminService = eventHistoryAdminService;
    }

    @GetMapping
    public EventHistoryPage list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) Long eventId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long apiKeyId,
            @RequestParam(required = false) Boolean matched,
            @RequestParam(required = false) Boolean traceAvailable,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return eventHistoryAdminService.list(
            page, size, eventId, type, source, apiKeyId, matched, traceAvailable, from, to);
    }

    @GetMapping("/{id}")
    public EventHistoryDetail get(@PathVariable Long id) {
        return eventHistoryAdminService.get(id);
    }
}
