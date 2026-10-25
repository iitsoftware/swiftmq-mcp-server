package com.swiftmq.mcp.access;

import java.time.Instant;
import java.util.*;

/** Gateway answering from a {@link FakeCli} tree, with the real {@link ContextGuard} but no connection handling. */
public final class FakeGateway implements SwiftMqGateway {
    private final FakeCli cli;
    private final ContextGuard guard = new ContextGuard(Set.of());
    public final List<String> reads = new ArrayList<>();
    /** Routers reported as available; defaults to the routers of the tree. */
    public SortedSet<String> available;

    public FakeGateway(FakeCli cli) {
        this.cli = cli;
    }

    @Override
    public ConnectionStatus status() {
        return new ConnectionStatus(ConnectionStatus.State.CONNECTED, "smqp://localhost:4001", "admin", null, Instant.EPOCH);
    }

    @Override
    public SortedSet<String> availableRouters() {
        return available != null ? available : new TreeSet<>(cli.trees.keySet());
    }

    @Override
    public Optional<ContextSnapshot> read(String router, MgmtPath path, Collection<String> names) {
        guard.check(path);
        reads.add(router + ":" + path);
        if (!cli.trees.containsKey(router))
            throw new SwiftMqAccessException.RouterUnavailable(router);
        var ctx = cli.trees.get(router).get(path.toString());
        if (ctx == null)
            return Optional.empty();
        Map<String, String> props = new LinkedHashMap<>();
        for (String n : names)
            if (ctx.properties().containsKey(n))
                props.put(n, ctx.properties().get(n));
        return Optional.of(new ContextSnapshot(path, ctx.entities(), props));
    }
}
