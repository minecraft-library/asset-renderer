package lib.minecraft.renderer.bake.armor;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.engine.camera.FitFrame;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.port.answer.CitResult;
import lib.minecraft.renderer.port.answer.GlintPolicy;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.support.RecordingContext;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of the {@link ElytraKit} pack-rule (CIT) {@code type=elytra} consumer: a hit override
 * retextures the wings through {@code textureFor("layer0")} in preference to the equipment-model
 * texture, while {@link CitResult#NONE} (and the empty-item default) leaves the wings on the
 * {@code equipment/elytra.json} texture.
 */
class ElytraKitCitTest {

    private static final @NotNull ResourceId OVERRIDE = new ResourceId("minecraft", "cit/custom_wings");
    private static final @NotNull String MODEL_WING = "minecraft:entity/equipment/wings/elytra";

    @Test
    @DisplayName("a matching type=elytra override retextures the wings via textureFor(layer0)")
    void overrideRetexturesWings() {
        CitResult hit = new CitResult(Optional.of(OVERRIDE), Concurrent.newMap(), Optional.empty(), GlintPolicy.DEFAULT);
        RecordingContext ctx = recording(hit);
        buildEntityWings(ctx, Optional.of(ItemContext.ofItem("minecraft:elytra")));

        assertThat(ctx.getResolved(), contains(OVERRIDE.id()));
    }

    @Test
    @DisplayName("a NONE override leaves the wings on the equipment-model elytra texture")
    void noneUsesModelTexture() {
        RecordingContext ctx = recording(CitResult.NONE);
        buildEntityWings(ctx, Optional.of(ItemContext.ofItem("minecraft:elytra")));

        assertThat(ctx.getResolved(), contains(MODEL_WING));
    }

    @Test
    @DisplayName("the empty-item default never consults the override and uses the model texture")
    void emptyItemUsesModelTexture() {
        RecordingContext ctx = recording(CitResult.NONE);
        buildEntityWings(ctx, Optional.empty());

        assertThat(ctx.getResolved(), contains(MODEL_WING));
        assertThat("the override seam is not consulted without an item", ctx.isArmorOverrideConsulted(), is(false));
    }

    private static void buildEntityWings(@NotNull RecordingContext ctx, @NotNull Optional<ItemContext> item) {
        ElytraKit.buildWings3D(ctx, false, Optional.empty(), FitFrame.IDENTITY, item, 0);
    }

    /**
     * A context serving one flat elytra wing layer, a fixed CIT override, and recording each resolved
     * texture id so the resolution order is observable.
     */
    private static @NotNull RecordingContext recording(@NotNull CitResult cit) {
        return RecordingContext.over(RendererContext.builder()
                .textures(id -> Optional.of(PixelBuffer.create(64, 32)))
                .build())
            .answeringEquipment(List.of(
                new EquipmentModel.Layer(new ResourceId("minecraft", "elytra"), Optional.empty(), true)))
            .answeringArmorOverride(cit);
    }

}
