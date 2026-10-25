package com.swiftmq.mcp.access;

/**
 * A management request that couldn't be served. The message is meant for the user (and the model),
 * so it must never contain credentials.
 */
public class SwiftMqAccessException extends RuntimeException {
    public SwiftMqAccessException(String message) {
        super(message);
    }

    public SwiftMqAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The server isn't connected to the router (yet, or anymore). */
    public static final class NotConnected extends SwiftMqAccessException {
        public NotConnected(String message) {
            super(message);
        }
    }

    /** The router isn't available in the network. */
    public static final class RouterUnavailable extends SwiftMqAccessException {
        public RouterUnavailable(String router) {
            super("Router '" + router + "' is not available");
        }
    }

    /** The path is denied or invalid. Never sent to the router. */
    public static final class AccessDenied extends SwiftMqAccessException {
        public AccessDenied(String message) {
            super(message);
        }
    }

    /** The request took longer than the configured timeout. */
    public static final class Timeout extends SwiftMqAccessException {
        public Timeout(String message) {
            super(message);
        }
    }
}
