package lib.minecraft.renderer.content.index;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.BlendMode;
import lib.minecraft.renderer.asset.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Pin on the pass declarations vanilla makes independently of one another - the {@code EMISSIVE}
 * shader define, {@code DepthStencilState.writeDepth}, {@code RenderSetup.sortOnUpload} and the
 * {@link BlendMode} a pipeline's blend function resolves to - taken over every
 * {@link Entity.OverlayLayer} {@link EntityModelLoader} ships, so a tooling walk that stops
 * resolving one of them cannot silently collapse it back onto another.
 * <p>
 * The collapse this guards against is a real one that shipped: keying the depth-write skip on
 * {@code emissive} suppressed it for the energy swirl, which vanilla registers with
 * {@code DepthStencilState.DEFAULT} and therefore writes. The corpus contains every disagreement
 * needed to catch that - an emissive pass that writes, an emissive pass that does not, a
 * non-emissive pass that sorts, and an additive pass that is emissive too - so any rule inferring
 * one declaration from another fails at least one row. Every overlay the walk classifies declares
 * its blend, so a cutout layer it stops resolving falls back to source-over and fails the cutout
 * roster.
 */
@DisplayName("overlay pipeline declarations")
class EntityModelLoaderOverlayPassTest {

    /**
     * Entities carrying an overlay whose pipeline declares no depth write - vanilla's eyes-style
     * passes. The breeze is on this list and on the emissive-and-writes list both, because it carries
     * two overlays that disagree: an eyes pass that does not write and a wind pass that does.
     */
    private static final Set<String> NO_DEPTH_WRITE = Set.of(
        "minecraft:breeze", "minecraft:cave_spider", "minecraft:copper_golem",
        "minecraft:ender_dragon", "minecraft:enderman", "minecraft:phantom", "minecraft:spider",
        "minecraft:warden");

    /**
     * Entities carrying an overlay vanilla submits through a pipeline declaring no blend function -
     * the clothing, outer and wool layers, both collars, the profession passes, the crackiness, the
     * trader llama's decor and the tropical fish's pattern.
     */
    private static final Set<String> CUTOUT = Set.of(
        "minecraft:bogged", "minecraft:cat", "minecraft:drowned", "minecraft:iron_golem",
        "minecraft:sheep", "minecraft:stray", "minecraft:trader_llama", "minecraft:tropical_fish",
        "minecraft:villager", "minecraft:wolf", "minecraft:zombie_villager");

    /** Overlays whose pipeline blends additively - vanilla's two energy swirls. */
    private static final Set<String> ADDITIVE = Set.of("minecraft:creeper", "minecraft:wither");

    private static ConcurrentMap<String, Entity> index() {
        return EntityModelLoader.load();
    }

    @Test
    @DisplayName("an emissive overlay may write depth or not, and the corpus carries both")
    void depthWriteIsIndependentOfEmissive() {
        Set<String> emissiveWithoutWrite = new TreeSet<>();
        Set<String> emissiveWithWrite = new TreeSet<>();
        index().forEach((id, entity) -> entity.overlays().forEach(overlay -> {
            if (!overlay.pass().emissive()) return;
            (overlay.pass().writesDepth() ? emissiveWithWrite : emissiveWithoutWrite).add(id);
        }));
        assertThat(emissiveWithoutWrite, is(new TreeSet<>(Set.of(
            "minecraft:cave_spider", "minecraft:copper_golem", "minecraft:ender_dragon",
            "minecraft:enderman", "minecraft:phantom", "minecraft:spider"))));
        assertThat(emissiveWithWrite, is(new TreeSet<>(Set.of(
            "minecraft:breeze", "minecraft:creeper", "minecraft:wither"))));
    }

    @Test
    @DisplayName("every overlay declaring no depth write is one of vanilla's eyes-style passes")
    void noDepthWriteRosterMatches() {
        Set<String> flagged = new TreeSet<>();
        index().forEach((id, entity) -> entity.overlays().forEach(overlay -> {
            if (!overlay.pass().writesDepth()) flagged.add(id);
        }));
        assertThat(flagged, is(new TreeSet<>(NO_DEPTH_WRITE)));
    }

    @Test
    @DisplayName("both energy swirls write depth and sort, which is what occludes their own far faces")
    void energySwirlsWriteAndSort() {
        Set<String> flagged = new TreeSet<>();
        index().forEach((id, entity) -> entity.overlays().forEach(overlay -> {
            if (overlay.pass().blend() != BlendMode.ADD) return;
            flagged.add(id);
            assertThat(id + " writes depth", overlay.pass().writesDepth(), is(true));
            assertThat(id + " sorts", overlay.pass().sorted(), is(true));
        }));
        assertThat(flagged, is(new TreeSet<>(ADDITIVE)));
    }

    @Test
    @DisplayName("a sorted overlay need not be emissive, so the sort cannot be read off that flag")
    void sortedIsIndependentOfEmissive() {
        Set<String> sortedNonEmissive = new TreeSet<>();
        index().forEach((id, entity) -> entity.overlays().forEach(overlay -> {
            if (overlay.pass().sorted() && !overlay.pass().emissive()) sortedNonEmissive.add(id);
        }));
        assertThat(sortedNonEmissive, is(new TreeSet<>(Set.of(
            "minecraft:breeze", "minecraft:horse", "minecraft:slime", "minecraft:warden"))));
    }

    @Test
    @DisplayName("every overlay vanilla submits through a cutout pipeline is written whole")
    void cutoutOverlaysReplace() {
        Set<String> cutout = new TreeSet<>();
        index().forEach((id, entity) -> entity.overlays().forEach(overlay -> {
            if (overlay.pass().blend() == BlendMode.REPLACE) cutout.add(id);
        }));
        assertThat(cutout, is(new TreeSet<>(CUTOUT)));
    }

}
