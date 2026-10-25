package com.swiftmq.mcp.access;

import com.swiftmq.mcp.access.SwiftMqGateway.ConnectionStatus.State;
import com.swiftmq.mcp.catalog.Catalog;
import com.swiftmq.mcp.config.Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs against a real local CE router (default settings: smqp://localhost:4001, ConnectionFactory, admin).
 * Skipped if no router listens. Override with env SWIFTMQ_TEST_URL / SWIFTMQ_TEST_USER / SWIFTMQ_TEST_PASSWORD.
 */
class LiveRouterTest {
    static CliConnection connection;
    static ManagementReader reader;
    static String router;

    @BeforeAll
    static void connect() {
        assumeTrue(listening("localhost", 4001), "no SwiftMQ router on localhost:4001");
        Settings settings = new Settings(env("SWIFTMQ_TEST_URL", "smqp://localhost:4001/timeout=10000"),
                env("SWIFTMQ_TEST_USER", "admin"), env("SWIFTMQ_TEST_PASSWORD", "secret"),
                Settings.DEFAULT_CONNECTION_FACTORY, 500, 15000, Set.of(), null);
        connection = new CliConnection(settings, new ContextGuard(Set.of())).start();
        CliConnectionTest.await(() -> connection.status().state() == State.CONNECTED && !connection.availableRouters().isEmpty());
        router = connection.availableRouters().first();
        reader = new ManagementReader(connection, Catalog.load(), 500);
    }

    @AfterAll
    static void close() {
        if (connection != null)
            connection.close();
    }

    @Test
    void queueUsage() {
        var usage = reader.read(router, MgmtPath.of("/sys$queuemanager/usage")).orElseThrow();
        assertTrue(usage.children().contains("routerdlq"), usage.children().toString());
        var dlq = reader.read(router, MgmtPath.of("/sys$queuemanager/usage/routerdlq")).orElseThrow();
        assertInstanceOf(Integer.class, dlq.properties().get("messagecount"));
        assertInstanceOf(Boolean.class, dlq.properties().get("active"));
    }

    @Test
    void envAndMemory() {
        var env = reader.read(router, MgmtPath.of("/.env")).orElseThrow();
        assertEquals(router, env.properties().get("routername"));
        assertInstanceOf(Boolean.class, env.properties().get("use-smart-tree"));
        var mem = reader.read(router, MgmtPath.of("/.env/router-memory-list/" + router)).orElseThrow();
        assertInstanceOf(Integer.class, mem.properties().get("total-memory"));
    }

    @Test
    void namesWithSpacesWork() {
        var groups = reader.read(router, MgmtPath.of("/sys$scheduler/usage/job-groups")).orElseThrow();
        assumeTrue(groups.children().contains("Queue Manager"), "no 'Queue Manager' job group: " + groups.children());
        assertTrue(reader.read(router, MgmtPath.of("/sys$scheduler/usage/job-groups/Queue Manager")).isPresent());
    }

    @Test
    void networkConnectionsAreTyped() {
        var net = reader.read(router, MgmtPath.of("/sys$net/usage")).orElseThrow();
        assertFalse(net.children().isEmpty(), "at least our own connection");
        var conn = reader.read(router, MgmtPath.of("/sys$net/usage/" + net.children().get(0))).orElseThrow();
        assertTrue(conn.properties().get("connect-time").toString().matches("\\d{4}-\\d\\d-\\d\\dT.*"), conn.toString());
    }

    @Test
    void missingContextAndDeniedContext() {
        assertTrue(reader.read(router, MgmtPath.of("/sys$queuemanager/usage/no-such-queue")).isEmpty());
        assertThrows(SwiftMqAccessException.AccessDenied.class, () -> reader.read(router, MgmtPath.of("/sys$authentication")));
        assertThrows(SwiftMqAccessException.RouterUnavailable.class, () -> reader.read("no-such-router", MgmtPath.of("/sys$queuemanager")));
        // the connection still works afterwards
        assertTrue(reader.read(router, MgmtPath.of("/sys$queuemanager")).isPresent());
    }

    private static boolean listening(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
