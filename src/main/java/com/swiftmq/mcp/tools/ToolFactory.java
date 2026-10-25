package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.access.SwiftMqAccessException;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds read-only MCP tools with a uniform contract: JSON result as text, user-facing errors as
 * {@code isError} results, one log line per call (never with values or credentials).
 */
final class ToolFactory {
    private static final Logger log = LoggerFactory.getLogger("com.swiftmq.mcp.tools.calls");
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JacksonMcpJsonMapper MCP_JSON = new JacksonMcpJsonMapper(JSON);

    @FunctionalInterface
    interface Handler {
        Object handle(Args args);
    }

    private ToolFactory() {
    }

    /**
     * @param inputSchema JSON schema of the arguments (an object schema)
     */
    static SyncToolSpecification tool(String name, String title, String description, String inputSchema, Handler handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .title(title)
                .description(description)
                .inputSchema(MCP_JSON, inputSchema)
                .annotations(new McpSchema.ToolAnnotations(title, true, false, true, true, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> call(name, handler, request))
                .build();
    }

    static McpSchema.CallToolResult call(String name, Handler handler, McpSchema.CallToolRequest request) {
        long start = System.nanoTime();
        String outcome = "ok";
        try {
            String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(handler.handle(new Args(request.arguments())));
            outcome = "ok, " + json.length() + " chars";
            return McpSchema.CallToolResult.builder().addTextContent(json).isError(false).build();
        } catch (SwiftMqAccessException | IllegalArgumentException e) {
            outcome = e.getClass().getSimpleName();
            return error(e.getMessage());
        } catch (RuntimeException e) {
            outcome = "internal error";
            log.error("Tool {} failed", name, e);
            return error("Internal error in " + name + ": " + e.getClass().getSimpleName() + "; see the server log");
        } finally {
            log.info("{} -> {} ({} ms)", name, outcome, (System.nanoTime() - start) / 1_000_000);
        }
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    /** Object schema with the optional {@code router} argument plus further properties (JSON fragments). */
    static String schema(String... properties) {
        StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"properties\":{")
                .append("\"router\":{\"type\":\"string\",\"description\":\"Router name. Optional: defaults to the selected router, or the only available one.\"}");
        for (String p : properties)
            sb.append(',').append(p);
        return sb.append("}}").toString();
    }

    /** Like {@link #schema} but with required properties. */
    static String schemaRequired(String[] required, String... properties) {
        String s = schema(properties);
        StringBuilder req = new StringBuilder(",\"required\":[");
        for (int i = 0; i < required.length; i++)
            req.append(i == 0 ? "" : ",").append('"').append(required[i]).append('"');
        return s.substring(0, s.length() - 1) + req + "]}";
    }
}
