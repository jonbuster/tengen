package com.tengencorp.tengen.controller;

import com.tengencorp.tengen.dto.NotificationTemplateRequest;
import com.tengencorp.tengen.dto.NotificationTemplateResponse;
import com.tengencorp.tengen.entity.NotificationChannel;
import com.tengencorp.tengen.service.NotificationTemplateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** JWT-protected admin API for immutable email and SMS template versions. */
@RestController
@RequestMapping("/api/notification-templates")
public class NotificationTemplateController {

    private final NotificationTemplateService service;

    public NotificationTemplateController(NotificationTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public List<NotificationTemplateResponse> list(
            @RequestParam(required = false) NotificationChannel channel) {
        return service.list(channel);
    }

    @PostMapping
    public ResponseEntity<NotificationTemplateResponse> create(
            @Valid @RequestBody NotificationTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }
}
