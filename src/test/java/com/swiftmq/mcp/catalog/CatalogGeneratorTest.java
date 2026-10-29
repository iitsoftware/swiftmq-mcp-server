package com.swiftmq.mcp.catalog;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regenerates the catalog from the local CE/UR/HA distributions (if present) and checks that the committed
 * resource is up to date. Skipped when the distributions aren't available, e.g. on CI.
 */
class CatalogGeneratorTest {

    @Test
    void committedCatalogMatchesDistribution() throws Exception {
        Map<Edition, File> dists = new EnumMap<>(Edition.class);
        for (Edition e : Edition.values()) {
            File dist = new File(System.getProperty("swiftmq." + e.key() + ".dist", "swiftmq/swiftmq_" + e.key() + "_13.2.4_router"));
            assumeTrue(dist.isDirectory(), "SwiftMQ " + e + " distribution not available: " + dist);
            dists.put(e, dist);
        }

        Catalog generated = CatalogGenerator.generate(dists);
        assertEquals(16, generated.swiftlets(Edition.CE).size());
        assertEquals(22, generated.swiftlets(Edition.UR).size());
        assertEquals(23, generated.swiftlets(Edition.HA).size());
        // Compare as JSON: that is exactly what is committed (defaults and empty values are omitted there)
        String committed = Catalog.MAPPER.writeValueAsString(Catalog.load());
        assertEquals(committed, Catalog.MAPPER.writeValueAsString(generated), "swiftmq-metadata.json is stale; run: mvn -Pgen-catalog process-classes");
    }

    @Test
    void rejectsEmptyKernelDir() throws Exception {
        File empty = Files.createTempDirectory("kernel").toFile();
        try {
            assertThrows(IllegalStateException.class, () -> CatalogGenerator.generate(Map.of(Edition.CE, empty, Edition.UR, empty, Edition.HA, empty)));
        } finally {
            empty.delete();
        }
    }
}
