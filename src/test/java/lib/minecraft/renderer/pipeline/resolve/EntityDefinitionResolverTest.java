package lib.minecraft.renderer.pipeline.resolve;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.option.AppearanceGate;
import lib.minecraft.renderer.option.EntityAppearance;
import lib.minecraft.renderer.pipeline.load.entity.EntityFamilyReader;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Entity.OverlayLayer;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Exercises the {@link AppearanceGate} render conditions through {@link EntityDefinitionResolver} for
 * the non-default appearances the (default-only) parity sweep cannot reach: the creeper charged gate
 * and the sheep sheared flag gate. Pins that the resolver drops flag / charged-gated overlays that
 * fail while deferring the tint gate to the render stage.
 */
@DisplayName("EntityDefinitionResolver appearance gates")
class EntityDefinitionResolverTest {

    private static final @NotNull ConcurrentMap<String, Entity> DEFS =
        EntityFamilyReader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));

    @Test
    @DisplayName("charged gate: the creeper energy swirl renders only when charged")
    void chargedGate() {
        Entity creeper = DEFS.get("minecraft:creeper");
        assertThat("default (uncharged) drops the charged swirl",
            EntityDefinitionResolver.resolve(creeper, EntityAppearance.builder().build()).overlays().isEmpty(), is(true));
        assertThat("charged keeps the swirl",
            EntityDefinitionResolver.resolve(creeper, EntityAppearance.builder().charged(true).build()).overlays().size(), is(1));
    }

    @Test
    @DisplayName("flag gate: shearing drops the wool layer but keeps the tint-gated undercoat")
    void shearedFlagGate() {
        Entity sheep = DEFS.get("minecraft:sheep");
        assertThat("default keeps both wool overlays",
            EntityDefinitionResolver.resolve(sheep, EntityAppearance.builder().build()).overlays().size(), is(2));

        List<OverlayLayer> sheared = EntityDefinitionResolver.resolve(sheep, EntityAppearance.builder().sheared(true).build()).overlays();
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
