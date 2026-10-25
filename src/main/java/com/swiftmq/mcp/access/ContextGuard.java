package com.swiftmq.mcp.access;

import com.swiftmq.mcp.guard.DeniedContexts;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rejects paths into denied top-level contexts before anything is sent to the router. The fixed
 * denylist ({@link DeniedContexts#FIXED}) can be extended but never reduced.
 */
public final class ContextGuard {
    private final Set<String> denied;

    public ContextGuard(Set<String> extraDenied) {
        Set<String> all = new TreeSet<>(DeniedContexts.FIXED);
        extraDenied.forEach(d -> all.add(d.toLowerCase(Locale.ROOT)));
        this.denied = Set.copyOf(all);
    }

    public boolean isDenied(String topLevelContext) {
        return topLevelContext != null && denied.contains(topLevelContext.toLowerCase(Locale.ROOT));
    }

    /** @throws SwiftMqAccessException.AccessDenied for the root or a denied context */
    public MgmtPath check(MgmtPath path) {
        if (path.isRoot())
            throw new SwiftMqAccessException.AccessDenied("The root context can't be read; use a swiftlet path such as /sys$queuemanager");
        if (isDenied(path.topLevel()))
            throw new SwiftMqAccessException.AccessDenied("Access to " + path.topLevel() + " is not allowed");
        return path;
    }

    public Set<String> denied() {
        return denied;
    }
}
