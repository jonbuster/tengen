package com.tengencorp.tengen.controller;

import com.tengencorp.tengen.dto.NotificationConnectionTestResponse;
import com.tengencorp.tengen.dto.NotificationDestinationRequest;
import com.tengencorp.tengen.dto.NotificationDestinationResponse;
import com.tengencorp.tengen.entity.NotificationChannel;
import com.tengencorp.tengen.service.NotificationDestinationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** JWT-protected admin API for reusable email and SMS provider connections. */
@RestController
@RequestMapping("/api/notification-destinations")
public class NotificationDestinationController {

    private final NotificationDestinationService service;

    public NotificationDestinationController(NotificationDestinationService service) {
        this.service = service;
    }

    @GetMapping
    public List<NotificationDestinationResponse> list(
            @RequestParam(required = false) NotificationChannel channel) {
        return service.list(channel);
    }

    @PostMapping
    public ResponseEntity<NotificationDestinationResponse> create(
            @Valid @RequestBody NotificationDestinationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PostMapping("/{id}/test")
    public NotificationConnectionTestResponse test(@PathVariable Long id) {
        return service.test(id);
    }
}
