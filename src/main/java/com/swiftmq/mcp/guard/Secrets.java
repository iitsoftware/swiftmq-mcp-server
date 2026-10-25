package com.swiftmq.mcp.guard;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which property values are secret and must never be returned.
 */
public final class Secrets {
    public static final String REDACTED = "***";

    private static final Pattern SECRET = Pattern.compile("^(.*-)?(password|passwd|secret)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUSPICIOUS = Pattern.compile(".*(pass|secret).*", Pattern.CASE_INSENSITIVE);

    /** Names that look suspicious but were reviewed and carry no secret value. */
    private static final Set<String> REVIEWED_NON_SECRET = Set.of("password-check-only");

    private Secrets() {
    }

    public static boolean isSecret(String propertyName) {
        return SECRET.matcher(propertyName).matches();
    }

    /** True for names that look like a secret but are neither matched by {@link #isSecret} nor reviewed. */
    public static boolean isUnreviewedSuspicious(String propertyName) {
        return SUSPICIOUS.matcher(propertyName).matches()
                && !isSecret(propertyName)
                && !REVIEWED_NON_SECRET.contains(propertyName);
    }
}
