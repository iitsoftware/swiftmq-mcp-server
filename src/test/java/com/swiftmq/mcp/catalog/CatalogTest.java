package com.swiftmq.mcp.catalog;

import com.swiftmq.mcp.guard.DeniedContexts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the committed swiftmq-metadata.json as the server loads it at runtime. */
class CatalogTest {
    static Catalog catalog;

    @BeforeAll
    static void load() {
        catalog = Catalog.load();
    }

    @Test
    void ceOnly() {
        assertEquals("13.02.04", catalog.release());
        assertEquals(List.of(Catalog.CE), List.copyOf(catalog.editions().keySet()));
    }

    @Test
    void deniedSwiftletsAreAbsent() {
        for (String denied : DeniedContexts.FIXED)
            assertFalse(catalog.swiftlets(Catalog.CE).containsKey(denied), denied);
    }

    @Test
    void queueUsageProperties() {
        CatalogNode queueUsage = catalog.lookup(Catalog.CE, "/sys$queuemanager/usage/testqueue");
        assertNotNull(queueUsage);
        assertEquals(CatalogNode.Kind.ENTITY, queueUsage.kind());
        for (String p : List.of("messagecount", "msg-consume-rate", "msg-produce-rate", "total-consumed", "total-produced", "latency", "active"))
            assertTrue(queueUsage.properties().containsKey(p), p);
        assertEquals("Integer", queueUsage.properties().get("messagecount").type());
        assertEquals("Boolean", queueUsage.properties().get("active").type());
    }

    @Test
    void usageSublistsResolveThroughTemplates() {
        CatalogNode receiver = catalog.lookup(Catalog.CE, "/sys$queuemanager/usage/tmp$8-1792866365982/receiver/20");
        assertNotNull(receiver);
        assertEquals(List.of("username", "clientid", "selector"), List.copyOf(receiver.properties().keySet()));

        CatalogNode durable = catalog.lookup(Catalog.CE, "/sys$topicmanager/usage/durables/routeannouncer$initrequests");
        assertNotNull(durable);
        assertTrue(durable.properties().keySet().containsAll(List.of("durablename", "clientid", "topic", "boundto")));
    }

    @Test
    void ceDefaults() {
        CatalogNode queue = catalog.lookup(Catalog.CE, "/sys$queuemanager/queues/testqueue");
        assertEquals("120000", queue.properties().get("cleanup-interval").defaultValue());
        assertEquals("2000", queue.properties().get("duplicate-detection-backlog-size").defaultValue());
        assertEquals(List.of("as_message", "persistent", "non_persistent"), queue.properties().get("persistence-mode").choices());
    }

    @Test
    void routingAndNetwork() {
        assertNotNull(catalog.lookup(Catalog.CE, "/sys$routing/usage/connections/router2").properties().get("routername"));
        assertNotNull(catalog.lookup(Catalog.CE, "/sys$net/usage/127.0.0.1:60526").properties().get("throughput-input"));
    }

    @Test
    void unknownPathsResolveToNull() {
        assertNull(catalog.lookup(Catalog.CE, "/sys$queuemanager/nosuchlist"));
        assertNull(catalog.lookup(Catalog.CE, "/sys$authentication/users"));
        assertNull(catalog.lookup(Catalog.CE, "/.env"));
    }

    @Test
    void exactlyTheRoutingPasswordsAreSecret() {
        List<String> secrets = new ArrayList<>();
        catalog.swiftlets(Catalog.CE).forEach((name, node) -> collectSecrets(node, "/" + name, secrets));
        assertEquals(List.of("/sys$routing/connectors/*/password", "/sys$routing/listeners/*/password"), secrets.stream().sorted().toList());
    }

    private static void collectSecrets(CatalogNode node, String path, List<String> out) {
        if (node.kind() == CatalogNode.Kind.LIST) {
            collectSecrets(node.template(), path + "/*", out);
            return;
        }
        if (node.properties() != null)
            node.properties().forEach((name, p) -> {
                if (p.secret())
                    out.add(path + "/" + name);
            });
        for (Map<String, CatalogNode> children : java.util.Arrays.asList(node.entities(), node.lists()))
            if (children != null)
                children.forEach((name, child) -> collectSecrets(child, path + "/" + name, out));
    }
}
