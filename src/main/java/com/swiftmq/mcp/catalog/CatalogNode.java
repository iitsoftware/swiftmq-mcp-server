package com.swiftmq.mcp.catalog;

import java.util.List;
import java.util.Map;

/**
 * One node of the management tree as described by config.xml.
 * <p>
 * An {@code ENTITY} has properties, named child entities and named child lists.
 * A {@code LIST} holds any number of entities of the same shape, described by {@code template}.
 */
public record CatalogNode(
        Kind kind,
        String display,
        String description,
        Map<String, PropertyDef> properties,
        Map<String, CatalogNode> entities,
        Map<String, CatalogNode> lists,
        CatalogNode template,
        List<String> dynamicProperties) {

    public enum Kind {ENTITY, LIST}

    /** The child node reached by one path segment, or null if the catalog doesn't know it. */
    public CatalogNode child(String segment) {
        if (kind == Kind.LIST)
            return template;
        CatalogNode entity = entities == null ? null : entities.get(segment);
        if (entity != null)
            return entity;
        return lists == null ? null : lists.get(segment);
    }
}
