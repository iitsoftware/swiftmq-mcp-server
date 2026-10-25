package com.swiftmq.mcp.access;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Hands out prepared CLIs (or failures) in order and keeps the callbacks of the last connect. */
public final class FakeConnector implements Cli.Connector {
    public final BlockingQueue<Object> results = new LinkedBlockingQueue<>();
    volatile Cli.RouterListener routerListener;
    volatile Runnable onConnectionLost;
    final CountDownLatch release = new CountDownLatch(1);
    final FakeCli lateCli = new FakeCli().router("late");
    volatile boolean blockFirst;
    /** The first attempt hangs until released, the way a half-open TCP connection does. */
    volatile boolean hangFirst;
    final CountDownLatch hang = new CountDownLatch(1);

    @Override
    public Cli connect(Cli.RouterListener routerListener, Runnable onConnectionLost) throws Exception {
        if (hangFirst) {
            hangFirst = false;
            hang.await();
            return lateCli;
        }
        if (blockFirst) {
            blockFirst = false;
            release.await();
        }
        this.routerListener = routerListener;
        this.onConnectionLost = onConnectionLost;
        Object next = results.poll(5, TimeUnit.SECONDS);
        if (next instanceof Exception e)
            throw e;
        return (Cli) next;
    }
}
