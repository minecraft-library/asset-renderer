package lib.minecraft.renderer.asset.equipment;

import lib.minecraft.renderer.asset.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Exercises {@link EquipmentModel}, its {@link EquipmentModel.Layer#textureLocation(LayerType) texture
 * path derivation}, and the {@link EquipmentAssets} / {@link EquipmentModel#MISSING} absent-lookup
 * fallbacks.
 */
@DisplayName("EquipmentModel + Layer + EquipmentAssets")
class EquipmentModelTest {

    @Test
    @DisplayName("textureLocation prepends entity/equipment/<layer>/ to the bare stem")
    void textureLocation() {
        EquipmentModel.Layer iron = new EquipmentModel.Layer(new ResourceId("minecraft", "iron"), Optional.empty(), false);
        assertThat(iron.textureLocation(LayerType.HUMANOID).id(), is("minecraft:entity/equipment/humanoid/iron"));
        assertThat(iron.textureLocation(LayerType.HUMANOID_LEGGINGS).id(), is("minecraft:entity/equipment/humanoid_leggings/iron"));

        EquipmentModel.Layer overlay = new EquipmentModel.Layer(new ResourceId("minecraft", "leather_overlay"), Optional.empty(), false);
        assertThat(overlay.textureLocation(LayerType.HUMANOID).id(), is("minecraft:entity/equipment/humanoid/leather_overlay"));
    }

    @Test
    @DisplayName("getLayers returns an empty list for an undeclared layer type")
    void getLayersEmpty() {
        EquipmentModel.Layer iron = new EquipmentModel.Layer(new ResourceId("minecraft", "iron"), Optional.empty(), false);
        EquipmentModel model = new EquipmentModel(Map.of(LayerType.HUMANOID, List.of(iron)));
        assertThat(model.getLayers(LayerType.HUMANOID).size(), is(1));
        assertThat(model.getLayers(LayerType.WINGS).isEmpty(), is(true));
    }

    @Test
    @DisplayName("MISSING has no layers for any type")
    void missing() {
        assertThat(EquipmentModel.MISSING.getLayers(LayerType.HUMANOID).isEmpty(), is(true));
    }

    @Test
    @DisplayName("EquipmentAssets.get returns MISSING for an absent asset id")
    void assetsGetMissing() {
        assertThat(EquipmentAssets.EMPTY.get(new ResourceId("minecraft", "iron")), is(EquipmentModel.MISSING));
    }

}
