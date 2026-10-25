package com.swiftmq.mcp.access;

import com.swiftmq.mcp.access.SwiftMqGateway.ConnectionStatus.State;
import com.swiftmq.mcp.config.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one CLI connection of this process. It connects in the background (so the MCP handshake never waits for
 * the router), reconnects with backoff, tracks router availability, and serializes all reads, because the CLI
 * is stateful (current router and context). Every read starts with an absolute {@code sr} + {@code cc}.
 */
public final class CliConnection implements SwiftMqGateway, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CliConnection.class);
    static final Duration MIN_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final Settings settings;
    private final ContextGuard guard;
    private final Cli.Connector connector;
    private final Clock clock;
    private final ScheduledExecutorService connectExecutor;
    /** Runs the blocking connect itself, so that a hanging attempt can be abandoned (one thread per stuck attempt). */
    private final ExecutorService connectWorker;
    private final ReentrantLock lock = new ReentrantLock();
    private final SortedSet<String> routers = new ConcurrentSkipListSet<>();
    private final AtomicLong generation = new AtomicLong();

    private volatile Cli cli;
    private volatile ConnectionStatus status;
    private volatile Duration backoff = MIN_BACKOFF;
    private volatile boolean closed;

    public CliConnection(Settings settings, ContextGuard guard) {
        this(settings, guard, SwiftMqCli.connector(settings), Clock.systemUTC());
    }

    CliConnection(Settings settings, ContextGuard guard, Cli.Connector connector, Clock clock) {
        this.settings = settings;
        this.guard = guard;
        this.connector = connector;
        this.clock = clock;
        this.status = new ConnectionStatus(State.CONNECTING, settings.url(), settings.user(), null, clock.instant());
        this.connectExecutor = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "swiftmq-connect"));
        this.connectWorker = Executors.newCachedThreadPool(r -> daemon(r, "swiftmq-connect-attempt"));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** Starts connecting in the background and returns immediately. */
    public CliConnection start() {
        connectExecutor.execute(this::connect);
        return this;
    }

    private void connect() {
        if (closed)
            return;
        long gen = generation.incrementAndGet();
        Future<Cli> attempt = connectWorker.submit(() -> connector.connect(this::onRouterEvent, () -> onConnectionLost(gen)));
        try {
            log.info("Connecting to {} as {}", settings.url(), settings.user());
            Cli c;
            try {
                c = attempt.get(settings.requestTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                abandon(attempt);
                throw new Exception("no response within " + settings.requestTimeoutMs() + " ms");
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
            String[] available = c.getAvailableRouters();
            if (available != null)
                routers.addAll(Arrays.asList(available));
            cli = c;
            backoff = MIN_BACKOFF;
            status = new ConnectionStatus(State.CONNECTED, settings.url(), settings.user(), null, clock.instant());
            log.info("Connected to {}, available routers: {}", settings.url(), routers);
        } catch (Exception e) {
            String reason = describe(e);
            status = new ConnectionStatus(State.DISCONNECTED, settings.url(), settings.user(), reason, clock.instant());
            log.warn("Connecting to {} failed: {}; retrying in {}s", settings.url(), reason, backoff.toSeconds());
            scheduleReconnect();
        }
    }

    /** Gives up on a hanging attempt; if it still succeeds later, its connection is closed right away. */
    private void abandon(Future<Cli> attempt) {
        connectWorker.execute(() -> {
            try {
                Cli late = attempt.get();
                log.info("Closing a connection that was established after its attempt had timed out");
                late.close();
            } catch (Exception ignored) {
                // the attempt failed after all: nothing to clean up
            }
        });
    }

    private void scheduleReconnect() {
        if (closed)
            return;
        Duration delay = backoff;
        backoff = delay.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay.multipliedBy(2);
        connectExecutor.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void onRouterEvent(String router, boolean available) {
        log.info("Router '{}' is {}", router, available ? "available" : "unavailable");
        if (available)
            routers.add(router);
        else
            routers.remove(router);
    }

    private void onConnectionLost(long gen) {
        if (closed || gen != generation.get())
            return;
        connectExecutor.execute(() -> {
            if (gen != generation.get())
                return;
            log.warn("Connection to {} lost", settings.url());
            Cli old = cli;
            cli = null;
            routers.clear();
            status = new ConnectionStatus(State.DISCONNECTED, settings.url(), settings.user(), "connection lost", clock.instant());
            if (old != null)
                old.close();
            scheduleReconnect();
        });
    }

    @Override
    public ConnectionStatus status() {
        return status;
    }

    @Override
    public SortedSet<String> availableRouters() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(routers));
    }

    @Override
    public Optional<ContextSnapshot> read(String router, MgmtPath path, Collection<String> propertyNames) {
        guard.check(path);
        String sr = CommandBuilder.selectRouter(router);
        String cc = CommandBuilder.changeContext(path);
        acquire();
        try {
            Cli c = connected();
            try {
                execute(c, sr);
            } catch (Exception e) {
                if (!routers.contains(router))
                    throw new SwiftMqAccessException.RouterUnavailable(router);
                throw new SwiftMqAccessException("Cannot select router '" + router + "': " + describe(e), e);
            }
            try {
                execute(c, cc);
            } catch (Exception e) {
                String reason = describe(e);
                // a router-side admin role refused the context: that's not the same as "doesn't exist"
                if (reason.contains("not granted"))
                    throw new SwiftMqAccessException.AccessDenied("Router '" + router + "' denies access to " + path + ": " + reason);
                log.debug("Context {} on {} not present: {}", path, router, reason);
                return Optional.empty();
            }
            String[] entities = c.getContextEntities();
            Map<String, String> properties = new LinkedHashMap<>();
            for (String name : propertyNames) {
                try {
                    properties.put(name, c.getContextProperty(name));
                } catch (Exception absent) {
                    // property doesn't exist in this context (e.g. version differences): skip it
                }
            }
            return Optional.of(new ContextSnapshot(path, entities == null ? List.of() : List.of(entities), properties));
        } catch (SwiftMqAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new SwiftMqAccessException("Reading " + path + " on '" + router + "' failed: " + describe(e), e);
        } finally {
            lock.unlock();
        }
    }

    private void acquire() {
        try {
            if (!lock.tryLock(settings.requestTimeoutMs(), TimeUnit.MILLISECONDS))
                throw new SwiftMqAccessException.Timeout("The router connection is busy; try again");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SwiftMqAccessException.Timeout("Interrupted while waiting for the router connection");
        }
    }

    private Cli connected() {
        Cli c = cli;
        if (c == null) {
            ConnectionStatus s = status;
            throw new SwiftMqAccessException.NotConnected("Not connected to " + s.url()
                    + (s.lastError() == null ? " (connecting)" : ": " + s.lastError()));
        }
        return c;
    }

    private static void execute(Cli c, String command) throws Exception {
        log.debug("CLI> {}", command);
        c.executeCommand(command);
    }

    static String describe(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank())
            msg = e.getClass().getSimpleName();
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null && !msg.contains(cause.getMessage()))
            msg += " (" + cause.getMessage() + ")";
        return msg;
    }

    @Override
    public void close() {
        closed = true;
        connectExecutor.shutdownNow();
        connectWorker.shutdownNow();
        Cli c = cli;
        cli = null;
        if (c != null) {
            log.info("Closing connection to {}", settings.url());
            c.close();
        }
    }
}
