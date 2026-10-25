package com.swiftmq.mcp.server;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Talks JSON-RPC to the server over piped streams, as an MCP client would over stdio. */
class SwiftMqMcpServerTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    PipedOutputStream clientOut;
    BufferedReader clientIn;
    McpSyncServer server;
    final ExecutorService reader = Executors.newSingleThreadExecutor();

    @BeforeEach
    void start() throws IOException {
        clientOut = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(clientOut, 1 << 16);
        PipedOutputStream serverOut = new PipedOutputStream();
        clientIn = new BufferedReader(new InputStreamReader(new PipedInputStream(serverOut, 1 << 16), StandardCharsets.UTF_8));
        server = SwiftMqMcpServer.start(serverIn, serverOut, new ToolRegistry());
    }

    @AfterEach
    void stop() throws IOException {
        server.closeGracefully();
        clientOut.close();
        reader.shutdownNow();
    }

    @Test
    void initializeAndListTools() throws Exception {
        send("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""");
        JsonNode init = receive();
        assertEquals(1, init.get("id").asInt());
        JsonNode result = init.get("result");
        assertEquals(SwiftMqMcpServer.NAME, result.get("serverInfo").get("name").asString());
        assertNotEquals("unknown", result.get("serverInfo").get("version").asString());
        assertTrue(result.get("instructions").asString().contains("Read-only"));
        assertTrue(result.get("capabilities").has("tools"));

        send("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}""");
        send("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}""");
        JsonNode tools = receive();
        assertEquals(2, tools.get("id").asInt());
        assertEquals(0, tools.get("result").get("tools").size());
    }

    private void send(String json) throws IOException {
        clientOut.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        clientOut.flush();
    }

    private JsonNode receive() throws Exception {
        String line = reader.submit(() -> clientIn.readLine()).get(10, TimeUnit.SECONDS);
        assertNotNull(line, "server closed the stream");
        return JSON.readTree(line);
    }
}
