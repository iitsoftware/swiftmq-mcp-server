package com.swiftmq.mcp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SettingsResolverTest {
    @TempDir
    Path dir;

    private static final String[] MINIMAL = {"--url", "smqp://localhost:4001", "--user", "admin"};

    @Test
    void minimalWithDefaults() throws Exception {
        Settings s = SettingsResolver.resolve(MINIMAL, Map.of());
        assertEquals("smqp://localhost:4001", s.url());
        assertEquals("admin", s.user());
        assertNull(s.password());
        assertEquals("ConnectionFactory", s.connectionFactory());
        assertEquals(500, s.maxListItems());
        assertEquals(15000, s.requestTimeoutMs());
        assertTrue(s.extraDeniedContexts().isEmpty());
    }

    @Test
    void argsWinOverEnvWinOverFile() throws Exception {
        Path cfg = write("cfg.json", """
                {"url": "smqp://file:4001", "user": "fileuser", "connectionFactory": "FileCF"}""");
        Map<String, String> env = Map.of("SWIFTMQ_URL", "smqp://env:4001", "SWIFTMQ_USER", "envuser");
        Settings s = SettingsResolver.resolve(new String[]{"--config", cfg.toString(), "--url", "smqp://arg:4001"}, env);
        assertEquals("smqp://arg:4001", s.url());
        assertEquals("envuser", s.user());
        assertEquals("FileCF", s.connectionFactory());
    }

    @Test
    void passwordFromEnvOrFile() throws Exception {
        assertEquals("secret", SettingsResolver.resolve(MINIMAL, Map.of("SWIFTMQ_PASSWORD", "secret")).password());

        Path pw = write("pw.txt", "fromfile\n");
        String[] args = {"--url", "smqp://localhost:4001", "--user", "admin", "--password-file", pw.toString()};
        assertEquals("fromfile", SettingsResolver.resolve(args, Map.of()).password());
        // env wins over the file
        assertEquals("secret", SettingsResolver.resolve(args, Map.of("SWIFTMQ_PASSWORD", "secret")).password());
    }

    @Test
    void passwordNeverAsArgumentOrInConfigFile() throws Exception {
        SettingsException e = assertThrows(SettingsException.class,
                () -> SettingsResolver.resolve(new String[]{"--url", "smqp://h:4001", "--user", "u", "--password", "x"}, Map.of()));
        assertTrue(e.getMessage().contains("SWIFTMQ_PASSWORD"));

        Path cfg = write("cfg.json", """
                {"url": "smqp://h:4001", "user": "u", "password": "x"}""");
        assertThrows(SettingsException.class, () -> SettingsResolver.resolve(new String[]{"--config", cfg.toString()}, Map.of()));
    }

    @Test
    void urlMustNotCarryCredentials() {
        SettingsException e = assertThrows(SettingsException.class,
                () -> SettingsResolver.resolve(new String[]{"--url", "smqp://admin:secret@localhost:4001", "--user", "admin"}, Map.of()));
        assertTrue(e.getMessage().contains("credentials"));
    }

    @Test
    void urlOptionsWithAtSignAreFine() throws Exception {
        Settings s = SettingsResolver.resolve(new String[]{"--url", "smqp://localhost:4001/timeout=10000;host2=a@b", "--user", "u"}, Map.of());
        assertEquals("smqp://localhost:4001/timeout=10000;host2=a@b", s.url());
    }

    @Test
    void missingOrInvalidValues() {
        assertThrows(SettingsException.class, () -> SettingsResolver.resolve(new String[]{"--user", "u"}, Map.of()));
        assertThrows(SettingsException.class, () -> SettingsResolver.resolve(new String[]{"--url", "smqp://h:4001"}, Map.of()));
        assertThrows(SettingsException.class, () -> SettingsResolver.resolve(new String[]{"--url", "tcp://h:4001", "--user", "u"}, Map.of()));
        assertThrows(SettingsException.class, () -> SettingsResolver.resolve(new String[]{"--url"}, Map.of()));
        assertThrows(SettingsException.class, () -> SettingsResolver.resolve(new String[]{"--bogus", "x"}, Map.of()));
    }

    @Test
    void limitsAndDeniedContextsFromFile() throws Exception {
        Path cfg = write("cfg.json", """
                {"url": "smqp://h:4001", "user": "u",
                 "limits": {"maxListItems": 50, "requestTimeoutMs": 3000},
                 "deniedContexts": ["SYS$Streams"]}""");
        Settings s = SettingsResolver.resolve(new String[]{"--config", cfg.toString()}, Map.of());
        assertEquals(50, s.maxListItems());
        assertEquals(3000, s.requestTimeoutMs());
        assertEquals(Set.of("sys$streams"), s.extraDeniedContexts());
    }

    @Test
    void invalidLimitsAndJson() throws Exception {
        Path bad = write("bad.json", "{not json");
        assertThrows(SettingsException.class, () -> SettingsResolver.resolve(new String[]{"--config", bad.toString()}, Map.of()));
        Path zero = write("zero.json", """
                {"url": "smqp://h:4001", "user": "u", "limits": {"maxListItems": 0}}""");
        assertThrows(SettingsException.class, () -> SettingsResolver.resolve(new String[]{"--config", zero.toString()}, Map.of()));
    }

    @Test
    void toStringMasksSecrets() throws Exception {
        Settings s = SettingsResolver.resolve(MINIMAL, Map.of("SWIFTMQ_PASSWORD", "topsecret"));
        assertFalse(s.toString().contains("topsecret"));
        assertTrue(s.toString().contains("password=***"));
    }

    @Test
    void help() {
        assertTrue(SettingsResolver.isHelp(new String[]{"--help"}));
        assertFalse(SettingsResolver.isHelp(MINIMAL));
    }

    private Path write(String name, String content) throws Exception {
        return Files.writeString(dir.resolve(name), content);
    }
}
