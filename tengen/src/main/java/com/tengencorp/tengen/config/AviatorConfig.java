package com.tengencorp.tengen.config;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a thread-safe singleton Aviator evaluator instance.
 * AviatorEvaluatorInstance is thread-safe for concurrent evaluations,
 * so a single shared bean is used for all rule condition scripts.
 */
@Configuration
public class AviatorConfig {

    @Bean
    public AviatorEvaluatorInstance aviatorEvaluator() {
        AviatorEvaluatorInstance instance = AviatorEvaluator.getInstance();
        instance.enableSandboxMode();
        // Evaluate scripts at call time so admin edits take effect without restart.
        instance.setCachedExpressionByDefault(true);
        return instance;
    }
}
