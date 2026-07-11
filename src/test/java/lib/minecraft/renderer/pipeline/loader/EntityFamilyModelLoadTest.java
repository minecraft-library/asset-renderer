package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader.EntityDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;

/**
 * Pins the option-axis data that {@link EntityModelLoader#load()} surfaces from the normalized
 * family-form {@code entity_models.json} (flattened by {@link EntityFamilyFlattener}): the wolf
 * state textures, the per-variant / non-variant / enum-convention baby textures, the dyed-collar
 * textures, and the baby mesh. Also covers {@code withoutBlockOverlays}.
 */
@DisplayName("EntityModelLoader family-form load")
class EntityFamilyModelLoadTest {

    @Test
    @DisplayName("load() surfaces a non-empty entity set")
    void loadsEntities() {
        assertThat(EntityModelLoader.load().size(), greaterThan(0));
    }

    @Test
    @DisplayName("wolf carries wild/tame/angry state textures")
    void wolfStateTextures() {
        ConcurrentMap<String, EntityDefinition> defs = EntityModelLoader.load();

        EntityDefinition pale = variant(defs, "minecraft:wolf", "pale");
        assertThat(pale.axes().stateTextures().keySet(), hasItems("wild", "tame", "angry"));
        assertThat(pale.axes().stateTextures().get("wild"), is("wolf/wolf"));
        assertThat(pale.axes().stateTextures().get("tame"), is("wolf/wolf_tame"));
        assertThat("wild state equals the default texture_ref",
            Optional.of(pale.axes().stateTextures().get("wild")), equalTo(pale.textureRef()));
        assertThat(variant(defs, "minecraft:wolf", "ashen").axes().stateTextures().get("tame"), is("wolf/wolf_ashen_tame"));
        assertThat("a single-asset variant has no tame/angry behavioural states",
            variant(defs, "minecraft:cow", "temperate").axes().stateTextures().containsKey("tame"), is(false));
    }

    @Test
    @DisplayName("baby texture is sourced per-variant / from the isBaby binding / via the <adult>_baby convention")
    void babyTextures() {
        ConcurrentMap<String, EntityDefinition> defs = EntityModelLoader.load();

        assertThat("variant table baby_asset_id",
            variant(defs, "minecraft:cow", "temperate").axes().stateTextures().get("baby"), is("cow/cow_temperate_baby"));
        assertThat(variant(defs, "minecraft:pig", "temperate").axes().stateTextures().get("baby"), is("pig/pig_temperate_baby"));
        assertThat("the baby variant differs from the base texture",
            variant(defs, "minecraft:cow", "warm").axes().stateTextures().get("baby"), is("cow/cow_warm_baby"));
        assertThat("non-variant entity sources its baby texture from the isBaby binding",
            defs.get("minecraft:sheep").axes().stateTextures().get("baby"), is("sheep/sheep_baby"));
        // axolotl carries an enum variant axis: the default coat is the base row "minecraft:axolotl"
        // (option-encoded, native) or the "minecraft:axolotl_lucy" default pseudo-id (bridge output); both
        // carry the same <adult>_baby fallback texture.
        assertThat("enum-variant entity falls back to the <adult>_baby naming convention",
            variant(defs, "minecraft:axolotl", "lucy").axes().stateTextures().get("baby"), is("axolotl/axolotl_lucy_baby"));
        assertThat("cow has a distinct baby mesh", variant(defs, "minecraft:cow", "temperate").axes().babyModel().isPresent(), is(true));
    }

    @Test
    @DisplayName("dyed collar textures are carried for wolf + cat, absent elsewhere")
    void collarTextures() {
        ConcurrentMap<String, EntityDefinition> defs = EntityModelLoader.load();

        assertThat(variant(defs, "minecraft:wolf", "pale").layers().collar(), equalTo(Optional.of("wolf/wolf_collar")));
        assertThat("every wolf variant shares the family collar",
            variant(defs, "minecraft:wolf", "ashen").layers().collar(), equalTo(Optional.of("wolf/wolf_collar")));
        assertThat(variant(defs, "minecraft:cat", "black").layers().collar(), equalTo(Optional.of("cat/cat_collar")));
        assertThat("a non-collar entity has none",
            variant(defs, "minecraft:cow", "temperate").layers().collar().isPresent(), is(false));
    }

    /**
     * A variant family's coat definition, resolved flag-adaptively: the id-encoded pseudo-id
     * ({@code minecraft:cow_temperate}) under the bridge, else the option-encoded base row's coat
     * sub-definition ({@code minecraft:cow} -&gt; {@code axes.variants["temperate"]}) natively. Lets this
     * load()-fork test pin the same coat data in both flag modes.
     */
    private static EntityDefinition variant(ConcurrentMap<String, EntityDefinition> defs, String familyId, String option) {
        EntityDefinition pseudoId = defs.get(familyId + "_" + option);
        if (pseudoId != null) return pseudoId;
        EntityDefinition base = defs.get(familyId);
        return base == null ? null : base.axes().variants().get(option);
    }

    @Test
    @DisplayName("withoutBlockOverlays drops block overlays and preserves every other field")
    void withoutBlockOverlaysStripsOnlyBlockOverlays() {
        EntityDefinition snowGolem = EntityModelLoader.load().get("minecraft:snow_golem");
        assertThat("snow_golem has block overlays to drop", snowGolem.blockOverlays().isEmpty(), is(false));

        EntityDefinition stripped = snowGolem.withoutBlockOverlays();
        assertThat(stripped.blockOverlays().isEmpty(), is(true));
        assertThat(stripped.model(), is(snowGolem.model()));
        assertThat(stripped.textureRef(), equalTo(snowGolem.textureRef()));
        assertThat(stripped.overlays(), equalTo(snowGolem.overlays()));
        assertThat(stripped.axes().stateTextures(), equalTo(snowGolem.axes().stateTextures()));
    }
}
