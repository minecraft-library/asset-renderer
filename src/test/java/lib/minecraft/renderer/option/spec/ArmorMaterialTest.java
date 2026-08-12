package lib.minecraft.renderer.option.spec;

import lib.minecraft.renderer.asset.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The vanilla equipment-asset stem {@link ArmorMaterial#assetId()} answers with, one per material -
 * the id an equipment model is looked up under, which is not always the constant's own name.
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

}
