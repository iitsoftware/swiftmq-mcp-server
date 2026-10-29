package com.swiftmq.mcp.catalog;

import java.util.List;
import java.util.Locale;

/**
 * SwiftMQ editions. UR builds on CE and HA on UR: each adds swiftlets and replaces some of its base's
 * swiftlets as a whole (e.g. HA's own sys$queuemanager).
 */
public enum Edition {
    CE("ce", "Community Edition"),
    UR("ur", "Universal Edition"),
    HA("ha", "High Availability Edition");

    private final String key;
    private final String distribution;

    Edition(String key, String distribution) {
        this.key = key;
        this.distribution = distribution;
    }

    /** Key of this edition's layer in the catalog JSON. */
    public String key() {
        return key;
    }

    /** This edition and its bases, most specific first, e.g. HA → [HA, UR, CE]. */
    public List<Edition> layers() {
        return switch (this) {
            case CE -> List.of(CE);
            case UR -> List.of(UR, CE);
            case HA -> List.of(HA, UR, CE);
        };
    }

    /**
     * The edition named in a router's {@code /.env} release, e.g. "13.02.04 Universal Edition".
     *
     * @return CE if the release names no known edition
     */
    public static Edition fromRelease(String release) {
        if (release != null) {
            String r = release.toLowerCase(Locale.ROOT);
            for (Edition e : values())
                if (r.contains(e.distribution.toLowerCase(Locale.ROOT)))
                    return e;
        }
        return CE;
    }
}
