package com.swiftmq.mcp.tools;

import java.util.Map;

/** Typed access to tool call arguments. Invalid arguments raise {@link IllegalArgumentException}. */
final class Args {
    private final Map<String, Object> values;

    Args(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    String optString(String name) {
        Object v = values.get(name);
        if (v == null)
            return null;
        String s = v.toString().strip();
        return s.isEmpty() ? null : s;
    }

    String string(String name) {
        String s = optString(name);
        if (s == null)
            throw new IllegalArgumentException("Missing argument '" + name + "'");
        return s;
    }

    int optInt(String name, int defaultValue, int min, int max) {
        Object v = values.get(name);
        if (v == null)
            return defaultValue;
        int i;
        try {
            i = v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Argument '" + name + "' must be a number");
        }
        if (i < min || i > max)
            throw new IllegalArgumentException("Argument '" + name + "' must be between " + min + " and " + max);
        return i;
    }

    boolean optBoolean(String name, boolean defaultValue) {
        Object v = values.get(name);
        if (v == null)
            return defaultValue;
        if (v instanceof Boolean b)
            return b;
        return Boolean.parseBoolean(v.toString().strip());
    }
}
