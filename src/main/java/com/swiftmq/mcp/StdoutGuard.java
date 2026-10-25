package com.swiftmq.mcp;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * With stdio, stdout is the JSON-RPC channel: a single stray line corrupts the protocol. The SwiftMQ CLI
 * may print to {@code System.out}, so before anything else runs, the real stdout is reserved for the MCP
 * transport and {@code System.out} is pointed at stderr.
 */
public final class StdoutGuard {
    private StdoutGuard() {
    }

    /**
     * Redirects {@code System.out} to stderr.
     *
     * @return the real stdout, to be used exclusively by the MCP transport
     */
    public static OutputStream install() {
        OutputStream realStdout = new FileOutputStream(FileDescriptor.out);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));
        return realStdout;
    }
}
