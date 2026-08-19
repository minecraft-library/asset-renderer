package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.pack.rule.CitResult;
import lib.minecraft.renderer.engine.RendererContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The equipment-model texture composite - the single place an equipment asset id plus a render layer
 * becomes one drawable buffer, shared by worn humanoid armor and mob equipment (saddles, body armor,
 * llama decor).
 *
 * <p>An asset declares its layers back-to-front per layer type: one flat layer for a plain material,
 * or a dyed base plus an undyed detail pass for leather-style armor. Each layer resolves to a texture
 * under the layer type's subdir and, when the layer is {@link EquipmentModel.Dyeable dyeable}, is
 * tinted by the wearer's dye or the layer's own undyed fallback colour. A dyeable layer with no
 * fallback resolves to colour 0 and is skipped, which is how a render-only-when-dyed pass (the
 * armadillo-scute overlay) stays invisible on an undyed wearer.
 */
@UtilityClass
public class EquipmentKit {

    /**
     * Resolves and composites one equipment asset's layers for a render layer.
     *
     * <p>A single flat layer with no pack-rule override returns its resolved buffer directly rather
     * than blitting onto a fresh one, so the common case does not depend on the composite path.
     *
     * @param context the texture context for pack-aware texture resolution
     * @param assetId the equipment asset id ({@code minecraft:iron}, {@code minecraft:saddle})
     * @param layerType the render layer whose subdir the layer textures sit under
     * @param dyeColor the wearer's dye, or empty to take each dyeable layer's own fallback colour
     * @param cit the pack-rule override replacing layer textures ({@code layer0} the base,
     *     {@code layerN} the overlays); {@link CitResult#NONE} leaves every layer on the model
     * @param tick the animation tick to sample each layer texture at, or empty to take the texture
     *     unsampled
     * @return the composited texture, or empty when the asset ships no layers for this render layer
     *     or none of them resolve
     */
    public static @NotNull Optional<PixelBuffer> composite(
        @NotNull RendererContext context,
        @NotNull ResourceId assetId,
        @NotNull LayerType layerType,
        @NotNull Optional<Integer> dyeColor,
        @NotNull CitResult cit,
        @NotNull OptionalInt tick
    ) {
        List<EquipmentModel.Layer> layers = context.resolveEquipmentLayers(assetId, layerType);
        if (layers.isEmpty() && cit == CitResult.NONE) return Optional.empty();

        // A single flat (non-dyeable) layer with no pack-rule override returns its resolved buffer
        // directly - the exact single-texture path, so byte-identity does not rest on a blit onto a fresh buffer.
        if (cit == CitResult.NONE && layers.size() == 1 && layers.getFirst().dyeable().isEmpty())
            return resolve(context, layers.getFirst().textureLocation(layerType).id(), tick);

        PixelBuffer combined = null;
        for (int i = 0; i < layers.size(); i++) {
            EquipmentModel.Layer layer = layers.get(i);
            // A matching CIT rule replaces this layer's texture (layer0 the base, layerN the overlays);
            // absent an override the equipment model's own path resolves. NONE returns empty for every
            // layer, so the fallback is byte-identical to the model-only path.
            String textureId = cit.textureFor("layer" + i)
                .map(ResourceId::id)
                .orElseGet(() -> layer.textureLocation(layerType).id());
            Optional<PixelBuffer> texture = resolve(context, textureId, tick);
            if (texture.isEmpty()) continue;

            PixelBuffer painted;
            if (layer.dyeable().isPresent()) {
                int color = dyeColor.orElseGet(() -> layer.dyeable().get().colorWhenUndyed().orElse(0));
                if (color == 0) continue;   // dyeable layer with no undyed fallback: skip when undyed
                painted = ColorMath.tint(texture.get(), color);
            } else {
                painted = texture.get();
            }

            if (combined == null) combined = PixelBuffer.create(painted.width(), painted.height());
            combined.blit(painted, 0, 0);
        }
        return Optional.ofNullable(combined);
    }

    /** Resolves one layer texture, sampling its animation frame when the caller supplies a tick. */
    private static @NotNull Optional<PixelBuffer> resolve(
        @NotNull RendererContext context,
        @NotNull String textureId,
        @NotNull OptionalInt tick
    ) {
        if (tick.isEmpty()) return context.resolveTexture(textureId);
        return context.resolveTextureAtTick(textureId, tick.getAsInt());
    }

}
