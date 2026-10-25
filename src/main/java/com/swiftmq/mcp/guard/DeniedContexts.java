package com.swiftmq.mcp.guard;

import java.util.Locale;
import java.util.Set;

/**
 * Top-level management contexts that are never accessed, regardless of configuration.
 * Configuration may add further entries, but can never remove these.
 */
public final class DeniedContexts {
    public static final Set<String> FIXED = Set.of("sys$authentication", "sys$mgmt", "sys$timer");

    private DeniedContexts() {
    }

    public static boolean isFixedDenied(String topLevelContext) {
        return topLevelContext != null && FIXED.contains(topLevelContext.toLowerCase(Locale.ROOT));
    }
}
