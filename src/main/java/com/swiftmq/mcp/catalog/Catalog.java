package com.swiftmq.mcp.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Property metadata of the management tree per edition, generated from the swiftlets' config.xml
 * by {@link CatalogGenerator}. It is the only source of property names and of the top-level swiftlets: the
 * CLI Admin API can list a context's entities, but neither its properties nor the root context.
 *
 * @param release  SwiftMQ release the catalog was generated from, e.g. "13.02.04"
 * @param editions edition layer ("ce", "ur", "ha") → top-level context name (e.g. "sys$queuemanager") → root node.
 *                 CE is complete; UR and HA only hold what they add to or replace in their base (see {@link Edition}).
 */
public record Catalog(String release, Map<String, Map<String, CatalogNode>> editions) {

    public static final String RESOURCE = "/swiftmq-metadata.json";
    public static final String ENV = ".env";

    /**
     * {@code .env} is built by the router kernel, not described by any config.xml. Its properties were
     * verified against a CE 13.2.4 router; it's the same for every edition.
     */
    static final CatalogNode ENV_NODE = new CatalogNode(CatalogNode.Kind.ENTITY, "Router Environment", "Router Environment",
            props(
                    "routername", prop("String", "Router Name"),
                    "release", prop("String", "Release"),
                    "os", prop("String", "Operating System"),
                    "startuptime", prop("String", "Startup Time"),
                    "memory-collect-interval", prop("Long", "Memory Collect Interval (ms)"),
                    "use-smart-tree", prop("Boolean", "Use Smart Management Tree (reduced Usage Parts)")),
            null,
            Map.of("router-memory-list", new CatalogNode(CatalogNode.Kind.LIST, "Router Memory List", "Router Memory List",
                    null, null, null,
                    new CatalogNode(CatalogNode.Kind.ENTITY, "Router Memory", "Router Memory",
                            props("free-memory", prop("Integer", "Free Memory (KB)"),
                                    "total-memory", prop("Integer", "Total Memory (KB)")),
                            null, null, null, null),
                    null)),
            null, null);

    /** Omits defaults (null, false) when writing, so the boolean flags must be optional when reading. */
    static final JsonMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_DEFAULT))
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public static Catalog load() {
        try (InputStream in = Catalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null)
                throw new IllegalStateException("Resource " + RESOURCE + " not found");
            return MAPPER.readValue(in, Catalog.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PropertyDef prop(String type, String display) {
        return new PropertyDef(type, display, display, null, null, null, null, true, false, false, false);
    }

    private static Map<String, PropertyDef> props(Object... nameAndDef) {
        Map<String, PropertyDef> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < nameAndDef.length; i += 2)
            m.put((String) nameAndDef[i], (PropertyDef) nameAndDef[i + 1]);
        return m;
    }

    /** All swiftlets of an edition, including those of its bases, by name. */
    public Map<String, CatalogNode> swiftlets(Edition edition) {
        Map<String, CatalogNode> merged = new TreeMap<>();
        for (Edition layer : edition.layers().reversed())
            merged.putAll(layer(layer));
        return merged;
    }

    /** The root node of one swiftlet in an edition, or null if that edition doesn't have it. */
    public CatalogNode swiftlet(Edition edition, String name) {
        for (Edition layer : edition.layers()) {
            CatalogNode node = layer(layer).get(name);
            if (node != null)
                return node;
        }
        return null;
    }

    private Map<String, CatalogNode> layer(Edition edition) {
        Map<String, CatalogNode> layer = editions.get(edition.key());
        if (layer == null)
            throw new IllegalStateException("Catalog has no layer for edition " + edition.key());
        return layer;
    }

    /**
     * Resolves an absolute management path such as {@code /sys$queuemanager/usage/testqueue/receiver/20}.
     * Entity names inside lists match any segment.
     *
     * @return the node, or null if the catalog doesn't describe that path
     */
    public CatalogNode lookup(Edition edition, String absolutePath) {
        String[] segments = absolutePath.split("/");
        if (segments.length < 2 || !segments[0].isEmpty())
            throw new IllegalArgumentException("Not an absolute path: " + absolutePath);
        CatalogNode node = ENV.equals(segments[1]) ? ENV_NODE : swiftlet(edition, segments[1]);
        for (int i = 2; i < segments.length && node != null; i++)
            node = node.child(segments[i]);
        return node;
    }
}
