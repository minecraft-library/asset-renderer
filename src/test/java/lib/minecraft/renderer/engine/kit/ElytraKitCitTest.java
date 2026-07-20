package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.pack.rule.CitResult;
import lib.minecraft.renderer.asset.pack.rule.GlintPolicy;
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.option.spec.ArmorMaterial;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

/**
 * Verifies the {@link ElytraKit} pack-rule (CIT) {@code type=elytra} consumer: a hit override
 * retextures the wings through {@code textureFor("layer0")} in preference to the equipment-model
 * texture, while {@link CitResult#NONE} (and the empty-item default) leaves the wings on the
 * {@code equipment/elytra.json} texture - the byte-neutral proof for the dormant seam.
 */
class ElytraKitCitTest {

    private static final @NotNull ResourceId OVERRIDE = new ResourceId("minecraft", "cit/custom_wings");
    private static final @NotNull String MODEL_WING = "minecraft:entity/equipment/wings/elytra";

    @Test
    @DisplayName("a matching type=elytra override retextures the wings via textureFor(layer0)")
    void overrideRetexturesWings() {
        CitResult hit = new CitResult(Optional.of(OVERRIDE), Concurrent.newMap(), Optional.empty(), GlintPolicy.DEFAULT);
        RecordingContext ctx = new RecordingContext(hit);
        buildEntityWings(ctx, Optional.of(ItemContext.ofItem("minecraft:elytra")));

        assertThat(ctx.resolved, contains(OVERRIDE.id()));
    }

    @Test
    @DisplayName("a NONE override leaves the wings on the equipment-model elytra texture")
    void noneUsesModelTexture() {
        RecordingContext ctx = new RecordingContext(CitResult.NONE);
        buildEntityWings(ctx, Optional.of(ItemContext.ofItem("minecraft:elytra")));

        assertThat(ctx.resolved, contains(MODEL_WING));
    }

    @Test
    @DisplayName("the empty-item default never consults the override and uses the model texture")
    void emptyItemUsesModelTexture() {
        RecordingContext ctx = new RecordingContext(CitResult.NONE);
        buildEntityWings(ctx, Optional.empty());

        assertThat(ctx.resolved, contains(MODEL_WING));
        assertThat("the override seam is not consulted without an item", ctx.overrideConsulted, is(false));
    }

    private static void buildEntityWings(@NotNull RecordingContext ctx, @NotNull Optional<ItemContext> item) {
        ElytraKit.buildWings3D(new Textures(ctx), false, null, Vector3f.ZERO, 1f, 1f, item, 0);
    }

    /**
     * A minimal context serving one flat elytra wing layer, a fixed CIT override, and recording each
     * resolved texture id so the resolution order is observable.
     */
    private static final class RecordingContext implements RendererContext {

        private final @NotNull List<String> resolved = new ArrayList<>();
        private final @NotNull CitResult cit;
        private boolean overrideConsulted = false;

        private RecordingContext(@NotNull CitResult cit) {
            this.cit = cit;
        }

        @Override
        public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
            this.resolved.add(textureId);
            return Optional.of(PixelBuffer.create(64, 32));
        }

        @Override
        public @NotNull List<EquipmentModel.Layer> resolveEquipmentLayers(@NotNull ResourceId assetId, @NotNull LayerType layerType) {
            return List.of(new EquipmentModel.Layer(new ResourceId("minecraft", "elytra"), Optional.empty(), true));
        }

        @Override
        public @NotNull CitResult resolveArmorTextureOverride(
            @NotNull ArmorMaterial material, @NotNull LayerType layerType, @NotNull ItemContext item) {
            this.overrideConsulted = true;
            return this.cit;
        }

        @Override
        public @NotNull Optional<Block> findBlock(@NotNull String id) {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<ColorMap> findColorMap(ColorMap.@NotNull Type type) {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Entity> findEntity(@NotNull String id) {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Item> findItem(@NotNull String id) {
            return Optional.empty();
        }

    }

}
