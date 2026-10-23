package lib.minecraft.renderer.content.index;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity.OverlayLayer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.AppearanceGate;
import lib.minecraft.renderer.vanilla.appearance.Flag;
import lib.minecraft.renderer.vanilla.appearance.Size;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The {@link AppearanceGate} render conditions two shipped entity rows carry for the non-default
 * appearances the default-only parity sweep cannot reach, read through {@link AppearanceOptions#resolve}:
 * the creeper's charged swirl, which the fold keeps only for a charged appearance, and the sheep's
 * sheared wool, which the fold drops once sheared while deferring the undercoat's tint gate to the
 * render stage. Each case pins both that the shipped row carries the gate and that the fold honours it.
 * It also holds the age a subject renders at, which the armour stand's second-shell gate answers for
 * its small form, read through {@link AppearanceOptions#rendersBaby}, and the pose a selected carried
 * block swaps in for the enderman's and the iron golem's bodies, with the {@code carried} gate it admits.
 * <p>
 * The class initialiser builds the whole shipped entity index through
 * {@link EntityModelLoader#load()} to reach a handful of subjects, so the class costs a full index load
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
    @DisplayName("carried block: the enderman and the iron golem take their carrier pose while a block is selected")
    void carriedBlockSwapsTheWearerPose() {
        for (String id : List.of("minecraft:enderman", "minecraft:iron_golem")) {
            Entity definition = DEFS.get(id);
            Entity form = definition.axes().carried()
                .orElseThrow(() -> new AssertionError(id + " ships no carried form"));
            EntityPose carrier = form.pose();
            assertThat(id + "'s carrier pose is not its body's", carrier == definition.pose(), is(false));
            assertThat(id + "'s carried form draws the row's own mesh", form.model() == definition.model(), is(true));

            AppearanceOptions held = AppearanceOptions.builder().carried("minecraft:poppy").build();
            Entity resolved = held.resolve(definition);
            assertThat(id + " takes its carrier pose while a block is selected", resolved.pose() == carrier, is(true));
            assertThat(id + "'s passes sharing the body's pose follow it onto the carrier",
                resolved.overlays().stream().filter(pass -> pass.pose() == definition.pose()).count(), is(0L));
            assertThat(id + " carries a pass that follows", resolved.overlays().stream()
                .anyMatch(pass -> pass.pose() == carrier), is(true));
            assertThat(id + " selects the carried flag while a block is selected", held.selects(Flag.CARRIED), is(true));

            for (AppearanceOptions empty : List.of(AppearanceOptions.builder().build(),
                AppearanceOptions.builder().carried("none").build())) {
                assertThat(id + " keeps its body's pose with no block held", empty.resolve(definition).pose() == definition.pose(), is(true));
                assertThat(id + " selects no carried flag with no block held", empty.selects(Flag.CARRIED), is(false));
            }
        }
    }

    @Test
    @DisplayName("render age: a small armour stand is a baby by its size, a skeleton never is")
    void renderAgeFollowsVanillasBaby() {
        AppearanceOptions small = AppearanceOptions.builder().size(Optional.of(Size.SMALL)).build();
        AppearanceOptions baby = AppearanceOptions.builder().age(Age.BABY).build();
        Entity stand = DEFS.get("minecraft:armor_stand");
        assertThat("a small stand renders as a baby", small.rendersBaby(stand), is(true));
        assertThat("a full-size stand does not", AppearanceOptions.builder().build().rendersBaby(stand), is(false));
        assertThat("the stand has no age, so the age knob makes no baby of it", baby.rendersBaby(stand), is(false));
        Entity zombie = DEFS.get("minecraft:zombie");
        assertThat("a baby zombie renders as a baby", baby.rendersBaby(zombie), is(true));
        assertThat("a zombie ages on age, never on size", small.rendersBaby(zombie), is(false));
        assertThat("a skeleton has no baby form", baby.rendersBaby(DEFS.get("minecraft:skeleton")), is(false));
        assertThat("a baby villager is one with no second shell",
            baby.rendersBaby(DEFS.get("minecraft:villager")), is(true));
    }
}
