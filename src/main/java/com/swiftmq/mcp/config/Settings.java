package com.swiftmq.mcp.config;

import java.util.Set;

/**
 * Resolved launch settings. The password must never be logged or returned by a tool;
 * {@link #toString()} masks it.
 *
 * @param url                 SMQP URL without credentials, e.g. {@code smqp://host:4001}
 * @param password            may be null if the router doesn't require one
 * @param extraDeniedContexts top-level contexts denied in addition to the fixed ones
 * @param logFile             log file path, or null for the default
 */
public record Settings(
        String url,
        String user,
        String password,
        String connectionFactory,
        int maxListItems,
        long requestTimeoutMs,
        Set<String> extraDeniedContexts,
        String logFile) {

    public static final String DEFAULT_CONNECTION_FACTORY = "ConnectionFactory";
    public static final int DEFAULT_MAX_LIST_ITEMS = 500;
    public static final long DEFAULT_REQUEST_TIMEOUT_MS = 15000;

    @Override
    public String toString() {
        return "Settings[url=" + url + ", user=" + user + ", password=" + (password == null ? "<none>" : "***")
                + ", connectionFactory=" + connectionFactory
                + ", maxListItems=" + maxListItems
                + ", requestTimeoutMs=" + requestTimeoutMs + ", extraDeniedContexts=" + extraDeniedContexts
                + ", logFile=" + logFile + "]";
    }
}
