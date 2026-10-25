package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.access.MgmtPath;
import com.swiftmq.mcp.access.SwiftMqAccessException;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.*;
import java.util.function.Predicate;

import static com.swiftmq.mcp.tools.ToolFactory.schema;
import static com.swiftmq.mcp.tools.ToolFactory.tool;

/** Topic Manager: topics with subscriber counts. */
final class TopicTools {
    static final MgmtPath TOPICS = MgmtPath.of("/sys$topicmanager/topics");
    static final MgmtPath SUBSCRIBERS = MgmtPath.of("/sys$topicmanager/usage/subscriber");
    static final MgmtPath DURABLES = MgmtPath.of("/sys$topicmanager/usage/durables");

    private TopicTools() {
    }

    static List<SyncToolSpecification> create(ToolContext ctx) {
        return List.of(
                tool("list_topics", "List topics",
                        "Lists the topics of a router: configured topics plus topics that currently have subscribers, with the number "
                                + "of active (non-durable) subscribers and durable subscribers per topic. Internal topics used by the router "
                                + "itself (e.g. swiftmq.jndi, stream_*) are included and marked configured=false.",
                        schema("\"pattern\":{\"type\":\"string\",\"description\":\"Glob filter on the topic name. Case-insensitive.\"}"),
                        args -> listTopics(ctx, args)),
                tool("get_topic", "Get topic",
                        "Shows one topic: whether it is configured, its active subscribers (client id, selector, no-local, bound queue) "
                                + "and its durable subscribers with their backlog (messages waiting in the durable's queue).",
                        ToolFactory.schemaRequired(new String[]{"topic"}, "\"topic\":{\"type\":\"string\",\"description\":\"Topic name, e.g. 'orders' or 'orders.eu'\"}"),
                        args -> getTopic(ctx, args)),
                tool("list_durable_subscribers", "List durable subscribers",
                        "Lists the durable subscribers of a router (client id, durable name, topic, selector) with their backlog: "
                                + "the number of messages waiting in the queue the durable is bound to. Sorted by backlog, largest first. "
                                + "A growing backlog usually means the subscriber is offline or too slow.",
                        schema("\"pattern\":{\"type\":\"string\",\"description\":\"Glob filter on topic or clientid$durablename. Case-insensitive.\"}"),
                        args -> listDurables(ctx, args)));
    }

    static Map<String, Object> listTopics(ToolContext ctx, Args args) {
        String router = ctx.router(args);
        Predicate<String> match = ToolContext.glob(args.optString("pattern"));
        Set<String> configured = new TreeSet<>(ctx.read(router, TOPICS).map(v -> v.children()).orElse(List.of()));
        Map<String, Integer> subscribers = countByTopic(ctx.entries(router, SUBSCRIBERS));
        Map<String, Integer> durables = countByTopic(ctx.entries(router, DURABLES));

        Set<String> all = new TreeSet<>(configured);
        all.addAll(subscribers.keySet());
        all.addAll(durables.keySet());
        List<Map<String, Object>> topics = new ArrayList<>();
        for (String topic : all) {
            if (!match.test(topic))
                continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", topic);
            row.put("configured", configured.contains(topic));
            row.put("subscribers", subscribers.getOrDefault(topic, 0));
            row.put("durableSubscribers", durables.getOrDefault(topic, 0));
            topics.add(row);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("router", router);
        r.put("topics", topics.size() > ctx.maxListItems ? topics.subList(0, ctx.maxListItems) : topics);
        if (topics.size() > ctx.maxListItems)
            r.put("truncated", true);
        return r;
    }

    static Map<String, Object> getTopic(ToolContext ctx, Args args) {
        String router = ctx.router(args);
        String topic = args.string("topic");
        boolean configured = ctx.read(router, TOPICS).map(v -> v.children().contains(topic)).orElse(false);
        List<Map<String, Object>> subscribers = ctx.entries(router, SUBSCRIBERS).stream()
                .filter(e -> topic.equals(e.get("topic"))).toList();
        List<Map<String, Object>> durables = withBacklog(ctx, router, ctx.entries(router, DURABLES).stream()
                .filter(e -> topic.equals(e.get("topic"))).toList());
        if (!configured && subscribers.isEmpty() && durables.isEmpty())
            throw new SwiftMqAccessException("Topic '" + topic + "' is neither configured nor in use on router '" + router + "'");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("router", router);
        r.put("topic", topic);
        r.put("configured", configured);
        r.put("subscribers", subscribers);
        r.put("durableSubscribers", durables);
        return r;
    }

    static Map<String, Object> listDurables(ToolContext ctx, Args args) {
        String router = ctx.router(args);
        Predicate<String> match = ToolContext.glob(args.optString("pattern"));
        List<Map<String, Object>> durables = withBacklog(ctx, router, ctx.entries(router, DURABLES).stream()
                .filter(e -> match.test(e.get("name").toString()) || (e.get("topic") != null && match.test(e.get("topic").toString())))
                .toList());
        List<Map<String, Object>> sorted = new ArrayList<>(durables);
        sorted.sort(Comparator.comparingLong((Map<String, Object> d) -> d.get("backlog") instanceof Integer i ? i : -1).reversed());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("router", router);
        r.put("durableSubscribers", sorted.size() > ctx.maxListItems ? sorted.subList(0, ctx.maxListItems) : sorted);
        if (sorted.size() > ctx.maxListItems)
            r.put("truncated", true);
        return r;
    }

    /** Adds {@code backlog}: messagecount of the queue the durable is bound to. */
    private static List<Map<String, Object>> withBacklog(ToolContext ctx, String router, List<Map<String, Object>> durables) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> d : durables) {
            Map<String, Object> row = new LinkedHashMap<>(d);
            Object queue = d.get("boundto");
            row.put("backlog", queue == null ? null : ctx.messageCount(router, queue.toString()));
            result.add(row);
        }
        return result;
    }

    private static Map<String, Integer> countByTopic(List<Map<String, Object>> entries) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Map<String, Object> e : entries)
            if (e.get("topic") != null)
                counts.merge(e.get("topic").toString(), 1, Integer::sum);
        return counts;
    }
}
