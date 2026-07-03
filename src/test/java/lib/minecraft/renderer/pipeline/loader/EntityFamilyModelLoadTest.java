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

        EntityDefinition pale = defs.get("minecraft:wolf_pale");
        assertThat(pale.stateTextures().keySet(), hasItems("wild", "tame", "angry"));
        assertThat(pale.stateTextures().get("wild"), is("wolf/wolf"));
        assertThat(pale.stateTextures().get("tame"), is("wolf/wolf_tame"));
        assertThat("wild state equals the default texture_ref",
            Optional.of(pale.stateTextures().get("wild")), equalTo(pale.textureRef()));
        assertThat(defs.get("minecraft:wolf_ashen").stateTextures().get("tame"), is("wolf/wolf_ashen_tame"));
        assertThat("a single-asset variant has no tame/angry behavioural states",
            defs.get("minecraft:cow_temperate").stateTextures().containsKey("tame"), is(false));
    }

    @Test
    @DisplayName("baby texture is sourced per-variant / from the isBaby binding / via the <adult>_baby convention")
    void babyTextures() {
        ConcurrentMap<String, EntityDefinition> defs = EntityModelLoader.load();

        assertThat("variant table baby_asset_id",
            defs.get("minecraft:cow_temperate").stateTextures().get("baby"), is("cow/cow_temperate_baby"));
        assertThat(defs.get("minecraft:pig_temperate").stateTextures().get("baby"), is("pig/pig_temperate_baby"));
        assertThat("the baby variant differs from the base texture",
            defs.get("minecraft:cow_warm").stateTextures().get("baby"), is("cow/cow_warm_baby"));
        assertThat("non-variant entity sources its baby texture from the isBaby binding",
            defs.get("minecraft:sheep").stateTextures().get("baby"), is("sheep/sheep_baby"));
        assertThat("enum-variant entity falls back to the <adult>_baby naming convention",
            defs.get("minecraft:axolotl").stateTextures().get("baby"), is("axolotl/axolotl_lucy_baby"));
        assertThat("cow has a distinct baby mesh", defs.get("minecraft:cow_temperate").babyModel().isPresent(), is(true));
    }

    @Test
    @DisplayName("dyed collar textures are carried for wolf + cat, absent elsewhere")
    void collarTextures() {
        ConcurrentMap<String, EntityDefinition> defs = EntityModelLoader.load();

        assertThat(defs.get("minecraft:wolf_pale").collarTexture(), equalTo(Optional.of("wolf/wolf_collar")));
        assertThat("every wolf variant shares the family collar",
            defs.get("minecraft:wolf_ashen").collarTexture(), equalTo(Optional.of("wolf/wolf_collar")));
        assertThat(defs.get("minecraft:cat_black").collarTexture(), equalTo(Optional.of("cat/cat_collar")));
        assertThat("a non-collar entity has none",
            defs.get("minecraft:cow_temperate").collarTexture().isPresent(), is(false));
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
        assertThat(stripped.stateTextures(), equalTo(snowGolem.stateTextures()));
    }
}
