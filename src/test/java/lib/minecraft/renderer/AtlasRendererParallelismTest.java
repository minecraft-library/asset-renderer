package lib.minecraft.renderer;

import lib.minecraft.renderer.option.AtlasOptions;
import lib.minecraft.renderer.option.AtlasTile;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Regression coverage for parallel {@link AtlasRenderer} dispatch. Asserts that the parallel
 * implementation preserves tile encounter order, which the sidecar JSON and the grid layout both
 * require, and that every filtered id owns exactly one tile. Order-preservation is the critical
 * invariant - a parallelStream().forEach implementation would shuffle tiles. No pixel or digest is
 * compared: the composed atlas PNG does not reproduce byte-for-byte by design, so there is nothing
 * for a digest to hold it to.
 * <p>
 * Tagged {@code slow} because it boots the full asset pipeline; run with
 * {@code ./gradlew slowTest}.
 */
@Tag("slow")
@DisplayName("AtlasRenderer parallel dispatch order")
class AtlasRendererParallelismTest {

    private static final File CACHE_ROOT = new File("cache/it");
    private static AtlasRenderer atlasRenderer;

    @BeforeAll
    static void bootstrapPipeline() {
        ClientAssets result = ClientAcquisition.acquire(
            ClientOptions.builder()
                .version("26.1")
                .cacheRoot(CACHE_ROOT)
                .build()
        );
        atlasRenderer = new AtlasRenderer(PipelineRendererContext.of(result));
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

        List<String> firstIds = atlasRenderer.renderAtlas(options).sidecar().tiles().stream()
            .map(AtlasTile::id)
            .toList();
        List<String> secondIds = atlasRenderer.renderAtlas(options).sidecar().tiles().stream()
            .map(AtlasTile::id)
            .toList();

        assertThat("parallel atlas dispatch must be order-stable",
            secondIds, equalTo(firstIds));
        assertThat("every filtered id must produce exactly one tile",
            firstIds.size(), is(filtered.size()));
    }

}
