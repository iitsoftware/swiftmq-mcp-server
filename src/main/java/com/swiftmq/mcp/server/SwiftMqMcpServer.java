package com.swiftmq.mcp.server;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** Builds the MCP server on the stdio transport. */
public final class SwiftMqMcpServer {
    public static final String NAME = "swiftmq-mcp-server";

    static final String INSTRUCTIONS = """
            Read-only access to a SwiftMQ router network via the CLI Admin API: queues, topics, durable \
            subscribers, routing, network connections and router memory of every router reachable from \
            the connected router. Nothing can be changed, message contents are not accessible, and the \
            contexts sys$authentication, sys$mgmt and sys$timer are not available.""";

    private SwiftMqMcpServer() {
    }

    /**
     * Starts the server on the given streams. The output stream must be the real stdout (see
     * {@link com.swiftmq.mcp.StdoutGuard}) or a test stream; nothing else may write to it.
     */
    public static McpSyncServer start(InputStream in, OutputStream out, ToolRegistry tools) {
        var transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(JsonMapper.builder().build()), in, out);
        return McpServer.sync(transport)
                .serverInfo(NAME, version())
                .instructions(INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools.tools())
                .build();
    }

    public static String version() {
        Properties p = new Properties();
        try (InputStream in = SwiftMqMcpServer.class.getResourceAsStream("/swiftmq-mcp-version.properties")) {
            if (in != null)
                p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p.getProperty("version", "unknown");
    }
}
