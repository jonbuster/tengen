package com.tengencorp.tengen.controller;

import com.tengencorp.tengen.dto.WebhookDeliveryDetail;
import com.tengencorp.tengen.dto.WebhookDeliveryPage;
import com.tengencorp.tengen.service.WebhookDeliveryAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** JWT-protected admin API for inspecting and retrying webhook deliveries. */
@RestController
@RequestMapping("/api/webhook-deliveries")
public class WebhookDeliveryAdminController {

    private final WebhookDeliveryAdminService deliveryAdminService;

    public WebhookDeliveryAdminController(WebhookDeliveryAdminService deliveryAdminService) {
        this.deliveryAdminService = deliveryAdminService;
    }

    @GetMapping
    public WebhookDeliveryPage list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) Long eventId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String search) {
        return deliveryAdminService.list(page, size, status, ruleId, eventId, from, to, search);
    }

    @GetMapping("/{id}")
    public WebhookDeliveryDetail get(@PathVariable Long id) {
        return deliveryAdminService.get(id);
    }

    @PostMapping("/{id}/retry")
    public WebhookDeliveryDetail retry(@PathVariable Long id) {
        return deliveryAdminService.retry(id);
    }
}
