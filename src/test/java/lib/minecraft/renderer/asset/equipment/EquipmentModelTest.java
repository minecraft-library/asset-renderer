package lib.minecraft.renderer.asset.equipment;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.EquipmentModel.Layer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link EquipmentModel}, of the texture-path derivation in
 * {@link Layer#textureLocation(LayerType)}, and of the {@link EquipmentModel#MISSING} absent-lookup
 * fallbacks.
 */
@DisplayName("EquipmentModel and Layer")
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
        ConcurrentList<Layer> humanoid = Concurrent.newUnmodifiableList(iron);
        EquipmentModel model = new EquipmentModel(Concurrent.newUnmodifiableMap(Map.of(LayerType.HUMANOID, humanoid)));
        assertThat(model.getLayers(LayerType.HUMANOID).size(), is(1));
        assertThat(model.getLayers(LayerType.WINGS).isEmpty(), is(true));
    }

    @Test
    @DisplayName("MISSING has no layers for any type")
    void missing() {
        assertThat(EquipmentModel.MISSING.getLayers(LayerType.HUMANOID).isEmpty(), is(true));
    }

}
