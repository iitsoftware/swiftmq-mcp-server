package com.swiftmq.mcp.config;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Resolves {@link Settings} from command line arguments, then environment variables, then an optional
 * JSON config file ({@code --config}), then defaults. Passwords are only read from the environment or
 * from files, never from arguments (they would be visible in the process list).
 */
public final class SettingsResolver {

    public static final String USAGE = """
            Usage: java -jar swiftmq-mcp-server.jar [options]

              --url <smqp-url>           Router URL, e.g. smqp://host:4001        (env SWIFTMQ_URL)
              --user <name>              SwiftMQ user                             (env SWIFTMQ_USER)
              --password-file <file>     File containing the password             (env SWIFTMQ_PASSWORD_FILE)
                                         or set the password in env SWIFTMQ_PASSWORD
              --config <file>            Optional JSON config file
              --log <file>               Log file, default ~/.swiftmq-mcp/logs/server.log
              --help                     Show this help
            """;

    private static final Set<String> OPTIONS = Set.of(
            "--url", "--user", "--password-file", "--config", "--log");

    private SettingsResolver() {
    }

    /** True if the arguments ask for help. */
    public static boolean isHelp(String[] args) {
        return Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h");
    }

    public static Settings resolve(String[] args, Map<String, String> env) throws SettingsException {
        Map<String, String> opts = parseArgs(args);
        JsonNode file = loadConfigFile(opts.get("--config"));

        String url = first(opts.get("--url"), env.get("SWIFTMQ_URL"), text(file, "url"));
        String user = first(opts.get("--user"), env.get("SWIFTMQ_USER"), text(file, "user"));
        String logFile = first(opts.get("--log"), text(file, "log"));

        String password = env.get("SWIFTMQ_PASSWORD");
        if (password == null) {
            String passwordFile = first(opts.get("--password-file"), env.get("SWIFTMQ_PASSWORD_FILE"), text(file, "passwordFile"));
            if (passwordFile != null)
                password = readSecretFile(passwordFile);
        }
        if (file != null && file.has("connectionFactory"))
            throw new SettingsException("\"connectionFactory\" is no longer supported: the server builds the connection factory from \"url\"");
        if (file != null && file.has("password"))
            throw new SettingsException("The config file must not contain \"password\"; use \"passwordFile\" or env SWIFTMQ_PASSWORD");

        JsonNode limits = file == null ? null : file.get("limits");
        int maxListItems = (int) number(limits, "maxListItems", Settings.DEFAULT_MAX_LIST_ITEMS);
        long requestTimeoutMs = number(limits, "requestTimeoutMs", Settings.DEFAULT_REQUEST_TIMEOUT_MS);

        Set<String> extraDenied = new LinkedHashSet<>();
        if (file != null && file.has("deniedContexts"))
            for (JsonNode n : file.get("deniedContexts"))
                extraDenied.add(n.asString().toLowerCase(Locale.ROOT));

        validate(url, user, maxListItems, requestTimeoutMs);
        return new Settings(url, user, password,
                maxListItems, requestTimeoutMs, Set.copyOf(extraDenied), logFile);
    }

    private static Map<String, String> parseArgs(String[] args) throws SettingsException {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--password"))
                throw new SettingsException("--password is not supported (visible in the process list); use env SWIFTMQ_PASSWORD or --password-file");
            if (!OPTIONS.contains(arg))
                throw new SettingsException("Unknown option: " + arg);
            if (i + 1 >= args.length || args[i + 1].startsWith("--"))
                throw new SettingsException("Missing value for " + arg);
            opts.put(arg, args[++i]);
        }
        return opts;
    }

    private static void validate(String url, String user, int maxListItems, long requestTimeoutMs) throws SettingsException {
        if (url == null)
            throw new SettingsException("Missing router URL: --url or env SWIFTMQ_URL");
        try {
            SmqpUrl.toFactoryProperties(url);
        } catch (IllegalArgumentException e) {
            throw new SettingsException(e.getMessage());
        }
        if (user == null)
            throw new SettingsException("Missing user: --user or env SWIFTMQ_USER");
        if (maxListItems <= 0)
            throw new SettingsException("limits.maxListItems must be > 0");
        if (requestTimeoutMs <= 0)
            throw new SettingsException("limits.requestTimeoutMs must be > 0");
    }

    private static JsonNode loadConfigFile(String path) throws SettingsException {
        if (path == null)
            return null;
        try {
            JsonNode node = JsonMapper.builder().build().readTree(Files.readString(Path.of(path), StandardCharsets.UTF_8));
            if (!node.isObject())
                throw new SettingsException("Config file must contain a JSON object: " + path);
            return node;
        } catch (IOException e) {
            throw new SettingsException("Cannot read config file " + path + ": " + e.getMessage());
        } catch (JacksonException e) {
            throw new SettingsException("Invalid JSON in config file " + path + ": " + e.getOriginalMessage());
        }
    }

    private static String readSecretFile(String path) throws SettingsException {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new SettingsException("Cannot read password file " + path + ": " + e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private static long number(JsonNode node, String field, long defaultValue) {
        return node != null && node.hasNonNull(field) ? node.get(field).asLong() : defaultValue;
    }

    private static String first(String... values) {
        for (String v : values)
            if (v != null && !v.isBlank())
                return v;
        return null;
    }
}
