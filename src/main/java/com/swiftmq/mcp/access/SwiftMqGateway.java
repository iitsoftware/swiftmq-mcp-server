package com.swiftmq.mcp.access;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;

/** Read access to the management tree of a router network. Implementations enforce the {@link ContextGuard}. */
public interface SwiftMqGateway {

    ConnectionStatus status();

    /** Routers currently available in the network, as reported by the connected router. */
    SortedSet<String> availableRouters();

    /**
     * Reads one context: its child entity names, and those of the requested properties that exist there.
     *
     * @return empty if the context doesn't exist on that router
     * @throws SwiftMqAccessException if the path is denied, the router is unavailable, or the server isn't connected
     */
    Optional<ContextSnapshot> read(String router, MgmtPath path, Collection<String> propertyNames);

    /** Raw content of one context. Property values are the CLI's strings; absent properties are omitted. */
    record ContextSnapshot(MgmtPath path, List<String> entities, Map<String, String> properties) {
    }

    record ConnectionStatus(State state, String url, String user, String lastError, Instant since) {
        public enum State {CONNECTING, CONNECTED, DISCONNECTED}
    }
}
