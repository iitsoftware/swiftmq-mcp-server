package com.swiftmq.mcp.catalog;

import java.util.List;

/**
 * Metadata of one management property, taken from a swiftlet's config.xml.
 *
 * @param type short Java type name: String, Integer, Long or Boolean
 */
public record PropertyDef(
        String type,
        String display,
        String description,
        String defaultValue,
        String min,
        String max,
        List<String> choices,
        boolean readOnly,
        boolean mandatory,
        boolean rebootRequired,
        boolean secret) {
}
