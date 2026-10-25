package com.swiftmq.mcp.access;

import com.swiftmq.mcp.catalog.Catalog;
import com.swiftmq.mcp.catalog.CatalogNode;
import com.swiftmq.mcp.catalog.PropertyDef;
import com.swiftmq.mcp.guard.Secrets;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads management contexts the way tools need them: property names from the catalog, values converted to
 * JSON types, secrets redacted, child lists capped.
 */
public final class ManagementReader {
    private final SwiftMqGateway gateway;
    private final Catalog catalog;
    private final int maxListItems;

    public ManagementReader(SwiftMqGateway gateway, Catalog catalog, int maxListItems) {
        this.gateway = gateway;
        this.catalog = catalog;
        this.maxListItems = maxListItems;
    }

    public SwiftMqGateway gateway() {
        return gateway;
    }

    /**
     * @return empty if the context doesn't exist on that router
     */
    public Optional<EntityView> read(String router, MgmtPath path) {
        CatalogNode node = catalog.lookup(Catalog.CE, path.toString());
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
