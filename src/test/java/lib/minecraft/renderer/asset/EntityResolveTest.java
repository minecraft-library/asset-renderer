package lib.minecraft.renderer.asset;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity.OverlayLayer;
import lib.minecraft.renderer.option.AppearanceGate;
import lib.minecraft.renderer.option.EntityAppearance;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Two readings of the {@link AppearanceGate} render conditions for the non-default appearances the
 * default-only parity sweep cannot reach: the creeper charged gate and the sheep sheared flag gate seen
 * through {@link Entity#resolve}, which drops a flag- or charged-gated overlay that fails while deferring
 * the tint gate to the render stage, and each gate's own arms evaluated directly against an
 * {@link EntityAppearance}, the only coverage those arms have.
 * <p>
 * The class initialiser builds the whole shipped entity index through
 * {@link EntityModelLoader#load(Diagnostics)} to reach two subjects, so the class costs a full index load
 * and carries no slow tag.
 */
@DisplayName("Entity.resolve appearance gates")
class EntityResolveTest {

    private static final @NotNull ConcurrentMap<String, Entity> DEFS =
        EntityModelLoader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));

    @Test
    @DisplayName("charged gate: the creeper energy swirl renders only when charged")
    void chargedGate() {
        Entity creeper = DEFS.get("minecraft:creeper");
        assertThat("default (uncharged) drops the charged swirl",
            creeper.resolve(EntityAppearance.builder().build()).overlays().isEmpty(), is(true));
        assertThat("charged keeps the swirl",
            creeper.resolve(EntityAppearance.builder().charged(true).build()).overlays().size(), is(1));
    }

    @Test
    @DisplayName("flag gate: shearing drops the wool layer but keeps the tint-gated undercoat")
    void shearedFlagGate() {
        Entity sheep = DEFS.get("minecraft:sheep");
        assertThat("default keeps both wool overlays",
            sheep.resolve(EntityAppearance.builder().build()).overlays().size(), is(2));

        List<OverlayLayer> sheared = sheep.resolve(EntityAppearance.builder().sheared(true).build()).overlays();
        assertThat("shearing drops the sheared-flag wool layer", sheared.size(), is(1));
        assertThat("the surviving overlay is the tint-gated undercoat (deferred to render)",
            sheared.getFirst().gate().orElseThrow() instanceof AppearanceGate.TintedGate, is(true));
    }

    @Test
    @DisplayName("gate arms evaluate their vanilla branch")
    void gateArms() {
        assertThat(new AppearanceGate.ChargedGate().test(EntityAppearance.builder().charged(true).build()), is(true));
        assertThat(new AppearanceGate.ChargedGate().test(EntityAppearance.builder().build()), is(false));
        assertThat("sheared flag false renders while un-sheared",
            new AppearanceGate.FlagGate("sheared", false).test(EntityAppearance.builder().build()), is(true));
        assertThat("sheared flag false is gated off once sheared",
            new AppearanceGate.FlagGate("sheared", false).test(EntityAppearance.builder().sheared(true).build()), is(false));
    }
}
