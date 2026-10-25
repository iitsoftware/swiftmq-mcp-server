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
