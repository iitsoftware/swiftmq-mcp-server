package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.access.ManagementReader.EntityView;
import com.swiftmq.mcp.access.SwiftMqAccessException;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.math.BigDecimal;
import java.util.*;

import static com.swiftmq.mcp.tools.ToolFactory.schema;
import static com.swiftmq.mcp.tools.ToolFactory.tool;

/** One overview of the things that usually go wrong in a router network. */
final class HealthTools {
    static final BigDecimal MEMORY_WARN_PERCENT = BigDecimal.valueOf(90);
    static final int TOP_QUEUES = 5;

    private HealthTools() {
    }

    static List<SyncToolSpecification> create(ToolContext ctx) {
        return List.of(tool("network_health", "Network health",
                "Health overview of every available router (or one, with 'router'): messages in the dead letter queue (routerdlq) "
                        + "and in 'unroutable', store-and-forward backlogs toward other routers (rt$<router> queues), the fullest queues, "
                        + "memory usage, and routing problems from the network topology. 'issues' lists what needs attention.",
                schema(),
                args -> health(ctx, args)));
    }

    static Map<String, Object> health(ToolContext ctx, Args args) {
        String only = args.optString("router");
        Collection<String> routers = only != null ? List.of(ctx.router(args)) : ctx.allRouters();
        List<String> issues = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String router : routers) {
            try {
                rows.add(router(ctx, router, issues));
            } catch (SwiftMqAccessException e) {
                rows.add(Map.of("router", router, "error", e.getMessage()));
                issues.add(router + ": " + e.getMessage());
            }
        }
        if (only == null) {
            @SuppressWarnings("unchecked")
            List<String> routing = (List<String>) RoutingTools.topology(ctx).get("issues");
            issues.addAll(routing);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("issues", issues);
        r.put("routers", rows);
        return r;
    }

    private static Map<String, Object> router(ToolContext ctx, String router, List<String> issues) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("router", router);
        try {
            BigDecimal used = (BigDecimal) RouterTools.memory(ctx, router).get("usedPercent");
            row.put("memoryUsedPercent", used);
            if (used.compareTo(MEMORY_WARN_PERCENT) >= 0)
                issues.add(router + ": memory " + used + "% used");
        } catch (SwiftMqAccessException e) {
            row.put("memoryUsedPercent", null);
        }

        EntityView usage = ctx.require(router, QueueTools.USAGE, "The queue manager usage is not available on router '" + router + "'");
        Map<String, Integer> counts = new TreeMap<>();
        for (String queue : usage.children()) {
            if (queue.startsWith("tmp$"))
                continue;
            Integer c = ctx.messageCount(router, queue);
            if (c != null)
                counts.put(queue, c);
        }
        int dlq = counts.getOrDefault("routerdlq", 0);
        int unroutable = counts.getOrDefault("unroutable", 0);
        row.put("deadLetterQueue", dlq);
        row.put("unroutable", unroutable);
        if (dlq > 0)
            issues.add(router + ": " + dlq + " message(s) in the dead letter queue routerdlq");
        if (unroutable > 0)
            issues.add(router + ": " + unroutable + " unroutable message(s)");

        Map<String, Integer> backlog = new TreeMap<>();
        counts.forEach((q, c) -> {
            if (q.startsWith("rt$") && c > 0)
                backlog.put(q.substring(3), c);
        });
        row.put("routingBacklog", backlog);
        backlog.forEach((dest, c) -> issues.add(router + ": " + c + " message(s) waiting to be forwarded to router '" + dest + "'"));

        List<Map<String, Object>> top = counts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(TOP_QUEUES)
                .map(e -> Map.<String, Object>of("queue", e.getKey(), "messagecount", e.getValue()))
                .toList();
        row.put("fullestQueues", top);
        return row;
    }
}
