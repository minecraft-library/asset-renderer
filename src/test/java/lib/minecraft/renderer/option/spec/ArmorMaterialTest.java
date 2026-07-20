package lib.minecraft.renderer.option.spec;

import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.equipment.LayerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Pins {@link ArmorMaterial#assetId()} to the vanilla equipment-asset stem per material and asserts
 * that resolving that asset under a humanoid layer reproduces the pre-model hardcoded armor path
 * ({@code entity/equipment/<layer>/<stem>}) - the byte-neutral guard for routing worn armor through
 * the equipment model.
 */
@DisplayName("ArmorMaterial equipment-asset ids")
class ArmorMaterialTest {

    @Test
    @DisplayName("assetId is minecraft:<vanilla stem> for every material")
    void assetIds() {
        assertThat(ArmorMaterial.LEATHER.assetId(), is(new ResourceId("minecraft", "leather")));
        assertThat(ArmorMaterial.CHAINMAIL.assetId(), is(new ResourceId("minecraft", "chainmail")));
        assertThat(ArmorMaterial.IRON.assetId(), is(new ResourceId("minecraft", "iron")));
        assertThat(ArmorMaterial.GOLDEN.assetId(), is(new ResourceId("minecraft", "gold")));
        assertThat(ArmorMaterial.DIAMOND.assetId(), is(new ResourceId("minecraft", "diamond")));
        assertThat(ArmorMaterial.COPPER.assetId(), is(new ResourceId("minecraft", "copper")));
        assertThat(ArmorMaterial.NETHERITE.assetId(), is(new ResourceId("minecraft", "netherite")));
        assertThat(ArmorMaterial.TURTLE_SCUTE.assetId(), is(new ResourceId("minecraft", "turtle_scute")));
    }

    @Test
    @DisplayName("the asset stem resolves to the legacy hardcoded armor path under each humanoid layer")
    void reproducesLegacyPaths() {
        for (ArmorMaterial material : ArmorMaterial.values()) {
            EquipmentModel.Layer base = new EquipmentModel.Layer(material.assetId(), Optional.empty(), false);
            String stem = material.assetId().name();
            assertThat(base.textureLocation(LayerType.HUMANOID).id(),
                is("minecraft:entity/equipment/humanoid/" + stem));
            assertThat(base.textureLocation(LayerType.HUMANOID_LEGGINGS).id(),
                is("minecraft:entity/equipment/humanoid_leggings/" + stem));
        }
    }

}
