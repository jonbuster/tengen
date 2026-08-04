package com.tengencorp.tengen.controller;

import com.tengencorp.tengen.dto.RuleEvaluation;
import com.tengencorp.tengen.dto.RuleRequest;
import com.tengencorp.tengen.dto.RuleResponse;
import com.tengencorp.tengen.dto.RuleResult;
import com.tengencorp.tengen.dto.RuleRevisionDetail;
import com.tengencorp.tengen.dto.RuleRevisionPage;
import com.tengencorp.tengen.dto.RuleTestRequest;
import com.tengencorp.tengen.dto.RuleTestResponse;
import com.tengencorp.tengen.dto.SequenceTestResult;
import com.tengencorp.tengen.dto.AbsenceTestResult;
import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.helper.EventJsonParser;
import com.tengencorp.tengen.repository.RuleRepository;
import com.tengencorp.tengen.service.RuleEngine;
import com.tengencorp.tengen.service.RuleLifecycleService;
import com.tengencorp.tengen.service.SequenceRuleService;
import com.tengencorp.tengen.service.AbsenceRuleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** JWT-protected REST admin API for current rules and immutable revisions. */
@RestController
@RequestMapping("/api/rules")
public class RuleAdminController {

    private final RuleRepository ruleRepository;
    private final RuleEngine ruleEngine;
    private final EventJsonParser eventJsonParser;
    private final RuleLifecycleService lifecycleService;
    private final SequenceRuleService sequenceRuleService;
    private final AbsenceRuleService absenceRuleService;

    public RuleAdminController(RuleRepository ruleRepository, RuleEngine ruleEngine,
                               EventJsonParser eventJsonParser,
                               RuleLifecycleService lifecycleService,
                               SequenceRuleService sequenceRuleService,
                               AbsenceRuleService absenceRuleService) {
        this.ruleRepository = ruleRepository;
        this.ruleEngine = ruleEngine;
        this.eventJsonParser = eventJsonParser;
        this.lifecycleService = lifecycleService;
        this.sequenceRuleService = sequenceRuleService;
        this.absenceRuleService = absenceRuleService;
    }

    @GetMapping
    public List<RuleResponse> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        if (includeArchived) {
            return ruleRepository.findAll(org.springframework.data.domain.Sort.by("name"))
                .stream().map(RuleResponse::from).toList();
        }
        return ruleRepository.findByArchivedAtIsNullOrderByNameAsc()
            .stream().map(RuleResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<RuleResponse> get(@PathVariable Long id) {
        return withEtag(RuleResponse.from(lifecycleService.find(id)));
    }

    @PostMapping
    public ResponseEntity<RuleResponse> create(@Valid @RequestBody RuleRequest request) {
        RuleResponse response = lifecycleService.create(request, actor());
        return withEtag(ResponseEntity.status(HttpStatus.CREATED), response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<RuleResponse> update(
            @PathVariable Long id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody RuleRequest request) {
        return withEtag(lifecycleService.update(id, request, ifMatch, actor()));
    }

    /** DELETE is retained as a compatible route but now archives the rule. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        lifecycleService.archive(id, ifMatch, actor());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<RuleResponse> toggle(
            @PathVariable Long id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return withEtag(lifecycleService.toggle(id, ifMatch, actor()));
    }

    @PostMapping("/{id}/unarchive")
    public ResponseEntity<RuleResponse> unarchive(
            @PathVariable Long id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return withEtag(lifecycleService.unarchive(id, ifMatch, actor()));
    }

    @GetMapping("/{id}/revisions")
    public RuleRevisionPage revisions(@PathVariable Long id,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "25") int size) {
        return lifecycleService.revisions(id, page, size);
    }

    @GetMapping("/{id}/revisions/{revision}")
    public RuleRevisionDetail revision(@PathVariable Long id, @PathVariable int revision) {
        return lifecycleService.revision(id, revision);
    }

    @PostMapping("/{id}/revisions/{revision}/restore")
    public ResponseEntity<RuleResponse> restore(
            @PathVariable Long id,
            @PathVariable int revision,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return withEtag(lifecycleService.restore(id, revision, ifMatch, actor()));
    }

    @PostMapping("/test")
    public RuleTestResponse runTest(@Valid @RequestBody RuleTestRequest request) {
        if ("all".equals(request.mode())) {
            Event event = parseRequiredEvent(request.eventJson());
            List<RuleResult> results = new ArrayList<>();
            boolean anyMatched = false;
            for (Rule rule : ruleRepository.findByActiveTrueAndArchivedAtIsNullOrderByNameAsc()) {
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
                    rule.getWindowSeconds(),
                    evaluation.groupKey(),
                    evaluation.sequence()));
            }
            return RuleTestResponse.all(results, anyMatched, event);
        }

        if (request.ruleId() == null) {
            throw new IllegalArgumentException("ruleId is required in single mode");
        }
        Rule rule = lifecycleService.find(request.ruleId());
        if (rule.getRuleType() == com.tengencorp.tengen.entity.RuleType.ABSENCE) {
            Event startEvent = parseRequiredEvent(request.eventJson());
            Event expectedEvent = request.absenceExpectedEventJson() == null
                || request.absenceExpectedEventJson().isBlank()
                ? null : eventJsonParser.parse(request.absenceExpectedEventJson());
            AbsenceTestResult absenceTest = absenceRuleService.test(startEvent, expectedEvent, rule);
            return RuleTestResponse.singleAbsence(rule, absenceTest, startEvent);
        }
        if (rule.getRuleType() == com.tengencorp.tengen.entity.RuleType.SEQUENCE
                && request.sequenceEventJsons() != null
                && !request.sequenceEventJsons().isEmpty()) {
            if (request.sequenceEventJsons().size() != rule.getSequenceSteps().size()) {
                throw new IllegalArgumentException(
                    "sequenceEventJsons must contain one event for each configured sequence step");
            }
            List<Event> sequenceEvents = request.sequenceEventJsons().stream()
                .map(eventJsonParser::parse)
                .toList();
            SequenceTestResult sequenceTest = sequenceRuleService.testSequence(sequenceEvents, rule);
            return RuleTestResponse.singleSequence(rule, sequenceTest,
                sequenceEvents.get(sequenceEvents.size() - 1));
        }
        Event event = parseRequiredEvent(request.eventJson());
        RuleEvaluation evaluation = ruleEngine.test(event, rule);
        return RuleTestResponse.single(
            rule,
            evaluation.matched(rule),
            evaluation.conditionMatched(),
            evaluation.aggregateValue(),
            evaluation.groupKey(),
            event);
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getName() != null
            ? authentication.getName() : "system";
    }

    private Event parseRequiredEvent(String eventJson) {
        if (eventJson == null || eventJson.isBlank()) {
            throw new IllegalArgumentException("eventJson is required");
        }
        return eventJsonParser.parse(eventJson);
    }

    private ResponseEntity<RuleResponse> withEtag(RuleResponse response) {
        return withEtag(ResponseEntity.ok(), response);
    }

    private ResponseEntity<RuleResponse> withEtag(ResponseEntity.BodyBuilder builder,
                                                  RuleResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setETag("\"" + response.revision() + "\"");
        return builder.headers(headers).body(response);
    }
}
