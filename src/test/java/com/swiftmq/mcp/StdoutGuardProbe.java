package com.swiftmq.mcp;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Run in a child JVM by {@link StdioProcessTest}: writes noise to System.out and a marker to the real stdout. */
public class StdoutGuardProbe {
    public static void main(String[] args) throws Exception {
        OutputStream real = StdoutGuard.install();
        System.out.println("noise-from-System.out");
        System.out.flush();
        real.write("only-this\n".getBytes(StandardCharsets.UTF_8));
        real.flush();
    }
}
