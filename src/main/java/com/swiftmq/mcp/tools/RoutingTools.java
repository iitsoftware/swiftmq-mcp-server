package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.access.ManagementReader.EntityView;
import com.swiftmq.mcp.access.MgmtPath;
import com.swiftmq.mcp.access.SwiftMqAccessException;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.*;

import static com.swiftmq.mcp.tools.ToolFactory.schema;
import static com.swiftmq.mcp.tools.ToolFactory.tool;

/** Routing swiftlet: router-to-router connections, routing table, routing configuration, network topology. */
final class RoutingTools {
    static final MgmtPath ROUTING = MgmtPath.of("/sys$routing");
    static final MgmtPath CONNECTIONS = MgmtPath.of("/sys$routing/usage/connections");
    static final MgmtPath ROUTING_TABLE = MgmtPath.of("/sys$routing/usage/routing-table");

    private RoutingTools() {
    }

    static List<SyncToolSpecification> create(ToolContext ctx) {
        return List.of(
                tool("list_routing_connections", "Routing connections",
                        "Lists the active router-to-router connections of a router: connected router name, connect time, protocol "
                                + "version and whether XA (2-phase commit) is used. Outgoing connections (via a connector) show the "
                                + "router name and remote address only, because the CLI can't read their details.",
                        schema(),
                        args -> connections(ctx, args)),
                tool("get_routing_table", "Routing table",
                        "Shows the routing table of a router: every reachable destination router and the dynamic routes (hops) to it.",
                        schema("\"destination\":{\"type\":\"string\",\"description\":\"Only this destination router.\"}"),
                        args -> routingTable(ctx, args)),
                tool("get_routing_config", "Routing configuration",
                        "Shows the routing configuration of a router: listeners (port, options), connectors (target host/port, "
                                + "enabled, retry time), static routes, route filters and the route announce hop limit. Passwords are redacted.",
                        schema(),
                        args -> routingConfig(ctx, args)),
                tool("network_topology", "Network topology",
                        "Builds the topology of the whole router network from the routing connections of every available router: "
                                + "nodes, links, and issues such as enabled connectors without an active (outgoing) connection, one-way links, "
                                + "and routers that other routers are connected to but that aren't available.",
                        "{\"type\":\"object\",\"properties\":{}}",
                        args -> topology(ctx)));
    }

    static Map<String, Object> connections(ToolContext ctx, Args args) {
        String router = ctx.router(args);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("router", router);
        r.put("connections", connectionEntries(ctx, router));
        return r;
    }

    /**
     * Routing connections. Outgoing connector connections are named {@code <router>/<host:port>} and can't be read
     * through the CLI; their router name and remote address are taken from the name.
     */
    static List<Map<String, Object>> connectionEntries(ToolContext ctx, String router) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> c : ctx.entries(router, CONNECTIONS)) {
            Map<String, Object> row = new LinkedHashMap<>(c);
            String name = c.get("name").toString();
            int slash = name.indexOf('/');
            if (slash > 0) {
                row.putIfAbsent("routername", name.substring(0, slash));
                row.put("remoteAddress", name.substring(slash + 1));
                row.put("direction", "outgoing");
            }
            result.add(row);
        }
        return result;
    }

    static Map<String, Object> routingTable(ToolContext ctx, Args args) {
        String router = ctx.router(args);
        String only = args.optString("destination");
        EntityView table = ctx.require(router, ROUTING_TABLE, "The routing table is not available on router '" + router + "'");
        List<Map<String, Object>> destinations = new ArrayList<>();
        for (String destination : table.children()) {
            if (only != null && !only.equals(destination))
                continue;
            MgmtPath routes = ROUTING_TABLE.child(destination).child("dynamic-routes");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("destination", destination);
            row.put("dynamicRoutes", ctx.read(router, routes).map(EntityView::children).orElse(List.of()));
            destinations.add(row);
        }
        if (only != null && destinations.isEmpty())
            throw new SwiftMqAccessException("Router '" + router + "' has no route to '" + only + "'");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("router", router);
        r.put("destinations", destinations);
        return r;
    }

    static Map<String, Object> routingConfig(ToolContext ctx, Args args) {
        String router = ctx.router(args);
        EntityView routing = ctx.require(router, ROUTING, "The routing swiftlet is not available on router '" + router + "'");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("router", router);
        r.put("settings", routing.properties());
        r.put("listeners", ctx.entries(router, ROUTING.child("listeners")));
        r.put("connectors", ctx.entries(router, ROUTING.child("connectors")));
        r.put("staticRoutes", ctx.read(router, ROUTING.child("static-routes")).map(EntityView::children).orElse(List.of()));
        List<Map<String, Object>> filters = new ArrayList<>();
        for (Map<String, Object> f : ctx.entries(router, ROUTING.child("filters"))) {
            Map<String, Object> row = new LinkedHashMap<>(f);
            MgmtPath routers = ROUTING.child("filters").child(f.get("name").toString()).child("routers");
            row.put("routers", ctx.read(router, routers).map(EntityView::children).orElse(List.of()));
            filters.add(row);
        }
        r.put("filters", filters);
        return r;
    }

    static Map<String, Object> topology(ToolContext ctx) {
        SortedSet<String> available = ctx.allRouters();
        Map<String, Set<String>> links = new TreeMap<>();
        Set<String> seen = new TreeSet<>(available);
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        for (String router : available) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("router", router);
            try {
                Set<String> peers = new TreeSet<>();
                long outgoing = 0;
                for (Map<String, Object> c : connectionEntries(ctx, router)) {
                    if (c.get("routername") != null)
                        peers.add(c.get("routername").toString());
                    if ("outgoing".equals(c.get("direction")))
                        outgoing++;
                }
                links.put(router, peers);
                seen.addAll(peers);
                long enabledConnectors = ctx.entries(router, ROUTING.child("connectors")).stream()
                        .filter(c -> !Boolean.FALSE.equals(c.get("enabled"))).count();
                node.put("connectedTo", peers);
                node.put("enabledConnectors", enabledConnectors);
                node.put("outgoingConnections", outgoing);
                if (enabledConnectors > outgoing)
                    issues.add(router + ": " + (enabledConnectors - outgoing) + " of " + enabledConnectors
                            + " enabled connector(s) have no active connection");
            } catch (SwiftMqAccessException e) {
                node.put("error", e.getMessage());
                issues.add(router + ": " + e.getMessage());
            }
            nodes.add(node);
        }
        Set<List<String>> edges = new LinkedHashSet<>();
        links.forEach((a, peers) -> peers.forEach(b -> {
            edges.add(a.compareTo(b) < 0 ? List.of(a, b) : List.of(b, a));
            if (links.containsKey(b) && !links.get(b).contains(a))
                issues.add("one-way link: " + a + " lists " + b + ", but " + b + " doesn't list " + a);
        }));
        for (String r : seen)
            if (!available.contains(r))
                issues.add(r + ": connected to by other routers but not available");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("routers", nodes);
        result.put("links", edges);
        result.put("issues", issues);
        return result;
    }
}
