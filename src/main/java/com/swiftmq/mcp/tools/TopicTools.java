package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.access.MgmtPath;
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
                        args -> listTopics(ctx, args)));
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

    private static Map<String, Integer> countByTopic(List<Map<String, Object>> entries) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Map<String, Object> e : entries)
            if (e.get("topic") != null)
                counts.merge(e.get("topic").toString(), 1, Integer::sum);
        return counts;
    }
}
