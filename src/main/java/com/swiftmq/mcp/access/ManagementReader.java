package com.swiftmq.mcp.access;

import com.swiftmq.mcp.catalog.Catalog;
import com.swiftmq.mcp.catalog.CatalogNode;
import com.swiftmq.mcp.catalog.Edition;
import com.swiftmq.mcp.catalog.PropertyDef;
import com.swiftmq.mcp.guard.Secrets;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads management contexts the way tools need them: property names from the catalog of the router's edition,
 * values converted to JSON types, secrets redacted, child lists capped.
 */
public final class ManagementReader {
    private final SwiftMqGateway gateway;
    private final Catalog catalog;
    private final int maxListItems;
    /** Edition per router, from its {@code /.env} release. A router doesn't change its edition while it runs. */
    private final Map<String, Edition> editions = new ConcurrentHashMap<>();

    public ManagementReader(SwiftMqGateway gateway, Catalog catalog, int maxListItems) {
        this.gateway = gateway;
        this.catalog = catalog;
        this.maxListItems = maxListItems;
    }

    public SwiftMqGateway gateway() {
        return gateway;
    }

    public Catalog catalog() {
        return catalog;
    }

    /** The router's edition; CE if its {@code /.env} doesn't name one. */
    public Edition edition(String router) {
        Edition cached = editions.get(router);
        if (cached != null)
            return cached;
        var env = gateway.read(router, MgmtPath.of("/" + Catalog.ENV), Set.of("release"));
        Edition edition = Edition.fromRelease(env.map(e -> e.properties().get("release")).orElse(null));
        if (env.isPresent())
            editions.put(router, edition);
        return edition;
    }

    /**
     * @return empty if the context doesn't exist on that router
     */
    public Optional<EntityView> read(String router, MgmtPath path) {
        CatalogNode node = describe(router, path);
        Map<String, PropertyDef> defs = node == null || node.properties() == null ? Map.of() : node.properties();
        return gateway.read(router, path, defs.keySet()).map(snapshot -> {
            Map<String, Object> properties = new LinkedHashMap<>();
            snapshot.properties().forEach((name, raw) -> {
                PropertyDef def = defs.get(name);
                boolean secret = (def != null && def.secret()) || Secrets.isSecret(name);
                properties.put(name, secret ? redact(raw) : ValueConverter.convert(name, def, raw));
            });
            // ".metadata" is an empty internal entity in every swiftlet
            List<String> children = snapshot.entities().stream().filter(c -> !c.equals(".metadata")).toList();
            boolean truncated = children.size() > maxListItems;
            return new EntityView(router, path.toString(), node == null ? null : node.kind(), properties,
                    truncated ? children.subList(0, maxListItems) : children, children.size(), truncated);
        });
    }

    /** The catalog's description of a path on that router, or null if the catalog doesn't know it. */
    public CatalogNode describe(String router, MgmtPath path) {
        // .env is the same in every edition: no need to look up the edition (which reads .env itself)
        Edition edition = Catalog.ENV.equals(path.topLevel()) ? Edition.CE : edition(router);
        return catalog.lookup(edition, path.toString());
    }

    private static String redact(String raw) {
        return raw == null || raw.isEmpty() || raw.equals("null") ? null : Secrets.REDACTED;
    }

    /**
     * One management context as returned to tools.
     *
     * @param kind          ENTITY or LIST per the catalog, or null if the catalog doesn't describe the path
     * @param children      child entity names, at most {@code maxListItems}
     * @param childrenTotal number of child entities on the router
     */
    public record EntityView(String router, String path, CatalogNode.Kind kind, Map<String, Object> properties,
                             List<String> children, int childrenTotal, boolean truncated) {
    }
}
