package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.access.MgmtPath;
import com.swiftmq.mcp.access.SwiftMqAccessException;
import com.swiftmq.mcp.catalog.Catalog;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static com.swiftmq.mcp.tools.ToolFactory.schema;
import static com.swiftmq.mcp.tools.ToolFactory.tool;

/** Router environment (.env): release, OS, startup time, smart tree, memory, available swiftlets. */
final class RouterTools {
    static final MgmtPath ENV = MgmtPath.of("/" + Catalog.ENV);
    static final MgmtPath MEMORY = ENV.child("router-memory-list");

    private RouterTools() {
    }

    static List<SyncToolSpecification> create(ToolContext ctx) {
        return List.of(
                tool("get_router_memory", "Router memory",
                        "Shows the JVM memory of a router: free, total and used KB and used percent. With all=true it covers every "
                                + "available router, sorted by used percent (highest first).",
                        schema("\"all\":{\"type\":\"boolean\",\"description\":\"All available routers instead of one. Default false.\"}"),
                        args -> routerMemory(ctx, args)),
                tool("get_router_info", "Router info",
                        "Shows a router's release, operating system, startup time, whether the smart management tree is on "
                                + "(it hides consumer/producer details), its memory, and which swiftlets (management areas) it has.",
                        schema(),
                        args -> routerInfo(ctx, args)));
    }

    static Map<String, Object> routerMemory(ToolContext ctx, Args args) {
        if (!args.optBoolean("all", false))
            return memory(ctx, ctx.router(args));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String router : ctx.reader.gateway().availableRouters()) {
            try {
                rows.add(memory(ctx, router));
            } catch (SwiftMqAccessException e) {
                rows.add(new LinkedHashMap<>(Map.of("router", router, "error", e.getMessage())));
            }
        }
        rows.sort(Comparator.comparing((Map<String, Object> m) -> m.get("usedPercent") instanceof BigDecimal b ? b : BigDecimal.valueOf(-1)).reversed());
        return Map.of("routers", rows);
    }

    static Map<String, Object> memory(ToolContext ctx, String router) {
        var list = ctx.require(router, MEMORY, "Memory information is not available on router '" + router + "'");
        for (String entry : list.children()) {
            Optional<Map<String, Object>> props = ctx.read(router, MEMORY.child(entry)).map(e -> e.properties());
            if (props.isPresent() && props.get().get("free-memory") instanceof Integer free
                    && props.get().get("total-memory") instanceof Integer total) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("router", router);
                r.put("freeKB", free);
                r.put("totalKB", total);
                r.put("usedKB", total - free);
                r.put("usedPercent", total == 0 ? BigDecimal.ZERO
                        : BigDecimal.valueOf((total - free) * 100.0 / total).setScale(1, RoundingMode.HALF_UP));
                return r;
            }
        }
        throw new SwiftMqAccessException("Memory information is not available on router '" + router + "'");
    }

    static Map<String, Object> routerInfo(ToolContext ctx, Args args) {
        String router = ctx.router(args);
        var env = ctx.require(router, ENV, "Router environment is not available on router '" + router + "'");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("router", router);
        r.putAll(env.properties());
        try {
            Map<String, Object> mem = new LinkedHashMap<>(memory(ctx, router));
            mem.remove("router");
            r.put("memory", mem);
        } catch (SwiftMqAccessException e) {
            r.put("memory", "not available");
        }
        List<String> swiftlets = new ArrayList<>();
        for (String swiftlet : ctx.catalog.swiftlets(Catalog.CE).keySet()) {
            try {
                if (ctx.read(router, MgmtPath.of("/" + swiftlet)).isPresent())
                    swiftlets.add(swiftlet);
            } catch (SwiftMqAccessException.AccessDenied denied) {
                // additionally denied by configuration
            }
        }
        r.put("swiftlets", swiftlets);
        if (!Boolean.FALSE.equals(env.properties().get("use-smart-tree")))
            r.put("hint", ToolContext.smartTreeHint(router));
        return r;
    }
}
