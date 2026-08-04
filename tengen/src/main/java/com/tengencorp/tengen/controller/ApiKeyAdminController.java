package com.tengencorp.tengen.controller;
import com.tengencorp.tengen.dto.ApiKeyRequest;
import com.tengencorp.tengen.dto.ApiKeyResponse;
import com.tengencorp.tengen.entity.ApiKey;
import com.tengencorp.tengen.exception.NotFoundException;
import com.tengencorp.tengen.helper.LogSafe;
import com.tengencorp.tengen.repository.ApiKeyRepository;
import com.tengencorp.tengen.service.ApiKeyService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin CRUD for API keys, protected by JWT (see SecurityConfig).
 */
@RestController
@RequestMapping("/api/keys")
public class ApiKeyAdminController {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAdminController.class);

    private final ApiKeyService apiKeyService;
    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyAdminController(ApiKeyService apiKeyService, ApiKeyRepository apiKeyRepository) {
        this.apiKeyService = apiKeyService;
        this.apiKeyRepository = apiKeyRepository;
    }

    @GetMapping
    public List<ApiKeyResponse> list() {
        return apiKeyRepository.findAll().stream().map(ApiKeyResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<ApiKeyResponse> create(@Valid @RequestBody ApiKeyRequest request) {
        ApiKeyService.CreatedKey created = apiKeyService.create(
            request.name(), request.allowedEventTypes(), request.allowedSources(), request.expiresAt(),
            request.responseMode());
        log.info("event=admin_mutation action=api_key_create entity=api_key entityId={} actor={} responseMode={}",
            created.key().getId(), LogSafe.text(actor()), created.key().getEffectiveResponseMode());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiKeyResponse.created(created));
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<ApiKeyResponse> revoke(@PathVariable Long id) {
        apiKeyService.revoke(id);
        ApiKey key = apiKeyRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("API key " + id + " not found"));
        log.info("event=admin_mutation action=api_key_revoke entity=api_key entityId={} actor={} active={}",
            id, LogSafe.text(actor()), key.isActive());
        return ResponseEntity.ok(ApiKeyResponse.from(key));
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getName() != null
            ? authentication.getName() : "system";
    }
}
