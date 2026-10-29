package com.swiftmq.mcp.access;

import com.swiftmq.mcp.access.SwiftMqGateway.ConnectionStatus.State;
import com.swiftmq.mcp.catalog.Catalog;
import com.swiftmq.mcp.config.Settings;
import com.swiftmq.mcp.tools.SwiftMqTools;
import com.swiftmq.mcp.tools.ToolContext;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every tool, called normally and with hostile arguments, through the real CliConnection against a fake router
 * that contains traps (denied swiftlets with secrets, passwords in the routing configuration). Checks every command
 * that reaches the CLI and every output.
 */
class SecurityTest {
    static final Pattern SR = Pattern.compile("sr [^\\s;\"]+");
    static final Pattern CC = Pattern.compile("cc \"/[^\";\\p{Cntrl}]+\"");
    static final List<String> SECRETS = List.of("s3cret-user", "s3cret-role", "s3cret-connector", "s3cret-listener");

    final FakeCli cli = new FakeCli();
    final FakeConnector connector = new FakeConnector();
    CliConnection connection;
    final Map<String, SyncToolSpecification> tools = new TreeMap<>();
    final List<String> outputs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // traps: must never be entered
        cli.context("router", "/sys$authentication", List.of("users"), Map.of());
        cli.context("router", "/sys$authentication/users/admin", List.of(), Map.of("password", "s3cret-user"));
        cli.context("router", "/sys$mgmt/roles/readonly", List.of(), Map.of("password", "s3cret-role"));
        cli.context("router", "/sys$timer", List.of(), Map.of());
        // regular tree with secrets that must be redacted
        cli.context("router", "/sys$routing", List.of("connectors", "listeners"), Map.of());
        cli.context("router", "/sys$routing/connectors", List.of("c1"), Map.of());
        cli.context("router", "/sys$routing/connectors/c1", List.of(), Map.of("hostname", "h", "password", "s3cret-connector", "enabled", "true"));
        cli.context("router", "/sys$routing/listeners", List.of("l1"), Map.of());
        cli.context("router", "/sys$routing/listeners/l1", List.of(), Map.of("port", "4100", "password", "s3cret-listener"));
        cli.context("router", "/sys$routing/usage/connections", List.of(), Map.of());
        cli.context("router", "/sys$routing/usage/routing-table", List.of(), Map.of());
        cli.context("router", "/sys$queuemanager/usage", List.of("q"), Map.of());
        cli.context("router", "/sys$queuemanager/usage/q", List.of(), Map.of("messagecount", "1"));
        cli.context("router", "/sys$topicmanager/topics", List.of("t"), Map.of());
        cli.context("router", "/.env", List.of("router-memory-list"), Map.of("use-smart-tree", "true"));
        cli.context("router", "/.env/router-memory-list", List.of("router"), Map.of());
        cli.context("router", "/.env/router-memory-list/router", List.of(), Map.of("free-memory", "1", "total-memory", "2"));
        cli.context("router", "/sys$net/usage", List.of(), Map.of());

        connector.results.add(cli);
        Settings settings = new Settings("smqp://localhost:4001", "admin", "s3cret-login", 500, 2000, Set.of(), null);
        connection = new CliConnection(settings, new ContextGuard(Set.of()), connector, Clock.systemUTC()).start();
        CliConnectionTest.await(() -> connection.status().state() == State.CONNECTED);
        Catalog catalog = Catalog.load();
        ToolContext ctx = new ToolContext(new ManagementReader(connection, catalog, 500), new NavigationState(), catalog, 500);
        SwiftMqTools.registry(ctx).tools().forEach(t -> tools.put(t.tool().name(), t));
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    @Test
    void everyToolWithNormalAndHostileArguments() {
        List<String> hostileNames = List.of("x\"; halt", "x;reboot", "x\nsave", "../../sys$authentication", "q\" ; cc /sys$mgmt");
        List<String> hostilePaths = List.of("/sys$authentication/users", "/SYS$AUTHENTICATION", "/sys$mgmt/roles",
                "/sys$queuemanager/../sys$mgmt", "../../sys$timer", "/sys$queuemanager/usage/\"; halt", "/");
        for (String tool : tools.keySet()) {
            call(tool, Map.of());
            call(tool, Map.of("router", "router", "queue", "q", "topic", "t", "pattern", "*", "path", "/sys$routing/connectors/c1", "all", true));
            for (String bad : hostileNames)
                call(tool, Map.of("router", bad, "queue", bad, "topic", bad, "pattern", bad, "destination", bad, "swiftlet", bad, "path", bad));
            for (String path : hostilePaths)
                call(tool, Map.of("path", path));
        }

        assertFalse(cli.commands.isEmpty());
        for (String command : cli.commands) {
            assertTrue(SR.matcher(command).matches() || CC.matcher(command).matches(), "unexpected command: " + command);
            if (command.startsWith("cc ")) {
                // the denylist is about top-level swiftlets; e.g. /sys$routing/sys$timer is a harmless sub-path
                String topLevel = command.substring(5, command.length() - 1).split("/")[0].toLowerCase(Locale.ROOT);
                assertFalse(List.of("sys$authentication", "sys$mgmt", "sys$timer").contains(topLevel), "denied context reached the CLI: " + command);
            }
        }
        for (String output : outputs)
            for (String secret : SECRETS)
                assertFalse(output.contains(secret), "secret in output: " + output);
        assertTrue(outputs.stream().anyMatch(o -> o.contains("\"password\" : \"***\"")), "redacted connector password is shown as ***");
        assertTrue(outputs.stream().noneMatch(o -> o.contains("s3cret-login")), "login password never appears");
    }

    private void call(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult r = tools.get(tool).callHandler().apply(null, new McpSchema.CallToolRequest(tool, args));
        outputs.add(((McpSchema.TextContent) r.content().get(0)).text());
    }
}
