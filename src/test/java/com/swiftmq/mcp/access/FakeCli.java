package com.swiftmq.mcp.access;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory stand-in for the SwiftMQ CLI: a management tree per router, and a log of every command. */
final class FakeCli implements Cli {
    record Context(List<String> entities, Map<String, String> properties) {
    }

    final Map<String, Map<String, Context>> trees = new LinkedHashMap<>();
    final List<String> commands = new CopyOnWriteArrayList<>();
    volatile boolean closed;
    volatile long commandDelayMs;

    private String router;
    private String context;

    FakeCli router(String name) {
        trees.putIfAbsent(name, new LinkedHashMap<>());
        return this;
    }

    FakeCli context(String router, String path, List<String> entities, Map<String, String> properties) {
        router(router).trees.get(router).put(path, new Context(entities, properties));
        return this;
    }

    @Override
    public void executeCommand(String command) throws Exception {
        commands.add(command);
        if (commandDelayMs > 0)
            Thread.sleep(commandDelayMs);
        if (command.startsWith("sr ")) {
            String r = command.substring(3);
            if (!trees.containsKey(r))
                throw new Exception("Unknown router: " + r);
            router = r;
            context = null;
        } else if (command.startsWith("cc \"") && command.endsWith("\"")) {
            String path = command.substring(4, command.length() - 1);
            if (router == null || !trees.get(router).containsKey(path))
                throw new Exception("Unknown context");
            context = path;
        } else {
            throw new Exception("Invalid command: " + command);
        }
    }

    @Override
    public String[] getContextEntities() throws Exception {
        if (context == null)
            throw new Exception("No actual context set!");
        return trees.get(router).get(context).entities().toArray(String[]::new);
    }

    @Override
    public String getContextProperty(String name) throws Exception {
        if (context == null)
            throw new Exception("No actual context set!");
        String v = trees.get(router).get(context).properties().get(name);
        if (v == null && !trees.get(router).get(context).properties().containsKey(name))
            throw new Exception("Property '" + name + "' is unknown in this context!");
        return v;
    }

    @Override
    public String[] getAvailableRouters() {
        return trees.keySet().toArray(String[]::new);
    }

    @Override
    public void close() {
        closed = true;
    }
}
