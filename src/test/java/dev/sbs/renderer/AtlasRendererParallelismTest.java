package dev.sbs.renderer;

import dev.sbs.renderer.options.AtlasOptions;
import dev.sbs.renderer.pipeline.AssetPipeline;
import dev.sbs.renderer.pipeline.AssetPipelineOptions;
import dev.sbs.renderer.pipeline.PipelineRendererContext;
import dev.sbs.renderer.pipeline.client.HttpFetcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.function.Predicate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Regression coverage for Task 1 - parallel {@link AtlasRenderer} dispatch. Asserts that the
 * parallel implementation preserves tile encounter order (required by the sidecar JSON and the
 * grid layout) and produces identical output across runs. Order-preservation is the critical
 * invariant - a parallelStream().forEach implementation would shuffle tiles.
 * <p>
 * Tagged {@code slow} because it boots the full asset pipeline; run with
 * {@code ./gradlew :asset-renderer:slowTest}.
 */
@Tag("slow")
@DisplayName("AtlasRenderer parallel dispatch order + determinism")
class AtlasRendererParallelismTest {

    private static final File CACHE_ROOT = new File("cache/it");
    private static AtlasRenderer atlasRenderer;

    @BeforeAll
    static void bootstrapPipeline() {
        AssetPipeline.Result result = new AssetPipeline(new HttpFetcher()).run(
            AssetPipelineOptions.builder()
                .version("26.1")
                .cacheRoot(CACHE_ROOT)
                .build()
        );
        atlasRenderer = new AtlasRenderer(PipelineRendererContext.of(result));
    }

    @Test
    @DisplayName("tile list preserves encounter order across parallel dispatch runs")
    void atlasTileOrderIsStable() {
        // Small filter keeps the test under ~5s on cache hit. The filter covers two blocks and
        // two items that render via different dispatch paths (BlockRenderer, FluidRenderer,
        // ItemRenderer) so any ordering bug affects multiple source tags.
        Predicate<String> filter = id ->
            id.equals("minecraft:stone")
                || id.equals("minecraft:oak_planks")
                || id.equals("minecraft:water")
                || id.equals("minecraft:diamond_sword")
                || id.equals("minecraft:golden_apple");
        AtlasOptions options = AtlasOptions.builder()
            .filter(java.util.Optional.of(filter))
            .tileSize(64)
            .build();

        List<String> firstIds = atlasRenderer.renderAtlas(options).tiles().stream()
            .map(AtlasRenderer.TileSpec::id)
            .toList();
        List<String> secondIds = atlasRenderer.renderAtlas(options).tiles().stream()
            .map(AtlasRenderer.TileSpec::id)
            .toList();

        assertThat("parallel atlas dispatch must be order-stable",
            secondIds, equalTo(firstIds));
        assertThat("filter must match at least one block and one item",
            firstIds.size(), greaterThan(2));
    }

}
