package lib.minecraft.renderer.pipeline.load.entity;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader.EntityDefinition;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader.OverlayLayer;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;

/**
 * v2-native load-contract successors for {@link EntityFamilyReader}, pinning the same invariants the
 * bridge-era {@code EntityFamilyFlattenerTest} / {@code EntityFamilyModelLoadTest} pin, but against the
 * native family read of {@code v2/entity_models.json} directly. Load-bearing canaries: the wolf
 * variant/state texture join, the baby three-source texture chain, the dyed-collar presence, the
 * id-encoded variant pseudo-id expansion, and the depth-clearance auto-skip on a base-mesh-inheriting
 * overlay.
 */
@DisplayName("EntityFamilyReader v2-native load")
class EntityFamilyReaderTest {

    @Test
    @DisplayName("wolf base texture ref equals the default variant option's wild texture")
    void wolfWildEqualsTextureRef() {
        ConcurrentMap<String, EntityDefinition> defs = EntityFamilyReader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));
        EntityDefinition pale = defs.get("minecraft:wolf_pale");
        assertThat(pale.axes().stateTextures().get("wild"), is("wolf/wolf"));
        assertThat("wild state equals the default texture_ref",
            Optional.of(pale.axes().stateTextures().get("wild")), equalTo(pale.textureRef()));
        assertThat(pale.axes().stateTextures().keySet(), hasItems("wild", "tame", "angry"));
        assertThat(defs.get("minecraft:wolf_ashen").axes().stateTextures().get("tame"), is("wolf/wolf_ashen_tame"));
    }

    @Test
    @DisplayName("baby texture resolves via the three-source chain (variant baby_texture / isBaby binding / <adult>_baby)")
    void babyThreeSourceChain() {
        ConcurrentMap<String, EntityDefinition> defs = EntityFamilyReader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));
        // 1) variant-table per-option baby_texture
        assertThat(defs.get("minecraft:cow_temperate").axes().stateTextures().get("baby"), is("cow/cow_temperate_baby"));
        assertThat(defs.get("minecraft:cow_warm").axes().stateTextures().get("baby"), is("cow/cow_warm_baby"));
        assertThat("cow carries a distinct baby mesh", defs.get("minecraft:cow_temperate").axes().babyModel().isPresent(), is(true));
        // 2) non-variant entity sources its baby texture from the age.baby.texture (isBaby) binding
        assertThat(defs.get("minecraft:sheep").axes().stateTextures().get("baby"), is("sheep/sheep_baby"));
        // 3) enum-variant fallback to the <adult>_baby naming convention
        EntityDefinition axolotl = defs.get("minecraft:axolotl");
        if (axolotl == null) axolotl = defs.get("minecraft:axolotl_lucy");
        assertThat(axolotl.axes().stateTextures().get("baby"), is("axolotl/axolotl_lucy_baby"));
    }

    @Test
    @DisplayName("dyed-collar texture is carried for wolf + cat, absent elsewhere")
    void collarPresence() {
        // The collar renders only when a collar colour is supplied (the truthful collar_color gate); the
        // load contract pins the collar texture presence that gate resolves against. A bare wolf / cat
        // with no collar colour therefore renders no collar band.
        ConcurrentMap<String, EntityDefinition> defs = EntityFamilyReader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));
        assertThat(defs.get("minecraft:wolf_pale").layers().collar(), equalTo(Optional.of("wolf/wolf_collar")));
        assertThat("every wolf variant shares the family collar",
            defs.get("minecraft:wolf_ashen").layers().collar(), equalTo(Optional.of("wolf/wolf_collar")));
        assertThat(defs.get("minecraft:cat_black").layers().collar(), equalTo(Optional.of("cat/cat_collar")));
        assertThat("a non-collar entity has none",
            defs.get("minecraft:cow_temperate").layers().collar().isPresent(), is(false));
    }

    @Test
    @DisplayName("id-encoded variant family expands to <id>_<opt> pseudo-ids joined by the family union")
    void variantPseudoIdExpansion() {
        ConcurrentMap<String, EntityDefinition> defs = EntityFamilyReader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));
        assertThat("base + variant rows both present as pseudo-ids",
            defs.get("minecraft:cow_temperate") != null && defs.get("minecraft:cow_cold") != null, is(true));

        Map<String, List<String>> families = EntityFamilyReader.loadFamilies(Diagnostics.root("test", Diagnostics.Output.NONE, null));
        assertThat("every variant sibling resolves to the whole family via variant_of",
            families.get("minecraft:cow_cold"), hasItems("minecraft:cow_temperate", "minecraft:cow_cold", "minecraft:cow_warm"));
        assertThat("a singleton entity returns itself",
            families.getOrDefault("minecraft:sheep", List.of()), contains("minecraft:sheep"));
    }

    @Test
    @DisplayName("a base-mesh-inheriting grow-less overlay is auto-skipped from canvas bounds")
    void depthClearanceOnGeometryInheritance() {
        ConcurrentMap<String, EntityDefinition> defs = EntityFamilyReader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));
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
