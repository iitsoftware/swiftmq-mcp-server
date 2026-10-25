package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.access.FakeCli;
import com.swiftmq.mcp.access.FakeGateway;
import com.swiftmq.mcp.access.ManagementReader;
import com.swiftmq.mcp.access.NavigationState;
import com.swiftmq.mcp.catalog.Catalog;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Tool logic against a fake management tree, called through the real MCP tool handlers. */
abstract class ToolTestBase {
    static final Catalog CATALOG = Catalog.load();

    final FakeCli cli = new FakeCli();
    final FakeGateway gateway = new FakeGateway(cli);
    final ToolContext ctx = new ToolContext(new ManagementReader(gateway, CATALOG, 500), new NavigationState(), CATALOG, 500);
    final Map<String, SyncToolSpecification> tools = new HashMap<>();

    ToolTestBase() {
        SwiftMqTools.registry(ctx).tools().forEach(t -> tools.put(t.tool().name(), t));
    }

    McpSchema.CallToolResult call(String tool, Map<String, Object> args) {
        return tools.get(tool).callHandler().apply(null, new McpSchema.CallToolRequest(tool, args));
    }

    JsonNode ok(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult r = call(tool, args);
        String text = ((McpSchema.TextContent) r.content().get(0)).text();
        assertFalse(r.isError(), text);
        return ToolFactory.JSON.readTree(text);
    }

    String error(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult r = call(tool, args);
        assertTrue(r.isError());
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }

    FakeCli queue(String router, String name, int count) {
        return cli.context(router, "/sys$queuemanager/usage/" + name, List.of(),
                Map.of("messagecount", String.valueOf(count), "msg-produce-rate", "1", "active", "true", "acache-size", "500"));
    }

    void usageList(String router, String... queues) {
        cli.context(router, "/sys$queuemanager/usage", List.of(queues), Map.of());
    }

    static List<String> names(JsonNode array) {
        List<String> l = new ArrayList<>();
        array.forEach(n -> l.add(n.get("name").asString()));
        return l;
    }

    static List<String> strings(JsonNode array) {
        List<String> l = new ArrayList<>();
        array.forEach(n -> l.add(n.asString()));
        return l;
    }
}
