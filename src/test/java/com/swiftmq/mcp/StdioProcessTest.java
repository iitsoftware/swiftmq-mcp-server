package com.swiftmq.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the server as a real child process, the way an MCP client starts it. */
class StdioProcessTest {
    @TempDir
    Path dir;

    @Test
    void systemOutNeverReachesRealStdout() throws Exception {
        Process p = java(StdoutGuardProbe.class.getName()).start();
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        assertEquals("only-this\n", new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).contains("noise-from-System.out"));
    }

    @Test
    void stdoutCarriesOnlyJsonRpcAndProcessExitsOnStdinEof() throws Exception {
        Path log = dir.resolve("server.log");
        Process p = java(Main.class.getName(), "--url", "smqp://localhost:4001", "--user", "nobody", "--log", log.toString())
                .redirectError(dir.resolve("stderr.txt").toFile())
                .start();
        try (OutputStream in = p.getOutputStream()) {
            in.write("""
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                    {"jsonrpc":"2.0","method":"notifications/initialized"}
                    {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                    """.getBytes(StandardCharsets.UTF_8));
            in.flush();
            Thread.sleep(3000);   // let the server answer before stdin is closed
        }
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "server did not exit after stdin EOF");
        assertEquals(0, p.exitValue());

        List<String> lines = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        assertEquals(2, lines.size(), "expected exactly two responses on stdout, got: " + lines);
        JsonMapper json = JsonMapper.builder().build();
        for (String line : lines) {
            JsonNode msg = json.readTree(line);
            assertEquals("2.0", msg.get("jsonrpc").asString());
            assertTrue(msg.has("result"), line);
        }
        assertTrue(Files.readString(log).contains("stdin closed, stopping"));
    }

    static final List<String> ALL_TOOLS = List.of("connection_info", "list_routers", "select_router", "list_queues", "find_queue",
            "get_queue", "list_topics", "get_topic", "list_durable_subscribers", "get_router_memory", "get_router_info",
            "list_routing_connections", "get_routing_table", "get_routing_config", "network_topology",
            "list_network_connections", "network_summary", "network_health", "list_swiftlets", "browse_context", "change_context");

    @Test
    void everyToolKeepsStdoutCleanWhenTheRouterIsUnreachable() throws Exception {
        List<JsonNode> responses = runAllTools("smqp://localhost:1/timeout=2000", 4000);
        for (JsonNode r : responses) {
            String id = r.get("id").asString();
            if (id.equals("connection_info") || id.equals("list_routers"))
                assertFalse(r.get("result").get("isError").asBoolean(), id + " reports the connection state instead of failing");
            else
                assertTrue(r.get("result").get("isError").asBoolean(), id + " must report the missing connection: " + r);
        }
    }

    @Test
    void everyToolKeepsStdoutCleanAgainstALiveRouter() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(listening(4001), "no SwiftMQ router on localhost:4001");
        List<JsonNode> responses = runAllTools("smqp://localhost:4001/timeout=10000", 8000);
        long ok = responses.stream().filter(r -> !r.get("result").get("isError").asBoolean()).count();
        assertTrue(ok >= ALL_TOOLS.size() - 4, "most tools succeed with empty arguments: " + responses);
    }

    /** Calls every tool once (id = tool name) and asserts stdout holds exactly the JSON-RPC responses. */
    private List<JsonNode> runAllTools(String url, long waitMs) throws Exception {
        ProcessBuilder pb = java(Main.class.getName(), "--url", url, "--user", "admin", "--log", dir.resolve("all.log").toString())
                .redirectError(dir.resolve("all-stderr.txt").toFile());
        pb.environment().put("SWIFTMQ_PASSWORD", System.getenv().getOrDefault("SWIFTMQ_TEST_PASSWORD", "secret"));
        Process p = pb.start();
        try (OutputStream in = p.getOutputStream()) {
            StringBuilder sb = new StringBuilder("""
                    {"jsonrpc":"2.0","id":"init","method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                    {"jsonrpc":"2.0","method":"notifications/initialized"}
                    """);
            in.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            in.flush();
            Thread.sleep(waitMs / 2);   // give the connection a chance
            sb.setLength(0);
            for (String tool : ALL_TOOLS) {
                String args = tool.equals("select_router") ? "{\"router\":\"router\"}"
                        : tool.equals("get_queue") ? "{\"queue\":\"routerdlq\"}"
                        : tool.equals("find_queue") ? "{\"pattern\":\"*\"}"
                        : tool.equals("get_topic") ? "{\"topic\":\"testtopic\"}"
                        : tool.equals("change_context") ? "{\"path\":\"/sys$queuemanager\"}" : "{}";
                sb.append("{\"jsonrpc\":\"2.0\",\"id\":\"").append(tool).append("\",\"method\":\"tools/call\",\"params\":{\"name\":\"")
                        .append(tool).append("\",\"arguments\":").append(args).append("}}\n");
            }
            in.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            in.flush();
            Thread.sleep(waitMs);
        }
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        List<String> lines = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        assertEquals(ALL_TOOLS.size() + 1, lines.size(), "one response per request and nothing else on stdout: " + lines);
        JsonMapper json = JsonMapper.builder().build();
        List<JsonNode> responses = new java.util.ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String line : lines) {
            JsonNode msg = json.readTree(line);
            assertEquals("2.0", msg.get("jsonrpc").asString());
            assertTrue(msg.has("result"), line);
            ids.add(msg.get("id").asString());
            if (!msg.get("id").asString().equals("init"))
                responses.add(msg);
        }
        assertTrue(ids.containsAll(ALL_TOOLS), "every tool answered");
        return responses;
    }

    private static boolean listening(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("localhost", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void invalidSettingsExitWithCode2AndUsageOnStderr() throws Exception {
        Process p = java(Main.class.getName(), "--user", "x").start();
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        assertEquals(2, p.exitValue());
        assertEquals(0, p.getInputStream().readAllBytes().length);
        assertTrue(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).contains("Missing router URL"));
    }

    private static ProcessBuilder java(String mainClass, String... args) {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        // The child must use the production logback.xml, not logback-test.xml from the test classpath
        String logbackConfig = StdioProcessTest.class.getResource("/logback.xml").toString();
        List<String> cmd = new java.util.ArrayList<>(List.of(javaBin, "-Dlogback.configurationFile=" + logbackConfig,
                "-cp", System.getProperty("java.class.path"), mainClass));
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd);
    }
}
