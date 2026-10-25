package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.access.ManagementReader;
import com.swiftmq.mcp.access.ManagementReader.EntityView;
import com.swiftmq.mcp.access.MgmtPath;
import com.swiftmq.mcp.access.NavigationState;
import com.swiftmq.mcp.access.SwiftMqAccessException;
import com.swiftmq.mcp.access.SwiftMqGateway.ConnectionStatus;
import com.swiftmq.mcp.catalog.Catalog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
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
    /** router → queue → messagecount at the previous list_queues call, for "delta since last call". */
    private final Map<String, Map<String, Long>> baselines = new ConcurrentHashMap<>();

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
        String requested = args.optString("router");
        return nav.resolveRouter(requested, requested != null ? reader.gateway().availableRouters() : allRouters());
    }

    /**
     * All available routers, for tools that cover the whole network. Never silently empty: without a connection
     * that would look like a network without problems.
     *
     * @throws SwiftMqAccessException.NotConnected if not connected or no router is available yet
     */
    SortedSet<String> allRouters() {
        ConnectionStatus s = reader.gateway().status();
        if (s.state() != ConnectionStatus.State.CONNECTED)
            throw new SwiftMqAccessException.NotConnected("Not connected to " + s.url()
                    + (s.lastError() == null ? " (connecting)" : ": " + s.lastError()));
        SortedSet<String> routers = reader.gateway().availableRouters();
        if (routers.isEmpty())
            throw new SwiftMqAccessException.NotConnected("Connected to " + s.url() + ", but no router is available yet");
        return routers;
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
     * listing and reading (e.g. a consumer that just closed) are skipped. Entries whose name can't be addressed
     * by the CLI (e.g. routing connections named {@code routerB/172.21.0.2:4100}: {@code cc} can't enter names
     * containing '/') are returned with their name only and {@code "readable": false}.
     *
     * @return empty list if the list context doesn't exist
     */
    List<Map<String, Object>> entries(String router, MgmtPath list) {
        Optional<EntityView> view = read(router, list);
        if (view.isEmpty())
            return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : view.get().children()) {
            MgmtPath entry;
            try {
                entry = list.child(name);
            } catch (SwiftMqAccessException.AccessDenied unaddressable) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("readable", false);
                result.add(row);
                continue;
            }
            read(router, entry).ifPresent(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.putAll(e.properties());
                result.add(row);
            });
        }
        return result;
    }

    /**
     * Current messagecount of a queue, or null if the queue isn't active. Accepts {@code name@router}.
     */
    Integer messageCount(String router, String queue) {
        String local = queue.contains("@") ? queue.substring(0, queue.indexOf('@')) : queue;
        return read(router, QueueTools.USAGE.child(local))
                .map(q -> q.properties().get("messagecount") instanceof Integer i ? i : null)
                .orElse(null);
    }

    /** Stores the counts and returns the previous ones (empty on the first call). */
    Map<String, Long> swapBaseline(String router, Map<String, Long> counts) {
        Map<String, Long> previous = baselines.put(router, Map.copyOf(counts));
        return previous == null ? Map.of() : previous;
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
