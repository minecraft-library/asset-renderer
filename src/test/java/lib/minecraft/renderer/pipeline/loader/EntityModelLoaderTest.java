package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.option.EntityAppearance;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Entity.OverlayLayer;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Load-contract tests for {@link EntityModelLoader}, exercising the family read of
 * {@code entity_models.json} directly. Load-bearing canaries: the wolf
 * variant/state texture join, the baby three-source texture chain, the dyed-collar presence, the
 * option-encoded variant coat map + resolver fold, and the depth-clearance auto-skip on a
 * base-mesh-inheriting overlay.
 */
@DisplayName("EntityModelLoader native load")
class EntityModelLoaderTest {

    @Test
    @DisplayName("wolf base texture ref equals the default variant option's wild texture")
    void wolfWildEqualsTextureRef() {
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));
        Entity pale = coat(defs, "minecraft:wolf", "pale");
        assertThat(pale.axes().stateTextures().get("wild"), is("wolf/wolf"));
        assertThat("wild state equals the default texture_ref",
            Optional.of(pale.axes().stateTextures().get("wild")), equalTo(pale.textureRef()));
        assertThat(pale.axes().stateTextures().keySet(), hasItems("wild", "tame", "angry"));
        assertThat(coat(defs, "minecraft:wolf", "ashen").axes().stateTextures().get("tame"), is("wolf/wolf_ashen_tame"));
    }

    @Test
    @DisplayName("baby texture resolves via the three-source chain (variant baby_texture / isBaby binding / <adult>_baby)")
    void babyThreeSourceChain() {
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));
        // 1) variant-table per-option baby_texture (per coat sub-definition)
        assertThat(coat(defs, "minecraft:cow", "temperate").axes().stateTextures().get("baby"), is("cow/cow_temperate_baby"));
        assertThat(coat(defs, "minecraft:cow", "warm").axes().stateTextures().get("baby"), is("cow/cow_warm_baby"));
        assertThat("cow carries a distinct baby mesh", coat(defs, "minecraft:cow", "temperate").axes().babyModel().isPresent(), is(true));
        // 2) non-variant entity sources its baby texture from the age.baby.texture (isBaby) binding
        assertThat(defs.get("minecraft:sheep").axes().stateTextures().get("baby"), is("sheep/sheep_baby"));
        // 3) enum-variant fallback to the <adult>_baby naming convention; the base row is the default coat
        assertThat(defs.get("minecraft:axolotl").axes().stateTextures().get("baby"), is("axolotl/axolotl_lucy_baby"));
    }

    @Test
    @DisplayName("dyed-collar texture is carried for wolf + cat, absent elsewhere")
    void collarPresence() {
        // The collar renders only when a collar colour is supplied (the truthful collar_color gate); the
        // load contract pins the collar texture presence that gate resolves against. A bare wolf / cat
        // with no collar colour therefore renders no collar band.
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));
        assertThat(coat(defs, "minecraft:wolf", "pale").layers().collar(), equalTo(Optional.of("wolf/wolf_collar")));
        assertThat("every wolf variant shares the family collar",
            coat(defs, "minecraft:wolf", "ashen").layers().collar(), equalTo(Optional.of("wolf/wolf_collar")));
        assertThat(coat(defs, "minecraft:cat", "black").layers().collar(), equalTo(Optional.of("cat/cat_collar")));
        assertThat("a non-collar entity has none",
            defs.get("minecraft:cow").layers().collar().isPresent(), is(false));
    }

    @Test
    @DisplayName("option-encoded variant family loads one base row + a coat map the resolver fold selects")
    void variantOptionEncoding() {
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));
        Entity cow = defs.get("minecraft:cow");
        assertThat("one base row, no coat pseudo-ids", cow != null && defs.get("minecraft:cow_cold") == null, is(true));
        assertThat("the coat map carries every option", cow.axes().variants().keySet(), hasItems("cold", "temperate", "warm"));

        // The base IS the default (temperate) coat; the resolver fold swaps it to the selected coat.
        // cow_cold uses the horned coldcow mesh + cold texture, so selecting it changes both.
        assertThat("the base row is the default coat", cow.textureRef(), is(cow.axes().variants().get("temperate").textureRef()));
        Entity resolvedCold = cow.resolve(EntityAppearance.builder().variant(Optional.of("cold")).build());
        assertThat("selecting cold swaps to the cold coat texture", resolvedCold.textureRef(), is(cow.axes().variants().get("cold").textureRef()));
        assertThat("the cold coat differs from the default", resolvedCold.textureRef(), not(cow.textureRef()));
        assertThat("selecting cold swaps to the cold coat mesh", resolvedCold.model(), sameInstance(cow.axes().variants().get("cold").model()));

        // Canvas-group membership is baked onto Entity.members: an option-encoded variant model with no
        // cross-entity group is a singleton (empty members); a genuine group_of group carries the
        // self-inclusive member list identically on every member.
        assertThat("an option-encoded variant model with no cross-group carries no members", cow.members(), is(empty()));
        assertThat("a plain singleton entity carries no members", defs.get("minecraft:sheep").members(), is(empty()));
        assertThat("a group_of group is self-inclusive on every member",
            defs.get("minecraft:camel").members(), containsInAnyOrder("minecraft:camel", "minecraft:camel_husk"));
        assertThat("the group member list is identical on every member",
            defs.get("minecraft:camel_husk").members(), equalTo(defs.get("minecraft:camel").members()));
    }

    @Test
    @DisplayName("humanoid armor_type is consumed off the layers armor row")
    void humanoidArmorFromLayersRow() {
        // armor_type lives under `layers`: the reader classifies humanoid off the layers armor row
        // (absence IS none). Skeleton/zombie are humanoid; cow/sheep are none.
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));
        assertThat("skeleton is humanoid-armored", defs.get("minecraft:skeleton").humanoidArmor(), is(true));
        assertThat("zombie is humanoid-armored", defs.get("minecraft:zombie").humanoidArmor(), is(true));
        assertThat("the derived accessor reads the layers row", defs.get("minecraft:zombie").layers().humanoidArmor(), is(true));
        assertThat("cow is not humanoid-armored", defs.get("minecraft:cow").humanoidArmor(), is(false));
        assertThat("sheep is not humanoid-armored", defs.get("minecraft:sheep").humanoidArmor(), is(false));
    }

    /** The option-encoded coat sub-definition for a variant family's option. */
    private static Entity coat(ConcurrentMap<String, Entity> defs, String familyId, String option) {
        return defs.get(familyId).axes().variants().get(option);
    }

    @Test
    @DisplayName("a base-mesh-inheriting grow-less overlay is auto-skipped from canvas bounds")
    void depthClearanceOnGeometryInheritance() {
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));
        // enderman eyes re-submit the base mesh (same geometry coordinate) with no tint - our auto-emitted
        // depth-clearance inflate wins the coplanar tie, and the overlay is excluded from canvas bounds
        // because the base already covers its silhouette. Keyed on the overlay inheriting the base mesh,
        // not on ref-equality of the model object.
        OverlayLayer eyes = defs.get("minecraft:enderman").overlays().getFirst();
        assertThat("emissive eyes overlay", eyes.emissive(), is(true));
        assertThat("base-mesh-inheriting grow-less overlay skips bounds", eyes.skipBounds(), is(true));

        // The sheep wool layer uses a DISTINCT geometry (SheepFurModel) and carries no depth-clearance, so
        // it contributes to bounds; the same-mesh undercoat (SheepModel) is skipped.
        List<OverlayLayer> sheep = defs.get("minecraft:sheep").overlays();
        assertThat("sheep declares undercoat + wool overlays", sheep.size(), greaterThan(1));
        assertThat("same-mesh wool undercoat skips bounds", sheep.get(0).skipBounds(), is(true));
        assertThat("distinct-mesh wool layer contributes to bounds", sheep.get(1).skipBounds(), is(false));
    }
}
