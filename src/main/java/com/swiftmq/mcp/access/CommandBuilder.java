package com.swiftmq.mcp.access;

/**
 * The only place where CLI command strings are built. It can produce exactly two navigation commands:
 * {@code sr <router>} and {@code cc "<absolute path>"}. All data is read through the CLI getters.
 */
public final class CommandBuilder {
    private CommandBuilder() {
    }

    public static String selectRouter(String router) {
        MgmtPath.validateName(router, "router name");
        if (router.chars().anyMatch(Character::isWhitespace))
            throw new SwiftMqAccessException.AccessDenied("Invalid router name: " + router);
        return "sr " + router;
    }

    /** Paths are always double-quoted: entity names can contain spaces (e.g. "Queue Manager"). */
    public static String changeContext(MgmtPath path) {
        if (path.isRoot())
            throw new SwiftMqAccessException.AccessDenied("The root context can't be selected");
        return "cc \"" + path + "\"";
    }
}
