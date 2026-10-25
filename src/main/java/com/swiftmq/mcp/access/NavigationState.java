package com.swiftmq.mcp.access;

import java.util.SortedSet;

/**
 * Per-process navigation state (one process = one user): the default router for tools and the current
 * context for browsing. Lost on restart, by design.
 */
public final class NavigationState {
    private volatile String currentRouter;
    private volatile MgmtPath currentContext = MgmtPath.ROOT;

    public String currentRouter() {
        return currentRouter;
    }

    public void selectRouter(String router) {
        this.currentRouter = MgmtPath.validateName(router, "router name");
    }

    public MgmtPath currentContext() {
        return currentContext;
    }

    public void changeContext(MgmtPath context) {
        this.currentContext = context;
    }

    /**
     * The router a tool call applies to: the explicitly requested one, else the selected one, else the only
     * available router.
     *
     * @throws SwiftMqAccessException if none can be determined
     */
    public String resolveRouter(String requested, SortedSet<String> available) {
        if (requested != null && !requested.isBlank())
            return MgmtPath.validateName(requested.strip(), "router name");
        String selected = currentRouter;
        if (selected != null)
            return selected;
        if (available.size() == 1)
            return available.first();
        if (available.isEmpty())
            throw new SwiftMqAccessException.NotConnected("No router is available yet");
        throw new SwiftMqAccessException("Several routers are available " + available
                + "; pass 'router' or use select_router");
    }
}
