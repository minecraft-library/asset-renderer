package lib.minecraft.renderer.asset.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end decode of the two on-disk Catharsis sample packs, pinning the decoder against real
 * containers: the full file counts (eureka 2,780; FurSky 16,857), that every path reads back
 * (decompressing GZIP entries), and that the wrapped {@code pack.mcmeta} resolves. Tagged slow because
 * it reads the gitignored pack cache; skips gracefully when the samples are absent.
 * <p>
 * The two counts are golden values over two {@code .cats.zip} files that live only in that cache, so on
 * a checkout without them every method assumes away and the class is a silent no-op that reports as
 * neither passed nor failed. A green run here is evidence only where the samples are present.
 */
@Tag("slow")
@DisplayName("CATS decode of the on-disk sample packs")
class PackContainerCatsSampleTest {

    private static final Path PACKS = Path.of("cache/asset-renderer/packs");

    @Test
    @DisplayName("eureka.cats.zip decodes to 2,780 files, all readable")
    void eureka() {
        decodeAndAssert("eureka.cats.zip", 2_780);
    }

    @Test
    @DisplayName("FurSky Reborn.cats.zip decodes to 16,857 files, all readable")
    void furSky() {
        decodeAndAssert("FurSky Reborn.cats.zip", 16_857);
    }

    private static void decodeAndAssert(String fileName, int expectedFiles) {
        Path source = PACKS.resolve(fileName);
        assumeTrue(Files.isRegularFile(source), () -> "sample pack not present: " + source);

        PackContainer container = PackContainer.detect(source);
        assertThat(container, is(instanceOf(PackContainer.Cats.class)));

        assertThat((int) container.entries("").count(), is(expectedFiles));

        // Every path reads back (GZIP entries inflate without error).
        long readable = container.entries("")
            .filter(path -> container.bytes(path).isPresent())
            .count();
        assertThat((int) readable, is(expectedFiles));

        // The wrapped container exposes a pack.mcmeta (inner if present, outer decoy otherwise).
        assertThat(container.bytes("pack.mcmeta").orElseThrow().length, greaterThan(0));
    }

}
