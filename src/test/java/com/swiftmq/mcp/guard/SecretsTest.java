package com.swiftmq.mcp.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SecretsTest {

    @ParameterizedTest
    @ValueSource(strings = {"password", "PASSWORD", "keystore-password", "passwd", "client-secret", "secret"})
    void secrets(String name) {
        assertTrue(Secrets.isSecret(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"password-check-only", "link-credit", "messagecount", "passwordless", "username"})
    void notSecrets(String name) {
        assertFalse(Secrets.isSecret(name));
    }

    @Test
    void suspiciousNamesNeedReview() {
        assertTrue(Secrets.isUnreviewedSuspicious("passwordless"));
        assertTrue(Secrets.isUnreviewedSuspicious("secret-key"));
        assertFalse(Secrets.isUnreviewedSuspicious("password"));
        assertFalse(Secrets.isUnreviewedSuspicious("password-check-only"));
        assertFalse(Secrets.isUnreviewedSuspicious("link-credit"));
    }

    @Test
    void deniedContextsAreCaseInsensitive() {
        assertTrue(DeniedContexts.isFixedDenied("sys$authentication"));
        assertTrue(DeniedContexts.isFixedDenied("SYS$Mgmt"));
        assertTrue(DeniedContexts.isFixedDenied("sys$timer"));
        assertFalse(DeniedContexts.isFixedDenied("sys$queuemanager"));
        assertFalse(DeniedContexts.isFixedDenied(null));
    }
}
