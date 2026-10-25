package com.swiftmq.mcp.tools;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Batch 2: search, durables, routing, network, health, browsing. */
class MoreToolsTest extends ToolTestBase {

    @Test
    void listQueuesReportsDeltaSinceLastFullListing() {
        usageList("router", "q");
        queue("router", "q", 10);
        assertFalse(ok("list_queues", Map.of()).get("queues").get(0).has("delta"), "no baseline on the first call");
        queue("router", "q", 25);
        assertFalse(ok("list_queues", Map.of("pattern", "q")).get("queues").get(0).has("delta"), "filtered calls don't use the baseline");
        assertEquals(15, ok("list_queues", Map.of()).get("queues").get(0).get("delta").asInt());
        queue("router", "q", 5);
        assertEquals(-20, ok("list_queues", Map.of()).get("queues").get(0).get("delta").asInt());
    }

    @Test
    void findQueueAcrossRouters() {
        usageList("r1", "orders", "other");
        queue("r1", "orders", 3);
        usageList("r2", "orders-eu");
        queue("r2", "orders-eu", 8);
        gateway.available = new TreeSet<>(Set.of("r1", "r2", "r3"));
        JsonNode r = ok("find_queue", Map.of("pattern", "orders*"));
        assertEquals(2, r.get("found").size());
        assertEquals("r2", r.get("found").get(1).get("router").asString());
        assertEquals(8, r.get("found").get(1).get("messagecount").asInt());
        assertEquals("r3", r.get("errors").get(0).get("router").asString());
    }

    @Test
    void durablesWithBacklogSortedLargestFirst() {
        cli.context("router", "/sys$topicmanager/usage/durables", List.of("app$d1", "app$d2"), Map.of());
        cli.context("router", "/sys$topicmanager/usage/durables/app$d1", List.of(), Map.of("topic", "orders", "clientid", "app", "durablename", "d1", "boundto", "app$d1"));
        cli.context("router", "/sys$topicmanager/usage/durables/app$d2", List.of(), Map.of("topic", "prices", "clientid", "app", "durablename", "d2", "boundto", "app$d2"));
        queue("router", "app$d1", 3);
        queue("router", "app$d2", 4000);

        JsonNode list = ok("list_durable_subscribers", Map.of()).get("durableSubscribers");
        assertEquals("app$d2", list.get(0).get("name").asString());
        assertEquals(4000, list.get(0).get("backlog").asInt());
        assertEquals(1, ok("list_durable_subscribers", Map.of("pattern", "orders")).get("durableSubscribers").size());

        cli.context("router", "/sys$topicmanager/topics", List.of("orders"), Map.of());
        JsonNode topic = ok("get_topic", Map.of("topic", "orders"));
        assertTrue(topic.get("configured").asBoolean());
        assertEquals(3, topic.get("durableSubscribers").get(0).get("backlog").asInt());
        assertTrue(error("get_topic", Map.of("topic", "nope")).contains("neither configured nor in use"));
    }

    @Test
    void routingTableAndConfig() {
        cli.context("router", "/sys$routing/usage/routing-table", List.of("r2", "r3"), Map.of());
        cli.context("router", "/sys$routing/usage/routing-table/r2/dynamic-routes", List.of("r2"), Map.of());
        cli.context("router", "/sys$routing/usage/routing-table/r3/dynamic-routes", List.of("r2;r3"), Map.of());
        JsonNode table = ok("get_routing_table", Map.of());
        assertEquals(2, table.get("destinations").size());
        assertEquals("r2;r3", table.get("destinations").get(1).get("dynamicRoutes").get(0).asString());
        assertEquals(1, ok("get_routing_table", Map.of("destination", "r3")).get("destinations").size());
        assertTrue(error("get_routing_table", Map.of("destination", "r9")).contains("no route"));

        cli.context("router", "/sys$routing", List.of("connectors", "listeners"), Map.of("route-announce-hop-limit", "3"));
        cli.context("router", "/sys$routing/connectors", List.of("c1"), Map.of());
        cli.context("router", "/sys$routing/connectors/c1", List.of(), Map.of("hostname", "h2", "port", "4100", "password", "pw!"));
        JsonNode cfg = ok("get_routing_config", Map.of());
        assertEquals(3, cfg.get("settings").get("route-announce-hop-limit").asInt());
        assertEquals("***", cfg.get("connectors").get(0).get("password").asString());
        assertFalse(cfg.toString().contains("pw!"));
    }

    @Test
    void topologyFindsIssues() {
        // r1 <-> r2 healthy; r2 -> r3 one-way; r1 lists r4 which isn't available; r3 has a connector but no connection
        connection("r1", "r2");
        connection("r1", "r4");
        connection("r2", "r1");
        connection("r2", "r3");
        cli.context("r3", "/sys$routing/usage/connections", List.of(), Map.of());
        cli.context("r3", "/sys$routing/connectors", List.of("c1"), Map.of());
        cli.context("r3", "/sys$routing/connectors/c1", List.of(), Map.of("enabled", "true"));
        gateway.available = new TreeSet<>(Set.of("r1", "r2", "r3"));

        JsonNode t = ok("network_topology", Map.of());
        assertEquals(List.of(List.of("r1", "r2"), List.of("r1", "r4"), List.of("r2", "r3")), links(t.get("links")));
        String issues = t.get("issues").toString();
        assertTrue(issues.contains("r3: 1 of 1 enabled connector(s) have no active connection"), issues);
        assertTrue(issues.contains("one-way link: r2 lists r3, but r3 doesn't list r2"), issues);
        assertTrue(issues.contains("r4: connected to by other routers but not available"), issues);
        assertFalse(issues.contains("r1 lists r2"), issues);
    }

    @Test
    void outgoingConnectionsWithSlashInTheNameAreListedNotRead() {
        // real naming from a CE network: outgoing connector connections are "<router>/<host:port>"
        cli.context("routerA", "/sys$routing/usage/connections", List.of("routerB/172.21.0.2:4100"), Map.of());
        cli.context("routerB", "/sys$routing/usage/connections", List.of("routerA"), Map.of());
        cli.context("routerB", "/sys$routing/usage/connections/routerA", List.of(), Map.of("routername", "routerA", "protocol-version", "750"));

        JsonNode a = ok("list_routing_connections", Map.of("router", "routerA")).get("connections").get(0);
        assertEquals("routerB", a.get("routername").asString());
        assertEquals("172.21.0.2:4100", a.get("remoteAddress").asString());
        assertEquals("outgoing", a.get("direction").asString());
        assertFalse(a.get("readable").asBoolean());

        JsonNode t = ok("network_topology", Map.of());
        assertEquals(List.of(List.of("routerA", "routerB")), links(t.get("links")));
        assertEquals("[]", t.get("issues").toString());

        // routerB also has a connector, but its outgoing connection is gone; the incoming one from routerA doesn't hide that
        cli.context("routerB", "/sys$routing/connectors", List.of("to-c"), Map.of());
        cli.context("routerB", "/sys$routing/connectors/to-c", List.of(), Map.of("enabled", "true"));
        assertEquals("[\"routerB: 1 of 1 enabled connector(s) have no active connection\"]", ok("network_topology", Map.of()).get("issues").toString());
    }

    void connection(String router, String peer) {
        String list = "/sys$routing/usage/connections";
        List<String> existing = new ArrayList<>(cli.trees.getOrDefault(router, Map.of()).containsKey(list)
                ? cli.trees.get(router).get(list).entities() : List.of());
        existing.add(peer);
        cli.context(router, list, existing, Map.of());
        cli.context(router, list + "/" + peer, List.of(), Map.of("routername", peer));
    }

    @Test
    void networkConnectionsAndSummary() {
        cli.context("router", "/sys$net/usage", List.of("a:1", "b:2", "c:3"), Map.of());
        cli.context("router", "/sys$net/usage/a:1", List.of(), Map.of("swiftlet", "sys$jms", "throughput-input", "1.00", "throughput-output", "0.50"));
        cli.context("router", "/sys$net/usage/b:2", List.of(), Map.of("swiftlet", "sys$jms", "throughput-input", "9.00", "throughput-output", "1.00"));
        cli.context("router", "/sys$net/usage/c:3", List.of(), Map.of("swiftlet", "sys$routing", "throughput-input", "0.00", "throughput-output", "0.00"));

        JsonNode conns = ok("list_network_connections", Map.of());
        assertEquals("b:2", conns.get("connections").get(0).get("name").asString());
        assertEquals(1, ok("list_network_connections", Map.of("swiftlet", "sys$routing")).get("total").asInt());

        JsonNode jms = ok("network_summary", Map.of()).get("swiftlets").get("sys$jms");
        assertEquals(2, jms.get("connections").asInt());
        assertEquals(10.0, jms.get("throughputInKBs").asDouble());
        assertEquals(1.5, jms.get("throughputOutKBs").asDouble());
    }

    @Test
    void healthReportsIssues() {
        usageList("router", "routerdlq", "unroutable", "rt$r2", "orders", "tmp$1-9");
        queue("router", "routerdlq", 4);
        queue("router", "unroutable", 0);
        queue("router", "rt$r2", 120);
        queue("router", "orders", 50);
        queue("router", "tmp$1-9", 999);
        cli.context("router", "/.env/router-memory-list", List.of("router"), Map.of());
        cli.context("router", "/.env/router-memory-list/router", List.of(), Map.of("free-memory", "5", "total-memory", "100"));
        cli.context("router", "/sys$routing/usage/connections", List.of(), Map.of());

        JsonNode h = ok("network_health", Map.of());
        JsonNode row = h.get("routers").get(0);
        assertEquals(4, row.get("deadLetterQueue").asInt());
        assertEquals(120, row.get("routingBacklog").get("r2").asInt());
        assertEquals("rt$r2", row.get("fullestQueues").get(0).get("queue").asString());
        assertFalse(row.get("fullestQueues").toString().contains("tmp$"), "temporary queues are ignored");
        String issues = h.get("issues").toString();
        assertTrue(issues.contains("memory 95.0% used"), issues);
        assertTrue(issues.contains("4 message(s) in the dead letter queue"), issues);
        assertTrue(issues.contains("120 message(s) waiting to be forwarded to router 'r2'"), issues);
        assertFalse(issues.contains("unroutable message"), issues);
    }

    @Test
    void browsingAndChangingContext() {
        cli.context("router", "/sys$queuemanager", List.of(".metadata", "queues", "usage"), Map.of("collect-interval", "1000"));
        cli.context("router", "/sys$queuemanager/usage", List.of("q"), Map.of());
        cli.context("router", "/sys$jms", List.of(), Map.of());

        JsonNode root = ok("browse_context", Map.of());
        assertEquals("/", root.get("path").asString());
        assertEquals(List.of("sys$jms", "sys$queuemanager"), strings(root.get("swiftlets")));

        JsonNode qm = ok("browse_context", Map.of("path", "/sys$queuemanager"));
        assertEquals(List.of("queues", "usage"), strings(qm.get("children")), ".metadata is hidden");
        assertEquals(1000, qm.get("properties").get("collect-interval").asInt());
        assertEquals("entity", qm.get("type").asString());

        assertEquals("/sys$queuemanager", ok("change_context", Map.of("path", "/sys$queuemanager")).get("currentContext").asString());
        JsonNode usage = ok("browse_context", Map.of("path", "usage"));
        assertEquals("/sys$queuemanager/usage", usage.get("path").asString());
        assertEquals("list", usage.get("type").asString());
        assertEquals("/sys$queuemanager", ok("browse_context", Map.of()).get("path").asString());

        assertEquals("Access to sys$mgmt is not allowed", error("browse_context", Map.of("path", "../sys$mgmt")));
        assertTrue(error("change_context", Map.of("path", "/SYS$Authentication")).endsWith("is not allowed"));
        assertTrue(error("change_context", Map.of("path", "nope")).contains("doesn't exist"));
        assertEquals("/sys$queuemanager", ctx.nav.currentContext().toString(), "failed changes keep the context");
        assertEquals("/", ok("change_context", Map.of("path", "..")).get("currentContext").asString());
        assertFalse(gateway.reads.stream().anyMatch(p -> p.toLowerCase().contains("sys$mgmt") || p.toLowerCase().contains("sys$authentication")),
                "denied paths never reach the gateway: " + gateway.reads);
    }

    @Test
    void networkWideToolsNeverReportAnEmptyNetworkWhenNothingIsAvailable() {
        gateway.available = new TreeSet<>();
        for (String tool : List.of("network_topology", "network_health", "find_queue", "get_router_memory", "network_summary", "list_queues")) {
            Map<String, Object> args = switch (tool) {
                case "find_queue" -> Map.of("pattern", "*");
                case "get_router_memory", "network_summary" -> Map.of("all", true);
                default -> Map.of();
            };
            assertTrue(error(tool, args).contains("no router is available yet"), tool);
        }
    }

    static List<List<String>> links(JsonNode array) {
        List<List<String>> l = new ArrayList<>();
        array.forEach(pair -> l.add(strings(pair)));
        return l;
    }
}
