package com.swiftmq.mcp;

import com.swiftmq.mcp.config.Settings;
import com.swiftmq.mcp.config.SettingsException;
import com.swiftmq.mcp.config.SettingsResolver;
import com.swiftmq.mcp.server.SwiftMqMcpServer;
import com.swiftmq.mcp.server.ToolRegistry;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point. Order matters: stdout is protected before any other class (SwiftMQ, logging) is touched,
 * and the log file location is set before the first logger is created.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        OutputStream stdout = StdoutGuard.install();

        if (SettingsResolver.isHelp(args)) {
            System.err.print(SettingsResolver.USAGE);
            return;
        }
        Settings settings;
        try {
            settings = SettingsResolver.resolve(args, System.getenv());
        } catch (SettingsException e) {
            System.err.println("swiftmq-mcp-server: " + e.getMessage());
            System.err.print(SettingsResolver.USAGE);
            System.exit(2);
            return;
        }
        if (settings.logFile() != null)
            System.setProperty("swiftmq.mcp.log.file", settings.logFile());

        Logger log = LoggerFactory.getLogger(Main.class);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> log.error("Uncaught exception in thread {}", t.getName(), e));
        log.info("Starting {} {} with {}", SwiftMqMcpServer.NAME, SwiftMqMcpServer.version(), settings);

        CountDownLatch stdinClosed = new CountDownLatch(1);
        McpSyncServer server = SwiftMqMcpServer.start(new EofSignalingInputStream(System.in, stdinClosed), stdout, new ToolRegistry());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> log.info("Shutting down"), "shutdown"));

        try {
            stdinClosed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("stdin closed, stopping");
        server.closeGracefully();
        System.exit(0);
    }

    /** Signals when the client closes stdin, which ends the session. */
    private static final class EofSignalingInputStream extends FilterInputStream {
        private final CountDownLatch eof;

        EofSignalingInputStream(InputStream in, CountDownLatch eof) {
            super(in);
            this.eof = eof;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b < 0)
                eof.countDown();
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n < 0)
                eof.countDown();
            return n;
        }
    }
}
