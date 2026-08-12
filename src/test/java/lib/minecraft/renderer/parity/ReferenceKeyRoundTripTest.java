package lib.minecraft.renderer.parity;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * The parser run over every reference the harness has actually written.
 *
 * <p>The hand-written grammar tests prove the rules; this proves the rules cover the corpus. Between
 * them they are what stops a reference being emitted in a spelling nothing can read - which would
 * otherwise look exactly like a reference that was never emitted at all, since an unreadable name
 * has no row to be missing from.
 */
@Tag("slow")
@DisplayName("Reference names round-trip through the codec")
final class ReferenceKeyRoundTripTest {

    /** The harness's entity reference directory. */
    private static final Path REFERENCES = ParityPaths.references("entities");

    /**
     * Lists every reference PNG the harness has written.
     *
     * @return the file names, sorted
     * @throws IOException if the reference directory cannot be listed
     */
    private static List<String> referenceNames() throws IOException {
        try (Stream<Path> files = Files.list(REFERENCES)) {
            return files.map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".png"))
                .sorted()
                .toList();
        }
    }

    @Test
    @DisplayName("every reference name parses, and formats back to itself")
    void everyReferenceRoundTrips() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(REFERENCES),
            "reference tree absent - run renderVanillaReferences first");

        ConcurrentMap<String, Entity> entities = EntityModelLoader.load();
        Assumptions.assumeFalse(entities.isEmpty(), "entity index absent - run entityModels first");
        AppearanceCodec codec = AppearanceCodec.of(entities);

        List<String> names = referenceNames();
        assertThat("the reference tree holds no PNG", names, is(not(empty())));

        List<String> malformed = new ArrayList<>();
        List<String> asymmetric = new ArrayList<>();
        for (String name : names) {
            AppearanceKey.Result result = codec.parse(name);
            if (result instanceof AppearanceKey.Result.Malformed bad) {
                malformed.add(bad.name() + " - " + bad.reason());
                continue;
            }
            String stem = name.substring(0, name.length() - ".png".length());
            String formatted = codec.format(((AppearanceKey.Result.Parsed) result).key());
            if (!formatted.equals(stem)) asymmetric.add(stem + " -> " + formatted);
        }

        assertThat("names the parser could not read", malformed, is(empty()));
        assertThat("names that do not format back to themselves", asymmetric, is(empty()));
    }

    @Test
    @DisplayName("no two reference names mean the same appearance")
    void referenceNamesAreDistinct() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(REFERENCES),
            "reference tree absent - run renderVanillaReferences first");

        ConcurrentMap<String, Entity> entities = EntityModelLoader.load();
        Assumptions.assumeFalse(entities.isEmpty(), "entity index absent - run entityModels first");
        AppearanceCodec codec = AppearanceCodec.of(entities);

        List<AppearanceKey> keys = new ArrayList<>();
        for (String name : referenceNames())
            if (codec.parse(name) instanceof AppearanceKey.Result.Parsed parsed) keys.add(parsed.key());

        assertThat("two names resolving to one appearance would make coverage unassertable",
            keys.stream().distinct().count(), is(equalTo((long) keys.size())));
    }
}
