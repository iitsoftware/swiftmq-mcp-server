package com.swiftmq.mcp.access;

import com.swiftmq.mcp.catalog.PropertyDef;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Converts the CLI's string values into typed JSON values, based on the catalog type. Values that don't
 * parse are returned unchanged, so a version difference never loses data.
 */
final class ValueConverter {
    /** Format of {@link java.util.Date#toString()}, used by SwiftMQ for times such as connect-time. */
    private static final DateTimeFormatter DATE_TO_STRING = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US);

    private ValueConverter() {
    }

    static Object convert(String name, PropertyDef def, String raw) {
        if (raw == null || raw.equals("null"))
            return null;
        String type = def == null ? "String" : def.type();
        try {
            return switch (type) {
                case "Integer" -> Integer.valueOf(raw.strip());
                case "Long" -> Long.valueOf(raw.strip());
                case "Boolean" -> raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false") ? Boolean.valueOf(raw) : raw;
                default -> convertString(name, raw);
            };
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static Object convertString(String name, String raw) {
        if (name.startsWith("throughput-"))
            return new BigDecimal(raw.strip());   // KB/s, e.g. "0.00"
        if (name.endsWith("time") && raw.length() >= 20 && Character.isLetter(raw.charAt(0))) {
            try {
                return ZonedDateTime.parse(raw, DATE_TO_STRING).toOffsetDateTime().toString();
            } catch (DateTimeParseException ignored) {
                // not a Date.toString() value
            }
        }
        return raw;
    }
}
