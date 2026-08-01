package com.tengencorp.tengen.controller;
import com.tengencorp.tengen.dto.RuleRequest;
import com.tengencorp.tengen.dto.RuleResponse;
import com.tengencorp.tengen.dto.RuleResult;
import com.tengencorp.tengen.dto.RuleTestRequest;
import com.tengencorp.tengen.dto.RuleTestResponse;
import com.tengencorp.tengen.exception.NotFoundException;
import com.tengencorp.tengen.helper.EventJsonParser;

import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.repository.RuleRepository;
import com.tengencorp.tengen.service.RuleEngine;
import com.tengencorp.tengen.dto.RuleEvaluation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * REST admin API for rule management, replacing the Thymeleaf MVC controller.
 * All responses are JSON; mutations are protected by JWT (see SecurityConfig).
 */
@RestController
@RequestMapping("/api/rules")
public class RuleAdminController {

    private final RuleRepository ruleRepository;
    private final RuleEngine ruleEngine;
    private final EventJsonParser eventJsonParser;

    public RuleAdminController(RuleRepository ruleRepository, RuleEngine ruleEngine,
                               EventJsonParser eventJsonParser) {
        this.ruleRepository = ruleRepository;
        this.ruleEngine = ruleEngine;
        this.eventJsonParser = eventJsonParser;
    }

    @GetMapping
    public List<RuleResponse> list() {
        return ruleRepository.findAll().stream().map(RuleResponse::from).toList();
    }

    @GetMapping("/{id}")
    public RuleResponse get(@PathVariable Long id) {
        return RuleResponse.from(find(id));
    }

    @PostMapping
    public ResponseEntity<RuleResponse> create(@Valid @RequestBody RuleRequest request) {
        if (ruleRepository.existsByName(request.name())) {
            throw new IllegalArgumentException("A rule named '" + request.name() + "' already exists");
        }
        Rule rule = ruleRepository.save(request.toEntity());
        return ResponseEntity.status(HttpStatus.CREATED).body(RuleResponse.from(rule));
    }

    @PutMapping("/{id}")
    public RuleResponse update(@PathVariable Long id, @Valid @RequestBody RuleRequest request) {
        Rule rule = find(id);
        request.applyTo(rule);
        return RuleResponse.from(ruleRepository.save(rule));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        ruleRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/toggle")
    public RuleResponse toggle(@PathVariable Long id) {
        Rule rule = find(id);
        rule.setActive(!rule.isActive());
        return RuleResponse.from(ruleRepository.save(rule));
    }

    @PostMapping("/test")
    public RuleTestResponse runTest(@Valid @RequestBody RuleTestRequest request) {
        Event event = eventJsonParser.parse(request.eventJson());

        if ("all".equals(request.mode())) {
            List<RuleResult> results = new ArrayList<>();
            boolean anyMatched = false;
            for (Rule rule : ruleRepository.findByActiveTrueOrderByNameAsc()) {
                RuleEvaluation evaluation = ruleEngine.test(event, rule);
                boolean matched = evaluation.matched(rule);
                anyMatched = anyMatched || matched;
                results.add(new RuleResult(
                    rule.getId(),
                    rule.getName(),
                    rule.getRuleType(),
                    rule.getAction(),
                    matched,
                    evaluation.conditionMatched(),
                    evaluation.aggregateValue(),
                    rule.getThreshold(),
                    rule.getWindowSeconds()));
            }
            return RuleTestResponse.all(results, anyMatched, event);
        }

        if (request.ruleId() == null) {
            throw new IllegalArgumentException("ruleId is required in single mode");
        }
        Rule rule = find(request.ruleId());
        RuleEvaluation evaluation = ruleEngine.test(event, rule);
        return RuleTestResponse.single(
            rule,
            evaluation.matched(rule),
            evaluation.conditionMatched(),
            evaluation.aggregateValue(),
            event);
    }

    private Rule find(Long id) {
        return ruleRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Rule " + id + " not found"));
    }
}
