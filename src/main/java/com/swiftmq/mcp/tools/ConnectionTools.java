package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.access.MgmtPath;
import com.swiftmq.mcp.access.SwiftMqAccessException;
import com.swiftmq.mcp.access.SwiftMqGateway.ConnectionStatus;
import com.swiftmq.mcp.server.SwiftMqMcpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import static com.swiftmq.mcp.tools.ToolFactory.tool;

/** Connection state, router list and router selection. */
final class ConnectionTools {
    private ConnectionTools() {
    }

    static List<SyncToolSpecification> create(ToolContext ctx) {
        return List.of(
                tool("connection_info", "Connection info",
                        "Shows the SwiftMQ connection: URL, user, state (CONNECTING/CONNECTED/DISCONNECTED) with the last error, "
                                + "the routers available in the network, and the selected router. Use it first if other tools report connection problems.",
                        "{\"type\":\"object\",\"properties\":{}}",
                        args -> connectionInfo(ctx)),
                tool("list_routers", "List routers",
                        "Lists all routers currently available in the router network, as reported by the connected router. "
                                + "Every tool takes an optional 'router' argument naming one of them.",
                        "{\"type\":\"object\",\"properties\":{}}",
                        args -> listRouters(ctx)),
                tool("select_router", "Select router",
                        "Sets the default router for all following tool calls that don't pass 'router'.",
                        "{\"type\":\"object\",\"properties\":{\"router\":{\"type\":\"string\",\"description\":\"Name of an available router\"}},\"required\":[\"router\"]}",
                        args -> selectRouter(ctx, args)));
    }

    static Map<String, Object> connectionInfo(ToolContext ctx) {
        ConnectionStatus s = ctx.reader.gateway().status();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("url", s.url());
        r.put("user", s.user());
        r.put("state", s.state());
        r.put("since", s.since().toString());
        if (s.lastError() != null)
            r.put("lastError", s.lastError());
        r.put("availableRouters", ctx.reader.gateway().availableRouters());
        r.put("selectedRouter", ctx.nav.currentRouter());
        r.put("server", SwiftMqMcpServer.NAME + " " + SwiftMqMcpServer.version());
        r.put("catalogRelease", ctx.catalog.release());
        return r;
    }

    static Map<String, Object> listRouters(ToolContext ctx) {
        SortedSet<String> routers = ctx.reader.gateway().availableRouters();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("routers", routers);
        r.put("selectedRouter", ctx.nav.currentRouter());
        r.put("connectionState", ctx.reader.gateway().status().state());
        return r;
    }

    static Map<String, Object> selectRouter(ToolContext ctx, Args args) {
        String router = MgmtPath.validateName(args.string("router"), "router name");
        SortedSet<String> available = ctx.reader.gateway().availableRouters();
        if (!available.contains(router))
            throw new SwiftMqAccessException.RouterUnavailable(router);
        ctx.nav.selectRouter(router);
        return Map.of("selectedRouter", router);
    }
}
