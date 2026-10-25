package com.swiftmq.mcp.access;

import com.swiftmq.mcp.access.SwiftMqGateway.ConnectionStatus.State;
import com.swiftmq.mcp.config.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class CliConnectionTest {
    static final Settings SETTINGS = new Settings("smqp://localhost:4001", "admin", "secret", "ConnectionFactory",
            500, 2000, Set.of(), null);

    final FakeConnector connector = new FakeConnector();
    CliConnection connection;

    @AfterEach
    void close() {
        if (connection != null)
            connection.close();
    }

    static FakeCli tree() {
        return new FakeCli()
                .context("router1", "/sys$queuemanager/usage", List.of("q1", "q2"), Map.of())
                .context("router1", "/sys$queuemanager/usage/q1", List.of(), Map.of("messagecount", "5", "active", "true"));
    }

    CliConnection connected(FakeCli cli) {
        connector.results.add(cli);
        connection = new CliConnection(SETTINGS, new ContextGuard(Set.of()), connector, Clock.systemUTC()).start();
        await(() -> connection.status().state() == State.CONNECTED);
        return connection;
    }

    @Test
    void readsWithAbsoluteSrAndQuotedCc() {
        FakeCli cli = tree();
        connected(cli);
        var snapshot = connection.read("router1", MgmtPath.of("/sys$queuemanager/usage/q1"), List.of("messagecount", "active", "no-such-prop")).orElseThrow();
        assertEquals(List.of("sr router1", "cc \"/sys$queuemanager/usage/q1\""), cli.commands);
        assertEquals(Map.of("messagecount", "5", "active", "true"), snapshot.properties(), "absent properties are skipped");

        var list = connection.read("router1", MgmtPath.of("/sys$queuemanager/usage"), List.of()).orElseThrow();
        assertEquals(List.of("q1", "q2"), list.entities());
    }

    @Test
    void deniedPathSendsNothingToTheRouter() {
        FakeCli cli = tree();
        connected(cli);
        assertThrows(SwiftMqAccessException.AccessDenied.class,
                () -> connection.read("router1", MgmtPath.of("/sys$authentication/users"), List.of("password")));
        assertThrows(SwiftMqAccessException.AccessDenied.class,
                () -> connection.read("router1; halt", MgmtPath.of("/sys$queuemanager"), List.of()));
        assertTrue(cli.commands.isEmpty(), "commands: " + cli.commands);
    }

    @Test
    void unknownContextIsEmpty() {
        connected(tree());
        assertTrue(connection.read("router1", MgmtPath.of("/sys$queuemanager/usage/nope"), List.of()).isEmpty());
    }

    @Test
    void unknownRouter() {
        connected(tree());
        assertThrows(SwiftMqAccessException.RouterUnavailable.class,
                () -> connection.read("router9", MgmtPath.of("/sys$queuemanager"), List.of()));
    }

    @Test
    void notConnectedWhileConnecting() {
        connector.blockFirst = true;
        connection = new CliConnection(SETTINGS, new ContextGuard(Set.of()), connector, Clock.systemUTC()).start();
        assertEquals(State.CONNECTING, connection.status().state());
        var e = assertThrows(SwiftMqAccessException.NotConnected.class,
                () -> connection.read("router1", MgmtPath.of("/sys$queuemanager"), List.of()));
        assertTrue(e.getMessage().contains("smqp://localhost:4001"));
        assertFalse(e.getMessage().contains("secret"));
        connector.results.add(tree());
        connector.release.countDown();
        await(() -> connection.status().state() == State.CONNECTED);
    }

    @Test
    void failedConnectIsRetried() {
        connector.results.add(new Exception("Connection refused"));
        connector.results.add(tree());
        connection = new CliConnection(SETTINGS, new ContextGuard(Set.of()), connector, Clock.systemUTC()).start();
        await(() -> connection.status().state() == State.DISCONNECTED);
        assertEquals("Connection refused", connection.status().lastError());
        var e = assertThrows(SwiftMqAccessException.NotConnected.class,
                () -> connection.read("router1", MgmtPath.of("/sys$queuemanager"), List.of()));
        assertTrue(e.getMessage().contains("Connection refused"));
        await(() -> connection.status().state() == State.CONNECTED);   // after the 1 s backoff
        assertEquals(Set.of("router1"), connection.availableRouters());
    }

    @Test
    void hangingConnectIsAbandonedAndRetried() {
        // request timeout 2000 ms in SETTINGS: the hanging attempt is given up, the next one succeeds
        connector.hangFirst = true;
        FakeCli cli = tree();
        connector.results.add(cli);
        connection = new CliConnection(SETTINGS, new ContextGuard(Set.of()), connector, Clock.systemUTC()).start();
        await(() -> connection.status().state() == State.DISCONNECTED);
        assertTrue(connection.status().lastError().contains("no response within 2000 ms"), connection.status().lastError());
        await(() -> connection.status().state() == State.CONNECTED);
        assertEquals(Set.of("router1"), connection.availableRouters());

        connector.hang.countDown();   // the abandoned attempt completes late: its connection is closed, not used
        await(() -> connector.lateCli.closed);
        assertFalse(connection.availableRouters().contains("late"));
    }

    @Test
    void lostConnectionReconnects() {
        FakeCli first = tree();
        connected(first);
        FakeCli second = tree().router("router2");
        connector.results.add(second);
        connector.onConnectionLost.run();
        await(() -> first.closed);
        await(() -> connection.status().state() == State.CONNECTED && connection.availableRouters().contains("router2"));
        connection.read("router1", MgmtPath.of("/sys$queuemanager/usage"), List.of());
        assertEquals(List.of("sr router1", "cc \"/sys$queuemanager/usage\""), second.commands);
    }

    @Test
    void routerEventsUpdateAvailability() {
        connected(tree());
        connector.routerListener.onRouterEvent("router2", true);
        assertEquals(new TreeSet<>(Set.of("router1", "router2")), connection.availableRouters());
        connector.routerListener.onRouterEvent("router1", false);
        assertEquals(Set.of("router2"), connection.availableRouters());
    }

    @Test
    void concurrentReadsAreSerialized() throws Exception {
        FakeCli cli = tree();
        cli.commandDelayMs = 20;
        connected(cli);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++)
            futures.add(pool.submit(() -> connection.read("router1", MgmtPath.of("/sys$queuemanager/usage/q1"), List.of("messagecount"))));
        for (Future<?> f : futures)
            f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(16, cli.commands.size());
        for (int i = 0; i < cli.commands.size(); i += 2) {
            assertEquals("sr router1", cli.commands.get(i));
            assertEquals("cc \"/sys$queuemanager/usage/q1\"", cli.commands.get(i + 1));
        }
    }

    @Test
    void closeClosesTheCli() {
        FakeCli cli = tree();
        connected(cli);
        connection.close();
        assertTrue(cli.closed);
        assertThrows(SwiftMqAccessException.NotConnected.class,
                () -> connection.read("router1", MgmtPath.of("/sys$queuemanager"), List.of()));
    }

    static void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline)
                fail("condition not reached within 5 s");
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
