package lib.minecraft.renderer.request;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity.OverlayLayer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.content.table.EntityModelLoader;
import lib.minecraft.renderer.vanilla.appearance.AppearanceGate;
import lib.minecraft.renderer.vanilla.appearance.Flag;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Two readings of the {@link AppearanceGate} render conditions for the non-default appearances the
 * default-only parity sweep cannot reach: the creeper charged gate and the sheep sheared flag gate seen
 * through {@link AppearanceOptions#resolve}, which drops a flag- or charged-gated overlay that fails while deferring
 * the tint gate to the render stage, and each gate's own arms evaluated directly against an
 * {@link AppearanceOptions}, the only coverage those arms have.
 * <p>
 * The class initialiser builds the whole shipped entity index through
 * {@link EntityModelLoader#load()} to reach two subjects, so the class costs a full index load
 * and carries no slow tag.
 */
@DisplayName("AppearanceOptions.resolve appearance gates")
class EntityResolveTest {

    private static final @NotNull ConcurrentMap<String, Entity> DEFS =
        EntityModelLoader.load();

    @Test
    @DisplayName("charged gate: the creeper energy swirl renders only when charged")
    void chargedGate() {
        Entity creeper = DEFS.get("minecraft:creeper");
        assertThat("default (uncharged) drops the charged swirl",
            AppearanceOptions.builder().build().resolve(creeper).overlays().isEmpty(), is(true));
        assertThat("charged keeps the swirl",
            AppearanceOptions.builder().charged(true).build().resolve(creeper).overlays().size(), is(1));
    }

    @Test
    @DisplayName("flag gate: shearing drops the wool layer but keeps the tint-gated undercoat")
    void shearedFlagGate() {
        Entity sheep = DEFS.get("minecraft:sheep");
        assertThat("default keeps both wool overlays",
            AppearanceOptions.builder().build().resolve(sheep).overlays().size(), is(2));

        List<OverlayLayer> sheared = AppearanceOptions.builder().sheared(true).build().resolve(sheep).overlays();
        assertThat("shearing drops the sheared-flag wool layer", sheared.size(), is(1));
        assertThat("the surviving overlay is the tint-gated undercoat (deferred to render)",
            sheared.getFirst().gate().orElseThrow() instanceof AppearanceGate.TintedGate, is(true));
    }

    @Test
    @DisplayName("gate arms evaluate their vanilla branch")
    void gateArms() {
        assertThat(AppearanceOptions.builder().charged(true).build().passes(new AppearanceGate.Selected(Flag.CHARGED, true)), is(true));
        assertThat(AppearanceOptions.builder().build().passes(new AppearanceGate.Selected(Flag.CHARGED, true)), is(false));
        assertThat("sheared flag false renders while un-sheared",
            AppearanceOptions.builder().build().passes(new AppearanceGate.Selected(Flag.SHEARED, false)), is(true));
        assertThat("sheared flag false is gated off once sheared",
            AppearanceOptions.builder().sheared(true).build().passes(new AppearanceGate.Selected(Flag.SHEARED, false)), is(false));
    }
}
