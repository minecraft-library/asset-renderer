package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.call.request.AtlasOptions;
import lib.minecraft.renderer.call.result.AtlasResult;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Regression coverage for parallel {@link AtlasRenderer} dispatch. Asserts that the parallel
 * implementation preserves tile encounter order, which the sidecar JSON and the grid layout both
 * require, that every filtered id owns exactly one tile, and that the skipped rows keep the order the
 * pass met their subjects in. Order-preservation is the critical invariant - a
 * parallelStream().forEach implementation would shuffle tiles. No pixel or digest is compared: the
 * composed atlas PNG does not reproduce byte-for-byte by design, so there is nothing for a digest to
 * hold it to.
 * <p>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class
 * where nothing has extracted the client yet.
 */
@DisplayName("AtlasRenderer parallel dispatch order")
@ExtendWith(ClientAssetsExtension.class)
class AtlasRendererParallelismTest {

    private static AtlasRenderer atlasRenderer;

    @BeforeAll
    static void bootstrapPipeline() {
        atlasRenderer = new AtlasRenderer(ClientAssetsExtension.context());
    }

    @Test
    @DisplayName("tile list preserves encounter order across parallel dispatch runs")
    void atlasTileOrderIsStable() {
        // Small filter keeps the test under ~5s on cache hit. The ids render via different dispatch
        // paths (BlockRenderer, FluidRenderer, ItemRenderer) so any ordering bug affects multiple
        // source tags. Each owns exactly one tile: the block pass skips an id whose faithful icon is a
        // flat item sprite and the item pass renders that one instead, so no id lands twice.
        List<String> filtered = List.of("minecraft:stone", "minecraft:oak_planks", "minecraft:water",
            "minecraft:diamond_sword", "minecraft:golden_apple");
        Predicate<String> filter = filtered::contains;
        AtlasOptions options = AtlasOptions.builder()
            .filter(Optional.of(filter))
            .tileSize(64)
            .build();

        AtlasResult.Sidecar first = atlasRenderer.render(options).sidecar();
        List<String> firstIds = first.tiles().stream()
            .map(AtlasResult.Tile::id)
            .toList();
        List<String> secondIds = atlasRenderer.render(options).sidecar().tiles().stream()
            .map(AtlasResult.Tile::id)
            .toList();

        assertThat("parallel atlas dispatch must be order-stable",
            secondIds, equalTo(firstIds));
        assertThat("every filtered id must produce exactly one tile",
            firstIds.size(), is(filtered.size()));
        assertThat("nothing on the vanilla stack is skipped", first.skipped(), is(empty()));
    }

    @Test
    @DisplayName("skipped rows keep the order the pass met them in across parallel dispatch runs")
    void skippedRowOrderIsStable() {
        // A context whose known block ids end in ids its lookup answers absent skips each of them, so the
        // run holds several rows for the parallel dispatch to reorder. The block pass meets them in the
        // order they are listed, which is the order the rows must keep.
        List<String> absent = IntStream.range(0, 6)
            .mapToObj(i -> "minecraft:atlas_parallelism_absent_" + i)
            .toList();
        RendererContext over = ClientAssetsExtension.context();
        RendererContext disagreeing = new RendererContext.Forwarding() {

            @Override
            public @NotNull RendererContext delegate() {
                return over;
            }

            @Override
            public @NotNull ConcurrentList<String> knownBlockIds() {
                ConcurrentList<String> ids = Concurrent.newList(over.knownBlockIds());
                ids.addAll(absent);
                return ids;
            }

        };

        Set<String> subjects = new HashSet<>(absent);
        subjects.addAll(List.of("minecraft:oak_planks", "minecraft:water", "minecraft:diamond_sword"));
        AtlasOptions options = AtlasOptions.builder()
            .filter(Optional.of(subjects::contains))
            .tileSize(64)
            .progressLogging(false)
            .build();

        AtlasRenderer renderer = new AtlasRenderer(disagreeing);
        for (int run = 1; run <= 2; run++) {
            List<String> skipped = renderer.render(options).sidecar().skipped().stream()
                .map(AtlasResult.Skipped::id)
                .toList();
            assertThat("run " + run + " keeps the order the pass met them in", skipped, equalTo(absent));
        }
    }

}
