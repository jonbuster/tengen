package com.tengencorp.tengen.controller;

import com.tengencorp.tengen.dto.ReplayJobCreateRequest;
import com.tengencorp.tengen.dto.ReplayJobPage;
import com.tengencorp.tengen.dto.ReplayJobOutcomePage;
import com.tengencorp.tengen.dto.ReplayJobResponse;
import com.tengencorp.tengen.dto.ReplayJobTransitionResponse;
import com.tengencorp.tengen.service.ReplayJobControlService;
import com.tengencorp.tengen.service.ReplayJobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** JWT-protected admin API for analysis-only historical replay jobs. */
@RestController
@RequestMapping("/api/replay-jobs")
public class ReplayJobController {

    private final ReplayJobService replayJobService;
    private final ReplayJobControlService controlService;

    public ReplayJobController(ReplayJobService replayJobService,
                               ReplayJobControlService controlService) {
        this.replayJobService = replayJobService;
        this.controlService = controlService;
    }

    @PostMapping
    public ResponseEntity<ReplayJobResponse> create(
            @Valid @RequestBody ReplayJobCreateRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(replayJobService.create(request, actor()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReplayJobResponse> get(@PathVariable Long id) {
        ReplayJobResponse response = replayJobService.get(id);
        return withEtag(response);
    }

    @GetMapping
    public ReplayJobPage list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) Integer ruleRevision,
            @RequestParam(required = false) String createdBy,
            @RequestParam(required = false) Long jobId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return replayJobService.list(page, size, status, ruleId, ruleRevision,
            createdBy, jobId, from, to);
    }

    @GetMapping("/{id}/transitions")
    public List<ReplayJobTransitionResponse> transitions(@PathVariable Long id) {
        return replayJobService.transitions(id);
    }

    @GetMapping("/{id}/outcomes")
    public ReplayJobOutcomePage outcomes(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) Boolean matched) {
        return replayJobService.outcomes(id, page, size, matched);
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<ReplayJobResponse> pause(
            @PathVariable Long id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return withEtag(controlService.pause(id, ifMatch, actor()));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<ReplayJobResponse> resume(
            @PathVariable Long id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return withEtag(controlService.resume(id, ifMatch, actor()));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ReplayJobResponse> cancel(
            @PathVariable Long id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return withEtag(controlService.cancel(id, ifMatch, actor()));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<ReplayJobResponse> retry(
            @PathVariable Long id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return withEtag(controlService.retry(id, ifMatch, actor()));
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getName() != null
            ? authentication.getName() : "system";
    }

    private ResponseEntity<ReplayJobResponse> withEtag(ReplayJobResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setETag("\"" + response.version() + "\"");
        return ResponseEntity.ok().headers(headers).body(response);
    }
}
