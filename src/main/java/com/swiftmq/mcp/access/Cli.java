package com.swiftmq.mcp.access;

/**
 * The part of the SwiftMQ CLI Admin API this server uses. Narrow on purpose, so that everything above it
 * can be tested without a router. Implementations aren't thread-safe; {@link CliConnection} serializes access.
 */
public interface Cli extends AutoCloseable {

    /** Executes a command built by {@link CommandBuilder}. */
    void executeCommand(String command) throws Exception;

    /** Entity names in the current context. */
    String[] getContextEntities() throws Exception;

    /** A property value in the current context; throws if the property doesn't exist there. */
    String getContextProperty(String name) throws Exception;

    String[] getAvailableRouters();

    @Override
    void close();

    /** Opens a CLI connection to a router. */
    interface Connector {
        /**
         * @param routerListener receives router availability changes
         * @param onConnectionLost called once if the connection to the router breaks
         */
        Cli connect(RouterListener routerListener, Runnable onConnectionLost) throws Exception;
    }

    interface RouterListener {
        void onRouterEvent(String router, boolean available);
    }
}
