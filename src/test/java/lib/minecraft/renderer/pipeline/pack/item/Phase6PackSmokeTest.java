package lib.minecraft.renderer.pipeline.pack.item;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.pipeline.pack.PackAcquisition;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Smoke test proving the phase-6 item-tree walker consumes the on-disk hypixel-skyblock pack's
 * {@code hypixel_skyblock}-namespace item-definition trees (05-models.md §3, decision 11) - what
 * phase 5 left on the scanned path but unparsed. A known skyblock id resolves through the walker to
 * its model leaf under the neutral context, which is the Catharsis degradation contract for the
 * skyblock trees that branch. Tagged slow because it reads the gitignored pack cache; skips when
 * absent.
 */
@Tag("slow")
@DisplayName("Phase 6 item-tree pack smoke (hypixel-skyblock)")
class Phase6PackSmokeTest {

    private static final Path VANILLA = Path.of("cache/asset-renderer/vanilla/26.1");
    private static final Path PACKS = Path.of("cache/asset-renderer/packs");
    private static final String NS = "hypixel_skyblock";

    @Test
    @DisplayName("hypixel-skyblock item-definition trees parse and resolve through the walker")
    void hypixelItemTreesResolve(@TempDir Path cache) throws IOException {
        assumeTrue(Files.isDirectory(VANILLA), () -> "vanilla pack not extracted: " + VANILLA);
        Path hypixel = PACKS.resolve("hypixel-skyblock");
        assumeTrue(Files.isDirectory(hypixel), () -> "hypixel-skyblock pack not present under " + PACKS);

        PackStack stack = PackAcquisition.acquire(List.of(hypixel), cache, VANILLA);
        ConcurrentMap<String, ItemModelTree> trees = ItemModelTreeLoader.load(stack);

        long hypixelTrees = trees.keySet().stream().filter(id -> id.startsWith(NS + ":")).count();
        assertThat("hypixel item-definition trees parsed", hypixelTrees, greaterThan(900L));

        // A known skyblock id resolves through the walker under the neutral context.
        ItemModelTree known = trees.get(NS + ":item/abiphones/abiphone_basic");
        assertThat("known skyblock item-def tree present", known, notNullValue());
        assertThat("skyblock tree resolves to a model leaf",
            ItemModelWalker.resolve(known, ItemModelContext.gui()).modelId().isPresent(), is(true));
    }

}
