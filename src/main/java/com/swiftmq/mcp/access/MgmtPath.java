package com.swiftmq.mcp.access;

import java.util.ArrayList;
import java.util.List;

/**
 * A canonical, absolute path in the management tree, e.g. {@code /sys$queuemanager/usage/testqueue}.
 * Relative input is resolved against a base, {@code .} and {@code ..} are resolved here and never sent
 * to the router. Segments may contain spaces (paths are always double-quoted in commands), but no
 * double quote, semicolon or control characters.
 */
public final class MgmtPath {
    public static final MgmtPath ROOT = new MgmtPath(List.of());

    private final List<String> segments;

    private MgmtPath(List<String> segments) {
        this.segments = List.copyOf(segments);
    }

    /** Parses an absolute path; relative paths are rejected. */
    public static MgmtPath of(String path) {
        if (path == null || !path.startsWith("/"))
            throw new SwiftMqAccessException.AccessDenied("Path must be absolute: " + path);
        return resolve(ROOT, path);
    }

    /** Resolves {@code path} (absolute, or relative to {@code base}), including {@code .} and {@code ..}. */
    public static MgmtPath resolve(MgmtPath base, String path) {
        if (path == null)
            throw new SwiftMqAccessException.AccessDenied("Path is missing");
        List<String> result = new ArrayList<>(path.startsWith("/") ? List.of() : base.segments);
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals("."))
                continue;
            if (segment.equals("..")) {
                if (result.isEmpty())
                    throw new SwiftMqAccessException.AccessDenied("Path goes above the root: " + path);
                result.remove(result.size() - 1);
                continue;
            }
            validateName(segment, "path segment");
            result.add(segment);
        }
        return new MgmtPath(result);
    }

    /** Validates a single name (path segment, router, queue, ...) that will be placed into a command. */
    public static String validateName(String name, String what) {
        if (name == null || name.isBlank())
            throw new SwiftMqAccessException.AccessDenied("Empty " + what);
        if (name.length() > 512)
            throw new SwiftMqAccessException.AccessDenied(what + " is too long");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '"' || c == ';' || c == '/' || Character.isISOControl(c))
                throw new SwiftMqAccessException.AccessDenied("Invalid character in " + what + ": " + name);
        }
        if (!name.equals(name.strip()))
            throw new SwiftMqAccessException.AccessDenied(what + " must not start or end with whitespace: '" + name + "'");
        return name;
    }

    public MgmtPath child(String name) {
        List<String> s = new ArrayList<>(segments);
        s.add(validateName(name, "path segment"));
        return new MgmtPath(s);
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    /** The first segment (swiftlet or {@code .env}), or null for the root. */
    public String topLevel() {
        return segments.isEmpty() ? null : segments.get(0);
    }

    public List<String> segments() {
        return segments;
    }

    @Override
    public String toString() {
        return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MgmtPath p && p.segments.equals(segments);
    }

    @Override
    public int hashCode() {
        return segments.hashCode();
    }
}
