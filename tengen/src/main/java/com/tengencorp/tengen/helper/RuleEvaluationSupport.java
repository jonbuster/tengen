package com.tengencorp.tengen.helper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Pure event/rule helpers shared by live and historical evaluation. */
public final class RuleEvaluationSupport {

    public static final int MAX_GROUP_KEY_LENGTH = 500;

    private RuleEvaluationSupport() {
    }

    public static Map<String, Object> buildEnvironment(String type, String source,
                                                        Instant occurredAt,
                                                        Map<String, Object> data) {
        Map<String, Object> environment = new HashMap<>();
        environment.put("type", type);
        environment.put("source", source);
        environment.put("timestamp", occurredAt);
        environment.put("data", data);
        return environment;
    }

    public static Object resolvePath(Map<String, Object> map, String path) {
        Object current = map;
        String normalizedPath = AggregateFieldPath.normalize(path);
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return null;
        }
        for (String part : normalizedPath.split("\\.")) {
            if (current instanceof Map<?, ?> values) {
                current = values.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    public static Double extractNumericValue(Map<String, Object> data, String path) {
        Object value = resolvePath(data, path);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    public static String extractGroupKey(Map<String, Object> data, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Object value = resolvePath(data, path);
        if (value == null || value instanceof Map<?, ?>) {
            return null;
        }
        String groupKey = String.valueOf(value).trim();
        return groupKey.isBlank() ? null : groupKey;
    }
}
