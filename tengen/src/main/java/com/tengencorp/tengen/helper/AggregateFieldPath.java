package com.tengencorp.tengen.helper;

/**
 * Normalizes aggregate field paths to the contents of an event's data map.
 */
public final class AggregateFieldPath {

    private static final String DATA_PREFIX = "data.";

    private AggregateFieldPath() {
    }

    public static String normalize(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.trim();
        return normalized.startsWith(DATA_PREFIX)
            ? normalized.substring(DATA_PREFIX.length())
            : normalized;
    }
}
