package com.swiftmq.mcp.catalog;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regenerates the catalog from the local CE distribution (if present) and checks that the committed
 * resource is up to date. Skipped when the distribution isn't available, e.g. on CI.
 */
class CatalogGeneratorTest {

    @Test
    void committedCatalogMatchesDistribution() throws Exception {
        File kernel = new File(System.getProperty("swiftmq.ce.kernel", "swiftmq/swiftmq_ce_13.2.4_router/kernel"));
        assumeTrue(kernel.isDirectory(), "SwiftMQ CE distribution not available: " + kernel);

        Catalog generated = CatalogGenerator.generate(kernel);
        assertEquals(16, generated.swiftlets(Catalog.CE).size());
        // Compare as JSON: that is exactly what is committed (defaults and empty values are omitted there)
        String committed = Catalog.MAPPER.writeValueAsString(Catalog.load());
        assertEquals(committed, Catalog.MAPPER.writeValueAsString(generated), "swiftmq-metadata.json is stale; run: mvn -Pgen-catalog process-classes");
    }

    @Test
    void rejectsEmptyKernelDir() throws Exception {
        File empty = Files.createTempDirectory("kernel").toFile();
        try {
            assertThrows(IllegalStateException.class, () -> CatalogGenerator.generate(empty));
        } finally {
            empty.delete();
        }
    }
}
