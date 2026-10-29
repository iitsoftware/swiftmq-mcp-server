package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.access.FakeCli;
import com.swiftmq.mcp.access.FakeGateway;
import com.swiftmq.mcp.access.ManagementReader;
import com.swiftmq.mcp.access.NavigationState;
import com.swiftmq.mcp.catalog.Catalog;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Tool logic against a fake management tree, called through the real MCP tool handlers. */
class ToolsTest extends ToolTestBase {

    @Test
    void allToolsAreReadOnly() {
        assertEquals(Set.of("connection_info", "list_routers", "select_router",
                "list_queues", "find_queue", "get_queue",
                "list_topics", "get_topic", "list_durable_subscribers",
                "get_router_memory", "get_router_info",
                "list_routing_connections", "get_routing_table", "get_routing_config", "network_topology",
                "list_network_connections", "network_summary", "network_health",
                "list_swiftlets", "browse_context", "change_context"), tools.keySet());
        for (SyncToolSpecification t : tools.values()) {
            assertTrue(t.tool().annotations().readOnlyHint(), t.tool().name());
            assertFalse(t.tool().annotations().destructiveHint(), t.tool().name());
        }
    }

    @Test
    void listQueuesSortsFiltersAndLimits() {
        usageList("router", "a", "big", "tmp$1-123", "routerdlq");
        queue("router", "a", 3);
        queue("router", "big", 900);
        queue("router", "tmp$1-123", 50);
        queue("router", "routerdlq", 7);

        JsonNode r = ok("list_queues", Map.of());
        assertEquals(4, r.get("activeQueues").asInt());
        assertEquals(List.of("big", "routerdlq", "a"), names(r.get("queues")));
        assertEquals(900, r.get("queues").get(0).get("messagecount").asInt());
        assertFalse(r.get("queues").get(0).has("acache-size"), "only the list fields");

        assertEquals(List.of("a", "big", "routerdlq", "tmp$1-123"), names(ok("list_queues", Map.of("include_temporary", true, "sort", "name")).get("queues")));
        assertEquals(List.of("big"), names(ok("list_queues", Map.of("pattern", "B*")).get("queues")));
        JsonNode limited = ok("list_queues", Map.of("limit", 1));
        assertEquals(1, limited.get("returned").asInt());
        assertTrue(limited.get("truncated").asBoolean());
        assertTrue(error("list_queues", Map.of("sort", "size")).contains("sort"));
    }

    @Test
    void getQueueWithConsumersWhenSmartTreeIsOff() {
        cli.context("router", "/.env", List.of(), Map.of("use-smart-tree", "false"));
        cli.context("router", "/sys$queuemanager/usage/orders", List.of("receiver", "sender"), Map.of("messagecount", "4"));
        cli.context("router", "/sys$queuemanager/usage/orders/receiver", List.of("20"), Map.of());
        cli.context("router", "/sys$queuemanager/usage/orders/receiver/20", List.of(), Map.of("username", "app", "clientid", "c1", "selector", "x > 1"));
        cli.context("router", "/sys$queuemanager/usage/orders/sender", List.of("18"), Map.of());
        cli.context("router", "/sys$queuemanager/usage/orders/sender/18", List.of(), Map.of("username", "app", "clientid", "c1"));
        cli.context("router", "/sys$queuemanager/queues/orders", List.of(), Map.of("persistence-mode", "persistent", "max-messages", "-1"));

        JsonNode r = ok("get_queue", Map.of("queue", "orders@router"));
        assertEquals("regular", r.get("type").asString());
        assertEquals(4, r.get("usage").get("messagecount").asInt());
        assertEquals("persistent", r.get("config").get("persistence-mode").asString());
        assertEquals("x > 1", r.get("receivers").get(0).get("selector").asString());
        assertEquals("c1", r.get("senders").get(0).get("clientid").asString());
        assertEquals(0, r.get("browsers").size());
        assertFalse(r.has("hint"));
    }

    @Test
    void getQueueGivesSmartTreeHint() {
        cli.context("router", "/.env", List.of(), Map.of("use-smart-tree", "true"));
        cli.context("router", "/sys$queuemanager/usage/orders", List.of(), Map.of("messagecount", "4"));
        JsonNode r = ok("get_queue", Map.of("queue", "orders"));
        assertEquals("not available", r.get("consumers").asString());
        assertTrue(r.get("hint").asString().contains("use-smart-tree=\"false\""));
        assertEquals("system or dynamic (not configured)", r.get("type").asString());
    }

    @Test
    void getClusteredQueueWithBindings() {
        cli.context("router", "/sys$queuemanager/clustered-queues/cq", List.of("queue-bindings"), Map.of("message-group-enabled", "false"));
        cli.context("router", "/sys$queuemanager/clustered-queues/cq/queue-bindings", List.of("q1@router1"), Map.of());
        cli.context("router", "/sys$queuemanager/clustered-queues/cq/queue-bindings/q1@router1", List.of(), Map.of("redispatch-enabled", "true"));
        JsonNode r = ok("get_queue", Map.of("queue", "cq"));
        assertEquals("clustered", r.get("type").asString());
        assertTrue(r.get("usage").isNull());
        assertEquals("q1@router1", r.get("config").get("queue-bindings").get(0).get("name").asString());
        assertTrue(r.get("config").get("queue-bindings").get(0).get("redispatch-enabled").asBoolean());
    }

    @Test
    void getQueueErrors() {
        cli.router("router");
        assertEquals("Queue 'nope' not found on router 'router'", error("get_queue", Map.of("queue", "nope")));
        assertTrue(error("get_queue", Map.of()).contains("Missing argument 'queue'"));
        assertTrue(error("get_queue", Map.of("queue", "a;b")).contains("Invalid character"));
    }

    @Test
    void listTopicsCountsSubscribers() {
        cli.context("router", "/sys$topicmanager/topics", List.of("orders", "idle"), Map.of());
        cli.context("router", "/sys$topicmanager/usage/subscriber", List.of("orders-1", "orders-2", "dyn-1"), Map.of());
        cli.context("router", "/sys$topicmanager/usage/subscriber/orders-1", List.of(), Map.of("topic", "orders"));
        cli.context("router", "/sys$topicmanager/usage/subscriber/orders-2", List.of(), Map.of("topic", "orders"));
        cli.context("router", "/sys$topicmanager/usage/subscriber/dyn-1", List.of(), Map.of("topic", "dynamic"));
        cli.context("router", "/sys$topicmanager/usage/durables", List.of("c$d"), Map.of());
        cli.context("router", "/sys$topicmanager/usage/durables/c$d", List.of(), Map.of("topic", "orders"));

        JsonNode topics = ok("list_topics", Map.of()).get("topics");
        assertEquals(List.of("dynamic", "idle", "orders"), names(topics));
        JsonNode orders = topics.get(2);
        assertTrue(orders.get("configured").asBoolean());
        assertEquals(2, orders.get("subscribers").asInt());
        assertEquals(1, orders.get("durableSubscribers").asInt());
        assertFalse(topics.get(0).get("configured").asBoolean());
    }

    @Test
    void routerMemoryForAllRouters() {
        cli.context("r1", "/.env/router-memory-list", List.of("r1"), Map.of());
        cli.context("r1", "/.env/router-memory-list/r1", List.of(), Map.of("free-memory", "25", "total-memory", "100"));
        cli.context("r2", "/.env/router-memory-list", List.of("r2"), Map.of());
        cli.context("r2", "/.env/router-memory-list/r2", List.of(), Map.of("free-memory", "10", "total-memory", "100"));
        gateway.available = new TreeSet<>(Set.of("r1", "r2", "r3"));

        JsonNode rows = ok("get_router_memory", Map.of("all", true)).get("routers");
        assertEquals("r2", rows.get(0).get("router").asString());
        assertEquals(90.0, rows.get(0).get("usedPercent").asDouble());
        assertEquals(75, rows.get(1).get("usedKB").asInt());
        assertEquals("Router 'r3' is not available", rows.get(2).get("error").asString());

        assertTrue(error("get_router_memory", Map.of()).contains("Several routers"));
        assertEquals("r1", ok("get_router_memory", Map.of("router", "r1")).get("router").asString());
    }

    @Test
    void routerInfo() {
        cli.context("router", "/.env", List.of("router-memory-list"), Map.of("release", "13.02.04 Community Edition", "use-smart-tree", "true"));
        cli.context("router", "/sys$queuemanager", List.of(), Map.of());
        cli.context("router", "/sys$routing", List.of(), Map.of());
        JsonNode r = ok("get_router_info", Map.of());
        assertEquals("13.02.04 Community Edition", r.get("release").asString());
        assertEquals("CE", r.get("edition").asString());
        assertEquals(List.of("sys$queuemanager", "sys$routing"), strings(r.get("swiftlets")));
        assertEquals("not available", r.get("memory").asString());
        assertTrue(r.has("hint"));
        assertFalse(gateway.reads.stream().anyMatch(p -> p.contains("sys$authentication") || p.contains("sys$mgmt") || p.contains("sys$timer")),
                "denied swiftlets are never probed: " + gateway.reads);
    }

    @Test
    void universalEditionSwiftlets() {
        cli.context("router", "/.env", List.of(), Map.of("release", "13.02.04 Universal Edition"));
        cli.context("router", "/sys$queuemanager", List.of(), Map.of());
        cli.context("router", "/sys$filecache", List.of("caches", "usage"), Map.of());
        cli.context("router", "/sys$jac", List.of("static-containers", "usage"), Map.of());
        assertEquals("UR", ok("get_router_info", Map.of()).get("edition").asString());
        assertEquals(List.of("sys$filecache", "sys$jac", "sys$queuemanager", ".env"), strings(ok("list_swiftlets", Map.of()).get("swiftlets")));
        JsonNode jac = ok("browse_context", Map.of("path", "/sys$jac"));
        assertEquals("entity", jac.get("type").asString());
        assertFalse(jac.get("description").isNull());
        assertFalse(jac.has("note"));
    }

    @Test
    void sameSwiftletsHiddenOnCommunityEdition() {
        cli.context("router", "/.env", List.of(), Map.of("release", "13.02.04 Community Edition"));
        cli.context("router", "/sys$jac", List.of("usage"), Map.of());
        assertEquals(List.of(".env"), strings(ok("list_swiftlets", Map.of()).get("swiftlets")),
                "a CE router has no sys$jac; the CE catalog doesn't probe it");
        JsonNode jac = ok("browse_context", Map.of("path", "/sys$jac"));
        assertFalse(jac.has("type"));
        assertTrue(jac.get("note").asString().contains("doesn't describe this context"), jac.toString());
        assertEquals(List.of("usage"), strings(jac.get("children")));
    }

    @Test
    void selectRouter() {
        cli.router("r1").router("r2");
        usageList("r2", "q");
        queue("r2", "q", 1);
        assertEquals("Router 'r9' is not available", error("select_router", Map.of("router", "r9")));
        assertEquals("r2", ok("select_router", Map.of("router", "r2")).get("selectedRouter").asString());
        assertEquals("r2", ok("list_queues", Map.of()).get("router").asString());
        assertEquals("r2", ok("connection_info", Map.of()).get("selectedRouter").asString());
        assertEquals(List.of("r1", "r2"), strings(ok("list_routers", Map.of()).get("routers")));
    }

    @Test
    void connectionInfoHasNoPassword() {
        cli.router("router");
        JsonNode r = ok("connection_info", Map.of());
        assertEquals("CONNECTED", r.get("state").asString());
        assertFalse(r.toString().toLowerCase().contains("password"));
    }

}
