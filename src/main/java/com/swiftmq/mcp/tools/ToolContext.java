package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.access.ManagementReader;
import com.swiftmq.mcp.access.ManagementReader.EntityView;
import com.swiftmq.mcp.access.MgmtPath;
import com.swiftmq.mcp.access.NavigationState;
import com.swiftmq.mcp.access.SwiftMqAccessException;
import com.swiftmq.mcp.catalog.Catalog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** What the tools share: reader, navigation state, catalog, and per-router facts. */
public final class ToolContext {
    static final Duration FACT_TTL = Duration.ofMinutes(5);

    final ManagementReader reader;
    final NavigationState nav;
    final Catalog catalog;
    final int maxListItems;
    private final Clock clock;
    private final Map<String, Fact> smartTree = new ConcurrentHashMap<>();

    private record Fact(boolean value, Instant at) {
    }

    public ToolContext(ManagementReader reader, NavigationState nav, Catalog catalog, int maxListItems) {
        this(reader, nav, catalog, maxListItems, Clock.systemUTC());
    }

    ToolContext(ManagementReader reader, NavigationState nav, Catalog catalog, int maxListItems, Clock clock) {
        this.reader = reader;
        this.nav = nav;
        this.catalog = catalog;
        this.maxListItems = maxListItems;
        this.clock = clock;
    }

    /** The router a call applies to (explicit, selected, or the only available one). */
    String router(Args args) {
        return nav.resolveRouter(args.optString("router"), reader.gateway().availableRouters());
    }

    Optional<EntityView> read(String router, String path) {
        return reader.read(router, MgmtPath.of(path));
    }

    Optional<EntityView> read(String router, MgmtPath path) {
        return reader.read(router, path);
    }

    EntityView require(String router, MgmtPath path, String notFound) {
        return reader.read(router, path).orElseThrow(() -> new SwiftMqAccessException(notFound));
    }

    /**
     * The entries of a list context, each as {@code name} plus its properties. Entries that vanish between
     * listing and reading (e.g. a consumer that just closed) are skipped.
     *
     * @return empty list if the list context doesn't exist
     */
    List<Map<String, Object>> entries(String router, MgmtPath list) {
        Optional<EntityView> view = read(router, list);
        if (view.isEmpty())
            return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : view.get().children())
            read(router, list.child(name)).ifPresent(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.putAll(e.properties());
                result.add(row);
            });
        return result;
    }

    /**
     * Whether the router uses the smart management tree, which hides usage sublists such as queue receivers.
     * Cached for a few minutes: changing it needs a router restart anyway.
     */
    boolean smartTree(String router) {
        Fact f = smartTree.get(router);
        if (f == null || f.at().plus(FACT_TTL).isBefore(clock.instant())) {
            Object v = read(router, "/" + Catalog.ENV).map(e -> e.properties().get("use-smart-tree")).orElse(null);
            f = new Fact(!Boolean.FALSE.equals(v), clock.instant());   // the router default is true
            smartTree.put(router, f);
        }
        return f.value();
    }

    static String smartTreeHint(String router) {
        return "Router '" + router + "' uses the smart management tree, so consumer/producer details are hidden. "
                + "To see them, set use-smart-tree=\"false\" on the <router> element in routerconfig.xml and restart the router.";
    }

    /** Case-insensitive glob ({@code *}, {@code ?}); null matches everything. */
    static Predicate<String> glob(String pattern) {
        if (pattern == null)
            return s -> true;
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray())
            regex.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
        Pattern p = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
        return s -> p.matcher(s).matches();
    }
}
