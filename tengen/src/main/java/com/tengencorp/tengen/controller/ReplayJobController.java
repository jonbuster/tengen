package com.tengencorp.tengen.controller;

import com.tengencorp.tengen.dto.ReplayJobCreateRequest;
import com.tengencorp.tengen.dto.ReplayJobOutcomePage;
import com.tengencorp.tengen.dto.ReplayJobResponse;
import com.tengencorp.tengen.service.ReplayJobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** JWT-protected admin API for analysis-only historical replay jobs. */
@RestController
@RequestMapping("/api/replay-jobs")
public class ReplayJobController {

    private final ReplayJobService replayJobService;

    public ReplayJobController(ReplayJobService replayJobService) {
        this.replayJobService = replayJobService;
    }

    @PostMapping
    public ResponseEntity<ReplayJobResponse> create(
            @Valid @RequestBody ReplayJobCreateRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(replayJobService.create(request, actor()));
    }

    @GetMapping("/{id}")
    public ReplayJobResponse get(@PathVariable Long id) {
        return replayJobService.get(id);
    }

    @GetMapping("/{id}/outcomes")
    public ReplayJobOutcomePage outcomes(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) Boolean matched) {
        return replayJobService.outcomes(id, page, size, matched);
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getName() != null
            ? authentication.getName() : "system";
    }
}
