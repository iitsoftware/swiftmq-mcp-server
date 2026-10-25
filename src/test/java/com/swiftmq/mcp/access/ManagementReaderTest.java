package com.swiftmq.mcp.access;

import com.swiftmq.mcp.catalog.Catalog;
import com.swiftmq.mcp.catalog.CatalogNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ManagementReaderTest {
    static final Catalog CATALOG = Catalog.load();

    static SwiftMqGateway gateway(FakeCli cli) {
        return new FakeGateway(cli);
    }

    @Test
    void typedValuesFromCatalog() {
        FakeCli cli = new FakeCli().context("router", "/sys$queuemanager/usage/testqueue", List.of(),
                Map.of("messagecount", "12", "active", "true", "latency", "3", "msg-consume-rate", "x"));
        var view = new ManagementReader(gateway(cli), CATALOG, 500).read("router", MgmtPath.of("/sys$queuemanager/usage/testqueue")).orElseThrow();
        assertEquals(12, view.properties().get("messagecount"));
        assertEquals(true, view.properties().get("active"));
        assertEquals(3L, view.properties().get("latency"));
        assertEquals("x", view.properties().get("msg-consume-rate"), "unparseable values stay strings");
        assertEquals(CatalogNode.Kind.ENTITY, view.kind());
    }

    @Test
    void nullsDatesAndThroughput() {
        FakeCli cli = new FakeCli()
                .context("router", "/sys$net/usage/127.0.0.1:60526", List.of(),
                        Map.of("swiftlet", "sys$jms", "connect-time", "Sat Oct 24 20:04:14 CEST 2026",
                                "throughput-input", "1.50", "throughput-output", "0.00"))
                .context("router", "/sys$topicmanager/usage/subscriber/swiftmq.jndi-1", List.of(),
                        Map.of("selector", "null", "topic", "swiftmq.jndi"));
        var reader = new ManagementReader(gateway(cli), CATALOG, 500);
        var net = reader.read("router", MgmtPath.of("/sys$net/usage/127.0.0.1:60526")).orElseThrow();
        assertEquals("2026-10-24T20:04:14+02:00", net.properties().get("connect-time"));
        assertEquals(new BigDecimal("1.50"), net.properties().get("throughput-input"));
        var sub = reader.read("router", MgmtPath.of("/sys$topicmanager/usage/subscriber/swiftmq.jndi-1")).orElseThrow();
        assertTrue(sub.properties().containsKey("selector"));
        assertNull(sub.properties().get("selector"));
    }

    @Test
    void secretsAreRedacted() {
        FakeCli cli = new FakeCli()
                .context("router", "/sys$routing/connectors/c1", List.of(), Map.of("password", "topsecret", "hostname", "h"))
                .context("router", "/sys$routing/listeners/l1", List.of(), Map.of("password", "null", "port", "4100"));
        var reader = new ManagementReader(gateway(cli), CATALOG, 500);
        var connector = reader.read("router", MgmtPath.of("/sys$routing/connectors/c1")).orElseThrow();
        assertEquals("***", connector.properties().get("password"));
        assertFalse(connector.toString().contains("topsecret"));
        var listener = reader.read("router", MgmtPath.of("/sys$routing/listeners/l1")).orElseThrow();
        assertNull(listener.properties().get("password"), "unset passwords stay null");
        assertEquals(4100, listener.properties().get("port"));
    }

    @Test
    void childListsAreCapped() {
        List<String> queues = new ArrayList<>();
        for (int i = 0; i < 12; i++)
            queues.add("q" + i);
        FakeCli cli = new FakeCli().context("router", "/sys$queuemanager/usage", queues, Map.of());
        var view = new ManagementReader(gateway(cli), CATALOG, 10).read("router", MgmtPath.of("/sys$queuemanager/usage")).orElseThrow();
        assertEquals(10, view.children().size());
        assertEquals(12, view.childrenTotal());
        assertTrue(view.truncated());
        assertEquals(CatalogNode.Kind.LIST, view.kind());
    }

    @Test
    void envMemory() {
        FakeCli cli = new FakeCli()
                .context("router", "/.env", List.of("router-memory-list"), Map.of("use-smart-tree", "false", "release", "13.02.04 Community Edition"))
                .context("router", "/.env/router-memory-list/router", List.of(), Map.of("free-memory", "15959", "total-memory", "96256"));
        var reader = new ManagementReader(gateway(cli), CATALOG, 500);
        assertEquals(false, reader.read("router", MgmtPath.of("/.env")).orElseThrow().properties().get("use-smart-tree"));
        var mem = reader.read("router", MgmtPath.of("/.env/router-memory-list/router")).orElseThrow();
        assertEquals(Map.of("free-memory", 15959, "total-memory", 96256), mem.properties());
    }

    @Test
    void navigationResolvesRouter() {
        NavigationState nav = new NavigationState();
        assertEquals("r1", nav.resolveRouter(null, new TreeSet<>(Set.of("r1"))));
        assertThrows(SwiftMqAccessException.class, () -> nav.resolveRouter(null, new TreeSet<>(Set.of("r1", "r2"))));
        assertThrows(SwiftMqAccessException.NotConnected.class, () -> nav.resolveRouter(null, new TreeSet<>()));
        nav.selectRouter("r2");
        assertEquals("r2", nav.resolveRouter(null, new TreeSet<>(Set.of("r1", "r2"))));
        assertEquals("r1", nav.resolveRouter("r1", new TreeSet<>(Set.of("r1", "r2"))));
        assertThrows(SwiftMqAccessException.AccessDenied.class, () -> nav.resolveRouter("r1;x", new TreeSet<>()));
    }
}
