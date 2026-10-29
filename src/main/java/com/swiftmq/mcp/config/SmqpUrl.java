package com.swiftmq.mcp.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns an SMQP URL ({@code smqp://host:port/opt=value;...}) into the properties of
 * {@code SwiftMQConnectionFactory.create(Map)}. The connection factory is built on the client, so the server
 * connects exactly to host and port of the URL, whatever address the router's listener advertises (e.g. inside
 * Docker).
 */
public final class SmqpUrl {
    public static final String PLAIN_SOCKET_FACTORY = "com.swiftmq.net.PlainSocketFactory";

    /** URL option → connection factory property. */
    private static final Map<String, String> OPTIONS = Map.of(
            "type", "socketfactory",
            "host2", "reconnect_hostname2",
            "port2", "reconnect_port2",
            "reconnect", "reconnect_enabled",
            "retrydelay", "reconnect_retry_delay",
            "maxretries", "reconnect_max_retries",
            "keepalive", "keepaliveinterval");

    /** Options of the JNDI URL that have no connection factory equivalent; accepted so existing URLs keep working. */
    private static final Set<String> IGNORED = Set.of("timeout", "debug", "idleclose", "intravm");

    private static final Set<String> NUMERIC = Set.of("port2", "retrydelay", "maxretries", "keepalive");

    private SmqpUrl() {
    }

    /**
     * @throws IllegalArgumentException if the URL is malformed or has an unknown option
     */
    public static Map<String, String> toFactoryProperties(String url) {
        if (url == null || !url.startsWith("smqp://"))
            throw new IllegalArgumentException("Router URL must start with smqp:// : " + url);
        String rest = url.substring("smqp://".length());
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        if (authority.contains("@"))
            throw new IllegalArgumentException("Router URL must not contain credentials; use --user and env SWIFTMQ_PASSWORD");
        int colon = authority.lastIndexOf(':');
        if (colon <= 0 || colon == authority.length() - 1)
            throw new IllegalArgumentException("Router URL needs host and port, e.g. smqp://localhost:4001 : " + url);
        Map<String, String> props = new LinkedHashMap<>();
        props.put("socketfactory", PLAIN_SOCKET_FACTORY);
        props.put("hostname", authority.substring(0, colon));
        props.put("port", number("port", authority.substring(colon + 1)));
        if (slash >= 0)
            for (String option : rest.substring(slash + 1).split(";")) {
                if (option.isBlank())
                    continue;
                int eq = option.indexOf('=');
                if (eq <= 0)
                    throw new IllegalArgumentException("Invalid URL option '" + option + "' (expected name=value): " + url);
                String name = option.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = option.substring(eq + 1).trim();
                if (IGNORED.contains(name))
                    continue;
                String property = OPTIONS.get(name);
                if (property == null)
                    throw new IllegalArgumentException("Unknown URL option '" + name + "'; supported: "
                            + new java.util.TreeSet<>(OPTIONS.keySet()) + ": " + url);
                props.put(property, NUMERIC.contains(name) ? number(name, value) : value);
            }
        return props;
    }

    private static String number(String name, String value) {
        try {
            if (Long.parseLong(value) < 0)
                throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("URL option '" + name + "' must be a non-negative number: " + value);
        }
    }
}
