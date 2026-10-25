package com.swiftmq.mcp.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Property metadata of the management tree per edition, generated from the swiftlets' config.xml
 * by {@link CatalogGenerator}. It is the only source of property names: the CLI Admin API can list
 * entities but not properties.
 *
 * @param release  SwiftMQ release the catalog was generated from, e.g. "13.02.04"
 * @param editions edition ("ce") → top-level context name (e.g. "sys$queuemanager") → root node
 */
public record Catalog(String release, Map<String, Map<String, CatalogNode>> editions) {

    public static final String RESOURCE = "/swiftmq-metadata.json";
    public static final String CE = "ce";
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

    public Map<String, CatalogNode> swiftlets(String edition) {
        Map<String, CatalogNode> swiftlets = editions.get(edition);
        if (swiftlets == null)
            throw new IllegalArgumentException("Unknown edition: " + edition);
        return swiftlets;
    }

    /**
     * Resolves an absolute management path such as {@code /sys$queuemanager/usage/testqueue/receiver/20}.
     * Entity names inside lists match any segment.
     *
     * @return the node, or null if the catalog doesn't describe that path
     */
    public CatalogNode lookup(String edition, String absolutePath) {
        String[] segments = absolutePath.split("/");
        if (segments.length < 2 || !segments[0].isEmpty())
            throw new IllegalArgumentException("Not an absolute path: " + absolutePath);
        CatalogNode node = ENV.equals(segments[1]) ? ENV_NODE : swiftlets(edition).get(segments[1]);
        for (int i = 2; i < segments.length && node != null; i++)
            node = node.child(segments[i]);
        return node;
    }
}
