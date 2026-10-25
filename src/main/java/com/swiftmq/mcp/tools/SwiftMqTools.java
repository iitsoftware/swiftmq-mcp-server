package com.swiftmq.mcp.tools;

import com.swiftmq.mcp.server.ToolRegistry;

/** All tools of this server. */
public final class SwiftMqTools {
    private SwiftMqTools() {
    }

    public static ToolRegistry registry(ToolContext ctx) {
        ToolRegistry registry = new ToolRegistry();
        ConnectionTools.create(ctx).forEach(registry::add);
        QueueTools.create(ctx).forEach(registry::add);
        TopicTools.create(ctx).forEach(registry::add);
        RouterTools.create(ctx).forEach(registry::add);
        return registry;
    }
}
