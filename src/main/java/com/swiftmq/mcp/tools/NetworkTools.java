package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.access.MgmtPath;
import com.swiftmq.mcp.access.SwiftMqAccessException;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.math.BigDecimal;
import java.util.*;

import static com.swiftmq.mcp.tools.ToolFactory.schema;
import static com.swiftmq.mcp.tools.ToolFactory.tool;

/** Network swiftlet: all TCP connections of a router with owning swiftlet and throughput. */
final class NetworkTools {
    static final MgmtPath NET_USAGE = MgmtPath.of("/sys$net/usage");

    private NetworkTools() {
    }

    static List<SyncToolSpecification> create(ToolContext ctx) {
        return List.of(
                tool("list_network_connections", "Network connections",
                        "Lists the network connections of a router (host:port) with the owning swiftlet (sys$jms, sys$amqp, sys$mqtt, "
                                + "sys$routing, ...), connect time and throughput in/out in KB/s. Sorted by total throughput, highest first.",
                        schema("\"swiftlet\":{\"type\":\"string\",\"description\":\"Only connections of this swiftlet, e.g. 'sys$jms'.\"}",
                                "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"description\":\"Max. connections returned, default 100.\"}"),
                        args -> connections(ctx, args)),
                tool("network_summary", "Network summary",
                        "Summarizes the network connections per swiftlet: number of connections and total throughput in/out (KB/s). "
                                + "With all=true for every available router.",
                        schema("\"all\":{\"type\":\"boolean\",\"description\":\"All available routers instead of one. Default false.\"}"),
                        args -> summary(ctx, args)));
    }

    static Map<String, Object> connections(ToolContext ctx, Args args) {
        String router = ctx.router(args);
        String swiftlet = args.optString("swiftlet");
        int limit = args.optInt("limit", 100, 1, ctx.maxListItems);
        List<Map<String, Object>> rows = new ArrayList<>(ctx.entries(router, NET_USAGE).stream()
                .filter(c -> swiftlet == null || swiftlet.equalsIgnoreCase(String.valueOf(c.get("swiftlet"))))
                .toList());
        rows.sort(Comparator.comparing(NetworkTools::throughput).reversed());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("router", router);
        r.put("total", rows.size());
        r.put("connections", rows.subList(0, Math.min(limit, rows.size())));
        if (rows.size() > limit)
            r.put("truncated", true);
        return r;
    }

    static Map<String, Object> summary(ToolContext ctx, Args args) {
        if (!args.optBoolean("all", false))
            return summary(ctx, ctx.router(args));
        List<Map<String, Object>> routers = new ArrayList<>();
        for (String router : ctx.allRouters()) {
            try {
                routers.add(summary(ctx, router));
            } catch (SwiftMqAccessException e) {
                routers.add(Map.of("router", router, "error", e.getMessage()));
            }
        }
        return Map.of("routers", routers);
    }

    private static Map<String, Object> summary(ToolContext ctx, String router) {
        Map<String, Map<String, Object>> bySwiftlet = new TreeMap<>();
        for (Map<String, Object> c : ctx.entries(router, NET_USAGE)) {
            Map<String, Object> s = bySwiftlet.computeIfAbsent(String.valueOf(c.get("swiftlet")), k -> new LinkedHashMap<>(
                    Map.of("connections", 0, "throughputInKBs", BigDecimal.ZERO, "throughputOutKBs", BigDecimal.ZERO)));
            s.put("connections", (Integer) s.get("connections") + 1);
            s.put("throughputInKBs", ((BigDecimal) s.get("throughputInKBs")).add(decimal(c.get("throughput-input"))));
            s.put("throughputOutKBs", ((BigDecimal) s.get("throughputOutKBs")).add(decimal(c.get("throughput-output"))));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("router", router);
        r.put("swiftlets", bySwiftlet);
        return r;
    }

    private static BigDecimal throughput(Map<String, Object> c) {
        return decimal(c.get("throughput-input")).add(decimal(c.get("throughput-output")));
    }

    private static BigDecimal decimal(Object v) {
        return v instanceof BigDecimal b ? b : BigDecimal.ZERO;
    }
}
