package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.access.ManagementReader.EntityView;
import com.swiftmq.mcp.access.MgmtPath;
import com.swiftmq.mcp.access.SwiftMqAccessException;
import com.swiftmq.mcp.catalog.Catalog;
import com.swiftmq.mcp.catalog.CatalogNode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.*;

import static com.swiftmq.mcp.tools.ToolFactory.schema;
import static com.swiftmq.mcp.tools.ToolFactory.tool;

/** Generic read-only navigation of the management tree, for everything without a dedicated tool. */
final class BrowseTools {
    private static final String PATH = "\"path\":{\"type\":\"string\",\"description\":\"Absolute (/sys$jms/usage) or relative to the current context (usage, ..).\"}";

    private BrowseTools() {
    }

    static List<SyncToolSpecification> create(ToolContext ctx) {
        return List.of(
                tool("list_swiftlets", "List swiftlets",
                        "Lists the top-level management areas (swiftlets such as sys$queuemanager, sys$jms, sys$routing, and .env) "
                                + "that are available on a router. sys$authentication, sys$mgmt and sys$timer are never accessible.",
                        schema(),
                        args -> swiftlets(ctx, ctx.router(args))),
                tool("browse_context", "Browse management tree",
                        "Shows one context of the management tree: its properties (typed, with passwords redacted) and the names "
                                + "of its child entities. Without 'path' it shows the current context (see change_context). Use it for "
                                + "anything the other tools don't cover, e.g. /sys$jms/usage (JMS connections) or /sys$store.",
                        schema(PATH),
                        args -> browse(ctx, args)),
                tool("change_context", "Change context",
                        "Sets the current context for browse_context, like 'cc' in the SwiftMQ CLI. Accepts absolute or relative "
                                + "paths, including '..'.",
                        ToolFactory.schemaRequired(new String[]{"path"}, PATH),
                        args -> changeContext(ctx, args)));
    }

    static Map<String, Object> swiftlets(ToolContext ctx, String router) {
        List<String> available = new ArrayList<>(ctx.swiftlets(router));
        if (ctx.read(router, MgmtPath.of("/" + Catalog.ENV)).isPresent())
            available.add(Catalog.ENV);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("router", router);
        r.put("swiftlets", available);
        return r;
    }

    static Map<String, Object> browse(ToolContext ctx, Args args) {
        String router = ctx.router(args);
        String requested = args.optString("path");
        MgmtPath path = requested == null ? ctx.nav.currentContext() : MgmtPath.resolve(ctx.nav.currentContext(), requested);
        if (path.isRoot()) {
            Map<String, Object> r = new LinkedHashMap<>(swiftlets(ctx, router));
            r.put("path", "/");
            return r;
        }
        EntityView view = existing(ctx, router, path);
        CatalogNode node = ctx.reader.describe(router, path);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("router", router);
        r.put("path", path.toString());
        if (node != null) {
            r.put("type", node.kind() == CatalogNode.Kind.LIST ? "list" : "entity");
            r.put("description", node.description());
        } else {
            r.put("note", "The metadata for " + ctx.reader.edition(router).name() + " " + ctx.catalog.release()
                    + " doesn't describe this context, so its properties can't be listed (the CLI Admin API only "
                    + "reads properties by name). Its child entities are listed.");
        }
        r.put("properties", view.properties());
        r.put("children", view.children());
        if (view.truncated()) {
            r.put("childrenTotal", view.childrenTotal());
            r.put("truncated", true);
        }
        return r;
    }

    /**
     * Reads a context, or fails with the reason: either it doesn't exist, or it is an entity whose name contains '/'
     * (e.g. the outgoing connection {@code localhost/127.0.0.1:2001}), which the CLI can't enter.
     */
    private static EntityView existing(ToolContext ctx, String router, MgmtPath path) {
        Optional<EntityView> view = ctx.read(router, path);
        if (view.isPresent())
            return view.get();
        List<String> segments = path.segments();
        for (int i = segments.size() - 1; i > 0; i--) {
            MgmtPath parent = MgmtPath.of("/" + String.join("/", segments.subList(0, i)));
            String name = String.join("/", segments.subList(i, segments.size()));
            if (ctx.read(router, parent).map(p -> p.children().contains(name)).orElse(false))
                throw new SwiftMqAccessException("'" + name + "' in " + parent + " exists, but its name contains '/', which the "
                        + "SwiftMQ CLI Admin API can't open. Its properties and sub-contexts aren't accessible; list_network_connections "
                        + "and list_routing_connections show what is known from the name.");
        }
        throw new SwiftMqAccessException("Context " + path + " doesn't exist on router '" + router + "'");
    }

    static Map<String, Object> changeContext(ToolContext ctx, Args args) {
        MgmtPath path = MgmtPath.resolve(ctx.nav.currentContext(), args.string("path"));
        if (!path.isRoot()) {
            String router = ctx.router(args);
            existing(ctx, router, path);
        }
        ctx.nav.changeContext(path);
        return Map.of("currentContext", path.toString());
    }
}
