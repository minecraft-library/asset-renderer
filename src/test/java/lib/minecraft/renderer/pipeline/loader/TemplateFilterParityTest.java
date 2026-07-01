package lib.minecraft.renderer.pipeline.loader;

import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Parity gate for the structural empty-model filter that replaced the hardcoded
 * {@code TEMPLATE_BLOCK_NAMES} / {@code TEMPLATE_ITEM_NAMES} sets. Runs the real 26.1 pipeline and
 * asserts the filter keeps every model that actually renders a tile while dropping only the
 * parent / template models that render nothing.
 * <p>
 * The load-bearing checks are the <b>kept</b> assertions: every concrete variant render - slab
 * tops, door halves, stair corners, the multipart submodels, and every armor-trim /
 * clock / compass / light frame - must survive, because those were all in the stress-tested
 * reference atlas. Only pure inheritance parents ({@code generated}, {@code handheld},
 * {@code cross}, {@code slab}, {@code block}) and intentionally-invisible ids drop out.
 * <p>
 * Tagged {@code slow}: needs a real {@link Pipeline.Result}. Run with
 * {@code ./gradlew :asset-renderer:slowTest --tests "*TemplateFilterParityTest"}.
 */
@Tag("slow")
@DisplayName("Structural empty-model filter parity")
class TemplateFilterParityTest {

    /** Shared cache root with {@code PipelineIntegrationTest} so both slow tests reuse one extracted jar. */
    private static final File CACHE_ROOT = new File("cache/it");

    /** Full 26.1 pipeline result feeding both the block and item index loaders. */
    private static Pipeline.Result result;

    /** Block-entity model + variant maps the index loaders consume alongside {@link #result}. */
    private static BlockModelLoader.LoadResult be;

    /** Runs the real 26.1 pipeline and loads the block-entity models once for both filter tests. */
    @BeforeAll
    static void setup() {
        result = Pipeline.run(PipelineOptions.builder().version("26.1").cacheRoot(CACHE_ROOT).build());
        be = BlockModelLoader.load();
    }

    @Test
    @DisplayName("block filter keeps renderable variants, drops only empty templates")
    void blockFilter() {
        Set<String> built = new HashSet<>(BlockIndexLoader.buildUnfiltered(result, be.models(), be.variants()).keySet());
        Set<String> kept = new HashSet<>(BlockIndexLoader.load(result, be.models(), be.variants()).keySet());
        System.out.printf("[block] built=%d kept=%d dropped=%d%n", built.size(), kept.size(), built.size() - kept.size());

        // Concrete variant renders - real geometry + resolvable texture - must survive.
        for (String id : new String[]{
            "minecraft:stone", "minecraft:oak_stairs", "minecraft:yellow_carpet",
            "minecraft:acacia_slab_top", "minecraft:acacia_door_bottom_left", "minecraft:acacia_stairs_inner",
            "minecraft:tripwire_n", "minecraft:melon_stem_stage0"
        }) assertThat(id + " kept", kept.contains(id), is(true));

        // Pure parent / empty templates (only unresolved #variable textures) drop, plus invisibles.
        for (String id : new String[]{
            "minecraft:cross", "minecraft:slab", "minecraft:stairs", "minecraft:block", "minecraft:leaves",
            "minecraft:air", "minecraft:barrier"
        }) assertThat(id + " dropped", kept.contains(id), is(false));
    }

    @Test
    @DisplayName("item filter keeps renderable variants, drops only empty templates")
    void itemFilter() {
        Set<String> kept = new HashSet<>(ItemIndexLoader.load(result, be.models()).keySet());
        System.out.printf("[item] kept=%d%n", kept.size());

        // Every range / trim / sprite variant renders, so it stays.
        for (String id : new String[]{
            "minecraft:diamond_sword", "minecraft:apple",
            "minecraft:clock_00", "minecraft:clock_63", "minecraft:compass_16", "minecraft:recovery_compass_00",
            "minecraft:light_05", "minecraft:chainmail_helmet_diamond_trim", "minecraft:turtle_helmet_amethyst_trim"
        }) assertThat(id + " kept", kept.contains(id), is(true));

        // Pure parent templates (no layerN sprite, no elements) and invisible air drop.
        for (String id : new String[]{
            "minecraft:generated", "minecraft:handheld", "minecraft:air"
        }) assertThat(id + " dropped", kept.contains(id), is(false));
    }

}
