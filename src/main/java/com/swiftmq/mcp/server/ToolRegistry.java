package com.swiftmq.mcp.server;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.ArrayList;
import java.util.List;

/**
 * The tools offered by the server. Independent of the transport, so the same tools can later be wired
 * to a remote transport without changes.
 */
public final class ToolRegistry {
    private final List<SyncToolSpecification> tools = new ArrayList<>();

    public ToolRegistry add(SyncToolSpecification tool) {
        tools.add(tool);
        return this;
    }

    public List<SyncToolSpecification> tools() {
        return List.copyOf(tools);
    }
}
