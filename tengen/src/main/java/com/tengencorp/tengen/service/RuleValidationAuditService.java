package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.RuleValidationStatus;
import com.tengencorp.tengen.repository.RuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Audits legacy rules once at startup and prevents invalid configurations from executing. */
@Component
public class RuleValidationAuditService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RuleValidationAuditService.class);

    private final RuleRepository ruleRepository;
    private final RuleValidationService validationService;

    public RuleValidationAuditService(RuleRepository ruleRepository,
                                      RuleValidationService validationService) {
        this.ruleRepository = ruleRepository;
        this.validationService = validationService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int invalid = 0;
        for (var rule : ruleRepository.findAll()) {
            String error = validationService.validationError(rule);
            if (error == null) {
                rule.setValidationStatus(RuleValidationStatus.VALID);
                rule.setValidationError(null);
            } else {
                invalid++;
                rule.setValidationStatus(RuleValidationStatus.INVALID);
                rule.setValidationError(error);
                rule.setActive(false);
                log.warn("Deactivated invalid rule during startup audit: ruleId={}, revision={}, reason={}",
                    rule.getId(), rule.getEffectiveRevision(), error);
            }
        }
        if (invalid > 0) {
            log.warn("Rule validation audit found {} invalid rule(s)", invalid);
        }
    }
}
