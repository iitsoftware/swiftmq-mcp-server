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
    void allEditions() {
        assertEquals("13.02.04", catalog.release());
        assertEquals(List.of("ce", "ha", "ur"), List.copyOf(catalog.editions().keySet()));
    }

    @Test
    void editionsBuildOnEachOther() {
        var ce = catalog.swiftlets(Edition.CE).keySet();
        var ur = catalog.swiftlets(Edition.UR).keySet();
        var ha = catalog.swiftlets(Edition.HA).keySet();
        assertTrue(ur.containsAll(ce) && ha.containsAll(ur));
        assertFalse(ce.contains("sys$filecache") || ce.contains("sys$jac"));
        assertTrue(ur.containsAll(List.of("sys$filecache", "sys$jac", "xt$jmsbridge")));
        assertTrue(ha.contains("sys$hacontroller"));
        assertFalse(ur.contains("sys$hacontroller"));
        // an edition's own swiftlet replaces its base's as a whole
        assertSame(catalog.editions().get("ha").get("sys$queuemanager"), catalog.swiftlet(Edition.HA, "sys$queuemanager"));
        assertSame(catalog.editions().get("ce").get("sys$queuemanager"), catalog.swiftlet(Edition.UR, "sys$queuemanager"));
        assertNotNull(catalog.lookup(Edition.UR, "/sys$jac/usage"));
        assertNull(catalog.lookup(Edition.CE, "/sys$jac/usage"));
    }

    @Test
    void editionFromRelease() {
        assertEquals(Edition.UR, Edition.fromRelease("13.02.04 Universal Edition"));
        assertEquals(Edition.HA, Edition.fromRelease("13.02.04 High Availability Edition"));
        assertEquals(Edition.CE, Edition.fromRelease("13.02.04 Community Edition"));
        assertEquals(Edition.CE, Edition.fromRelease("13.02.04"));
        assertEquals(Edition.CE, Edition.fromRelease(null));
    }

    @Test
    void deniedSwiftletsAreAbsent() {
        for (Edition edition : Edition.values())
            for (String denied : DeniedContexts.FIXED)
                assertFalse(catalog.swiftlets(edition).containsKey(denied), edition + " " + denied);
    }

    @Test
    void queueUsageProperties() {
        CatalogNode queueUsage = catalog.lookup(Edition.CE, "/sys$queuemanager/usage/testqueue");
        assertNotNull(queueUsage);
        assertEquals(CatalogNode.Kind.ENTITY, queueUsage.kind());
        for (String p : List.of("messagecount", "msg-consume-rate", "msg-produce-rate", "total-consumed", "total-produced", "latency", "active"))
            assertTrue(queueUsage.properties().containsKey(p), p);
        assertEquals("Integer", queueUsage.properties().get("messagecount").type());
        assertEquals("Boolean", queueUsage.properties().get("active").type());
    }

    @Test
    void usageSublistsResolveThroughTemplates() {
        CatalogNode receiver = catalog.lookup(Edition.CE, "/sys$queuemanager/usage/tmp$8-1792866365982/receiver/20");
        assertNotNull(receiver);
        assertEquals(List.of("username", "clientid", "selector"), List.copyOf(receiver.properties().keySet()));

        CatalogNode durable = catalog.lookup(Edition.CE, "/sys$topicmanager/usage/durables/routeannouncer$initrequests");
        assertNotNull(durable);
        assertTrue(durable.properties().keySet().containsAll(List.of("durablename", "clientid", "topic", "boundto")));
    }

    @Test
    void ceDefaults() {
        CatalogNode queue = catalog.lookup(Edition.CE, "/sys$queuemanager/queues/testqueue");
        assertEquals("120000", queue.properties().get("cleanup-interval").defaultValue());
        assertEquals("2000", queue.properties().get("duplicate-detection-backlog-size").defaultValue());
        assertEquals(List.of("as_message", "persistent", "non_persistent"), queue.properties().get("persistence-mode").choices());
    }

    @Test
    void routingAndNetwork() {
        assertNotNull(catalog.lookup(Edition.CE, "/sys$routing/usage/connections/router2").properties().get("routername"));
        assertNotNull(catalog.lookup(Edition.CE, "/sys$net/usage/127.0.0.1:60526").properties().get("throughput-input"));
    }

    @Test
    void unknownPathsResolveToNull() {
        assertNull(catalog.lookup(Edition.CE, "/sys$queuemanager/nosuchlist"));
        assertNull(catalog.lookup(Edition.CE, "/sys$authentication/users"));
    }

    @Test
    void envIsBuiltIn() {
        CatalogNode env = catalog.lookup(Edition.CE, "/.env");
        assertTrue(env.properties().keySet().containsAll(List.of("routername", "release", "use-smart-tree")));
        assertEquals("Boolean", env.properties().get("use-smart-tree").type());
        CatalogNode memory = catalog.lookup(Edition.CE, "/.env/router-memory-list/router");
        assertEquals(List.of("free-memory", "total-memory"), List.copyOf(memory.properties().keySet()));
    }

    @Test
    void exactlyTheRoutingPasswordsAreSecret() {
        List<String> secrets = new ArrayList<>();
        catalog.swiftlets(Edition.CE).forEach((name, node) -> collectSecrets(node, "/" + name, secrets));
        assertEquals(List.of("/sys$routing/connectors/*/password", "/sys$routing/listeners/*/password"), secrets.stream().sorted().toList());
    }

    @Test
    void extensionSwiftletPasswordsAreSecret() {
        List<String> secrets = new ArrayList<>();
        catalog.swiftlets(Edition.HA).forEach((name, node) -> collectSecrets(node, "/" + name, secrets));
        assertEquals(List.of(
                "/sys$routing/connectors/*/password", "/sys$routing/listeners/*/password",
                "/xt$amqpbridge/bridges100/*/source/sasl-password", "/xt$amqpbridge/bridges100/*/target/sasl-password",
                "/xt$javamail/inbound-bridges/*/mail-host-account-password", "/xt$javamail/outbound-bridges/*/smtp-password",
                "/xt$jmsbridge/servers/*/password",
                "/xt$replicator/sinks/*/ivm-password", "/xt$replicator/sources/*/ivm-password"), secrets.stream().sorted().toList());
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
