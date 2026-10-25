package com.swiftmq.mcp.access;

import com.swiftmq.admin.cli.CLI;
import com.swiftmq.mcp.config.Settings;

import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.naming.Context;
import javax.naming.InitialContext;
import java.util.Hashtable;

/** {@link Cli} backed by the real SwiftMQ CLI Admin API over a JMS connection. */
final class SwiftMqCli implements Cli {
    private final QueueConnection connection;
    private final CLI cli;

    private SwiftMqCli(QueueConnection connection, CLI cli) {
        this.connection = connection;
        this.cli = cli;
    }

    static Connector connector(Settings settings) {
        return (routerListener, onConnectionLost) -> {
            // Request timeout of the SwiftMQ client; read when the connection is created
            System.setProperty("swiftmq.request.timeout", Long.toString(settings.requestTimeoutMs()));
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.swiftmq.jndi.InitialContextFactoryImpl");
            env.put(Context.PROVIDER_URL, settings.url());
            InitialContext ctx = new InitialContext(env);
            QueueConnection connection;
            try {
                QueueConnectionFactory factory = (QueueConnectionFactory) ctx.lookup(settings.connectionFactory());
                connection = factory.createQueueConnection(settings.user(), settings.password());
            } finally {
                ctx.close();
            }
            try {
                connection.setExceptionListener(e -> onConnectionLost.run());
                CLI cli = new CLI(connection);
                cli.addRouterListener(routerListener::onRouterEvent);
                return new SwiftMqCli(connection, cli);
            } catch (Exception e) {
                connection.close();
                throw e;
            }
        };
    }

    @Override
    public void executeCommand(String command) throws Exception {
        cli.executeCommand(command);
    }

    @Override
    public String[] getContextEntities() throws Exception {
        return cli.getContextEntities();
    }

    @Override
    public String getContextProperty(String name) throws Exception {
        return cli.getContextProperty(name);
    }

    @Override
    public String[] getAvailableRouters() {
        return cli.getAvailableRouters();
    }

    @Override
    public void close() {
        try {
            cli.close();
        } catch (Exception ignored) {
            // closing anyway
        }
        try {
            connection.close();
        } catch (Exception ignored) {
            // closing anyway
        }
    }
}
