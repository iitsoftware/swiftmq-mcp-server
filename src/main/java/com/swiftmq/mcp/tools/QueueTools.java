package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.access.ManagementReader.EntityView;
import com.swiftmq.mcp.access.MgmtPath;
import com.swiftmq.mcp.access.SwiftMqAccessException;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.*;
import java.util.function.Predicate;

import static com.swiftmq.mcp.tools.ToolFactory.schema;
import static com.swiftmq.mcp.tools.ToolFactory.schemaRequired;
import static com.swiftmq.mcp.tools.ToolFactory.tool;

/** Queue Manager: queue list with live counters, and queue details. */
final class QueueTools {
    static final MgmtPath USAGE = MgmtPath.of("/sys$queuemanager/usage");
    static final List<String> LIST_FIELDS = List.of("messagecount", "msg-produce-rate", "msg-consume-rate",
            "total-produced", "total-consumed", "latency", "active");
    /** Config lists that can define a queue, with the type reported for it. */
    private static final List<Map.Entry<String, String>> CONFIG_LISTS = List.of(
            Map.entry("queues", "regular"), Map.entry("clustered-queues", "clustered"), Map.entry("composite-queues", "composite"));

    private QueueTools() {
    }

    static List<SyncToolSpecification> create(ToolContext ctx) {
        return List.of(
                tool("list_queues", "List queues",
                        "Lists the active queues of a router with live counters: messagecount, produce/consume rates (msgs/s), "
                                + "total produced/consumed, latency (ms) and whether delivery is active. Sorted by messagecount (largest first) "
                                + "by default. Temporary queues (tmp$...) are left out unless include_temporary is true. "
                                + "Includes system queues such as routerdlq (dead letter queue) and unroutable. "
                                + "'delta' is the change of messagecount since the previous list_queues call for that router.",
                        schema("\"pattern\":{\"type\":\"string\",\"description\":\"Glob filter on the queue name, e.g. 'order*'. Case-insensitive.\"}",
                                "\"include_temporary\":{\"type\":\"boolean\",\"description\":\"Include temporary queues (tmp$...). Default false.\"}",
                                "\"sort\":{\"type\":\"string\",\"enum\":[\"messagecount\",\"name\"],\"description\":\"Default messagecount.\"}",
                                "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"description\":\"Max. queues returned, default 100.\"}"),
                        args -> listQueues(ctx, args)),
                tool("find_queue", "Find queue",
                        "Searches all available routers for queues whose name matches a glob pattern and returns router, "
                                + "queue and messagecount. Useful to locate a queue in a router network.",
                        "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\",\"description\":\"Glob, e.g. 'orders*'. Case-insensitive.\"}},\"required\":[\"pattern\"]}",
                        args -> findQueue(ctx, args)),
                tool("get_queue", "Get queue",
                        "Shows one queue: live usage counters, its configuration (regular, clustered or composite, incl. bindings), "
                                + "and the active receivers, senders and browsers (user, client id, selector). "
                                + "Consumer details need use-smart-tree=\"false\" on the router; otherwise a hint is returned.",
                        schemaRequired(new String[]{"queue"}, "\"queue\":{\"type\":\"string\",\"description\":\"Queue name without @router, e.g. 'orders'\"}"),
                        args -> getQueue(ctx, args)));
    }

    static Map<String, Object> listQueues(ToolContext ctx, Args args) {
        String router = ctx.router(args);
        Predicate<String> match = ToolContext.glob(args.optString("pattern"));
        boolean includeTemporary = args.optBoolean("include_temporary", false);
        String sort = Optional.ofNullable(args.optString("sort")).orElse("messagecount");
        if (!sort.equals("messagecount") && !sort.equals("name"))
            throw new IllegalArgumentException("sort must be 'messagecount' or 'name'");
        int limit = args.optInt("limit", 100, 1, ctx.maxListItems);

        EntityView usage = ctx.require(router, USAGE, "The queue manager usage is not available on router '" + router + "'");
        List<Map<String, Object>> queues = new ArrayList<>();
        for (String name : usage.children()) {
            if (!match.test(name) || (!includeTemporary && name.startsWith("tmp$")))
                continue;
            ctx.read(router, USAGE.child(name)).ifPresent(q -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                for (String f : LIST_FIELDS)
                    if (q.properties().containsKey(f))
                        row.put(f, q.properties().get(f));
                queues.add(row);
            });
        }
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> q : queues)
            if (q.get("messagecount") instanceof Number n)
                counts.put(q.get("name").toString(), n.longValue());
        if (args.optString("pattern") == null && !includeTemporary) {
            // only full listings update the baseline, so filtered calls don't distort the next delta
            Map<String, Long> previous = ctx.swapBaseline(router, counts);
            for (Map<String, Object> q : queues) {
                Long before = previous.get(q.get("name").toString());
                Long now = counts.get(q.get("name").toString());
                if (before != null && now != null)
                    q.put("delta", now - before);
            }
        }
        Comparator<Map<String, Object>> byName = Comparator.comparing(q -> q.get("name").toString());
        queues.sort(sort.equals("name") ? byName
                : Comparator.<Map<String, Object>>comparingLong(q -> q.get("messagecount") instanceof Number n ? n.longValue() : -1).reversed().thenComparing(byName));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("router", router);
        r.put("activeQueues", usage.childrenTotal());
        r.put("matched", queues.size());
        r.put("returned", Math.min(limit, queues.size()));
        if (usage.truncated() || queues.size() > limit)
            r.put("truncated", true);
        r.put("queues", queues.subList(0, Math.min(limit, queues.size())));
        return r;
    }

    static Map<String, Object> findQueue(ToolContext ctx, Args args) {
        Predicate<String> match = ToolContext.glob(args.string("pattern"));
        List<Map<String, Object>> found = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String router : ctx.reader.gateway().availableRouters()) {
            try {
                Optional<EntityView> usage = ctx.read(router, USAGE);
                if (usage.isEmpty())
                    continue;
                for (String name : usage.get().children())
                    if (match.test(name)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("router", router);
                        row.put("queue", name);
                        row.put("messagecount", ctx.messageCount(router, name));
                        found.add(row);
                    }
            } catch (SwiftMqAccessException e) {
                errors.add(Map.of("router", router, "error", e.getMessage()));
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("found", found.size() > ctx.maxListItems ? found.subList(0, ctx.maxListItems) : found);
        if (found.size() > ctx.maxListItems)
            r.put("truncated", true);
        if (!errors.isEmpty())
            r.put("errors", errors);
        return r;
    }

    static Map<String, Object> getQueue(ToolContext ctx, Args args) {
        String router = ctx.router(args);
        String queue = MgmtPath.validateName(args.string("queue"), "queue name");
        if (queue.contains("@"))
            queue = queue.substring(0, queue.indexOf('@'));

        Optional<EntityView> usage = ctx.read(router, USAGE.child(queue));
        Map<String, Object> config = null;
        String type = null;
        for (Map.Entry<String, String> list : CONFIG_LISTS) {
            MgmtPath path = MgmtPath.of("/sys$queuemanager/" + list.getKey()).child(queue);
            Optional<EntityView> cfg = ctx.read(router, path);
            if (cfg.isPresent()) {
                type = list.getValue();
                config = new LinkedHashMap<>(cfg.get().properties());
                for (String bindings : cfg.get().children())
                    config.put(bindings, ctx.entries(router, path.child(bindings)));
                break;
            }
        }
        if (usage.isEmpty() && config == null)
            throw new SwiftMqAccessException("Queue '" + queue + "' not found on router '" + router + "'");

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("router", router);
        r.put("queue", queue);
        r.put("type", type == null ? (queue.startsWith("tmp$") ? "temporary" : "system or dynamic (not configured)") : type);
        r.put("usage", usage.map(u -> (Object) u.properties()).orElse(null));   // null: not loaded on the router
        r.put("config", config);
        if (usage.isPresent()) {
            List<String> sublists = usage.get().children();
            if (sublists.isEmpty() && ctx.smartTree(router)) {
                r.put("consumers", "not available");
                r.put("hint", ToolContext.smartTreeHint(router));
            } else {
                MgmtPath base = USAGE.child(queue);
                r.put("receivers", sublists.contains("receiver") ? ctx.entries(router, base.child("receiver")) : List.of());
                r.put("senders", sublists.contains("sender") ? ctx.entries(router, base.child("sender")) : List.of());
                r.put("browsers", sublists.contains("browser") ? ctx.entries(router, base.child("browser")) : List.of());
            }
        }
        return r;
    }
}
