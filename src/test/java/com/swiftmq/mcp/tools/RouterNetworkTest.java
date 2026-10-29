package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.access.CliConnection;
import com.swiftmq.mcp.access.ContextGuard;
import com.swiftmq.mcp.access.ManagementReader;
import com.swiftmq.mcp.access.NavigationState;
import com.swiftmq.mcp.access.SwiftMqGateway.ConnectionStatus.State;
import com.swiftmq.mcp.catalog.Catalog;
import com.swiftmq.jms.SwiftMQConnectionFactory;
import com.swiftmq.mcp.config.Settings;
import com.swiftmq.mcp.config.SmqpUrl;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;

import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueSender;
import javax.jms.QueueSession;
import javax.jms.Session;
import java.io.File;
import java.util.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs against the 3-router CE test network (scripts/test-network: routerA - routerB - routerC, routerA on
 * localhost:14001). Starts and stops routerC, so it only runs with SWIFTMQ_TEST_NETWORK=true:
 * <pre>
 *   (cd scripts/test-network && docker compose up -d)
 *   SWIFTMQ_TEST_NETWORK=true mvn test -Dtest=RouterNetworkTest
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RouterNetworkTest {
    static final String URL = "smqp://localhost:14001/timeout=10000";
    static final File COMPOSE = new File("scripts/test-network/docker-compose.yml");
    static final Set<String> ALL = Set.of("routerA", "routerB", "routerC");

    static CliConnection connection;
    static Map<String, SyncToolSpecification> tools = new HashMap<>();

    @BeforeAll
    static void connect() throws Exception {
        assumeTrue("true".equals(System.getenv("SWIFTMQ_TEST_NETWORK")), "set SWIFTMQ_TEST_NETWORK=true to run");
        docker("start", "routerC");   // in case a previous run was interrupted
        Settings settings = new Settings(URL, "admin", "secret", 500, 15000, Set.of(), null);
        connection = new CliConnection(settings, new ContextGuard(Set.of())).start();
        Catalog catalog = Catalog.load();
        ToolContext ctx = new ToolContext(new ManagementReader(connection, catalog, 500), new NavigationState(), catalog, 500);
        SwiftMqTools.registry(ctx).tools().forEach(t -> tools.put(t.tool().name(), t));
        await("all routers available", () -> connection.status().state() == State.CONNECTED && connection.availableRouters().equals(ALL));
        await("routing settled", () -> call("network_topology", Map.of()).get("issues").isEmpty());
    }

    @AfterAll
    static void close() throws Exception {
        if (connection != null) {
            docker("start", "routerC");
            connection.close();
        }
    }

    @Test
    @Order(1)
    void healthyNetwork() {
        JsonNode t = call("network_topology", Map.of());
        assertEquals("[[\"routerA\",\"routerB\"],[\"routerB\",\"routerC\"]]", t.get("links").toString());
        assertEquals(0, t.get("issues").size());

        JsonNode table = call("get_routing_table", Map.of("router", "routerA", "destination", "routerC"));
        assertEquals("routerB;routerC", table.get("destinations").get(0).get("dynamicRoutes").get(0).asString());

        JsonNode info = call("get_router_info", Map.of("router", "routerC"));
        assertTrue(info.get("release").asString().contains("Community Edition"));
        assertFalse(info.get("use-smart-tree").asBoolean());

        JsonNode b = call("list_routing_connections", Map.of("router", "routerB")).get("connections");
        assertEquals(2, b.size(), b.toString());
    }

    @Test
    @Order(2)
    void routerDownIsDetectedAndBacklogDrainsAfterRecovery() throws Exception {
        int before = queueCount("routerC", "testqueue");

        docker("stop", "routerC");
        await("routerC unavailable", () -> !connection.availableRouters().contains("routerC"));
        await("broken connector detected", () -> call("network_topology", Map.of()).get("issues").toString()
                .contains("routerB: 1 of 1 enabled connector(s) have no active connection"));

        send("testqueue@routerC", 5);
        // at least our 5 messages; internal route announcements can add a few
        await("store-and-forward backlog reported", () -> backlog("routerA", "routerC") >= 5
                && call("network_health", Map.of()).get("issues").toString().contains("waiting to be forwarded to router 'routerC'"));

        docker("start", "routerC");
        await("routerC available again", () -> connection.availableRouters().equals(ALL));
        await("backlog drained", () -> queueCount("routerA", "rt$routerC") == 0);
        await("messages delivered", () -> queueCount("routerC", "testqueue") == before + 5);
    }

    static int backlog(String router, String destination) {
        JsonNode b = call("network_health", Map.of("router", router)).get("routers").get(0).get("routingBacklog").get(destination);
        return b == null ? 0 : b.asInt();
    }

    static int queueCount(String router, String queue) {
        return call("get_queue", Map.of("router", router, "queue", queue)).get("usage").get("messagecount").asInt();
    }

    static JsonNode call(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult r = tools.get(tool).callHandler().apply(null, new McpSchema.CallToolRequest(tool, args));
        String text = ((McpSchema.TextContent) r.content().get(0)).text();
        assertFalse(r.isError(), tool + ": " + text);
        return ToolFactory.JSON.readTree(text);
    }

    static void send(String queue, int count) throws Exception {
        QueueConnection qc = ((QueueConnectionFactory) SwiftMQConnectionFactory.create(SmqpUrl.toFactoryProperties(URL)))
                .createQueueConnection("admin", "secret");
        try {
            QueueSession s = qc.createQueueSession(false, Session.AUTO_ACKNOWLEDGE);
            QueueSender sender = s.createSender(s.createQueue(queue));
            for (int i = 0; i < count; i++)
                sender.send(s.createTextMessage("swiftmq-mcp-server network test " + i));
        } finally {
            qc.close();
        }
    }

    static void docker(String action, String service) throws Exception {
        Process p = new ProcessBuilder("docker", "compose", "-f", COMPOSE.getPath(), action, service).inheritIO().start();
        assertEquals(0, p.waitFor(), "docker compose " + action + " " + service);
    }

    static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 120_000;
        AssertionError last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean())
                    return;
            } catch (AssertionError | RuntimeException e) {
                last = e instanceof AssertionError a ? a : new AssertionError(e);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        fail("timed out waiting for: " + what + (last == null ? "" : " (last error: " + last.getMessage() + ")"));
    }
}
