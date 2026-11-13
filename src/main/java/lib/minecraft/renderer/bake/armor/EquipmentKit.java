package lib.minecraft.renderer.bake.armor;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.bake.mesh.EntityGeometryKit;
import lib.minecraft.renderer.content.index.CitResult;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.draw.SampledTexture;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.equipment.LayerType;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The equipment-model layer resolution - the single place an equipment asset id plus a render layer
 * becomes the passes that draw it, shared by worn humanoid armor and mob equipment (saddles, body
 * armor, llama decor).
 *
 * <p>An asset declares its layers back-to-front per layer type: one flat layer for a plain material,
 * or a dyed base plus an undyed detail pass for leather-style armor. Each layer resolves to a texture
 * under the layer type's subdir and, when the layer is {@link EquipmentModel.Dyeable dyeable}, carries
 * the wearer's dye or the layer's own undyed fallback colour as its tint. A dyeable layer with no
 * fallback resolves to colour 0 and is skipped, which is how a render-only-when-dyed pass (the
 * armadillo-scute overlay) stays invisible on an undyed wearer.
 *
 * <p>Each layer is answered as the client draws it - its own pass over the same geometry, its texture
 * read through its own sampler and its colour the vertex colour, which the raster multiplies with the
 * light in one rounding. Nothing is pasted or tinted into a picture first, so a dyed layer's colour is
 * rounded once, and every layer keeps the sampler its sidecar selects.
 *
 * <p>Every layer texture is read through the context's
 * {@link RendererContext#withMissingTexture() missing-texture wrapper}, so a texture a layer names that
 * no pack supplies, or that cannot be read, is the checkerboard - never a layer quietly left out.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class EquipmentKit {

    /**
     * One pass an equipment asset draws - a layer's texture and the vertex colour it is drawn with.
     *
     * @param texture the layer's texture, with the sampler it is read through
     * @param tint the opaque ARGB vertex colour the layer is drawn with, {@link ColorMath#WHITE} for an
     *     undyed layer
     */
    public record Layer(@NotNull SampledTexture texture, int tint) {}

    /**
     * Resolves one equipment asset's layers for a render layer, in the order they draw.
     *
     * @param context the texture context for pack-aware texture resolution
     * @param assetId the equipment asset id ({@code minecraft:iron}, {@code minecraft:saddle})
     * @param layerType the render layer whose subdir the layer textures sit under
     * @param dyeColor the wearer's dye, or empty to take each dyeable layer's own fallback colour
     * @param cit the pack-rule override replacing layer textures ({@code layer0} the base,
     *     {@code layerN} the overlays); {@link CitResult#NONE} leaves every layer on the model
     * @param tick the animation tick to sample each layer texture at, or empty to take the texture
     *     unsampled
     * @return the layers that draw, back to front - empty when the asset declares no layer that draws
     *     for this render layer, none at all or only dyed-only passes on an undyed wearer
     */
    public static @NotNull List<Layer> layers(
        @NotNull RendererContext context,
        @NotNull ResourceId assetId,
        @NotNull LayerType layerType,
        @NotNull Optional<Integer> dyeColor,
        @NotNull CitResult cit,
        @NotNull OptionalInt tick
    ) {
        List<EquipmentModel.Layer> declared = context.resolveEquipmentLayers(assetId, layerType);
        List<Layer> layers = new ArrayList<>(declared.size());

        for (int i = 0; i < declared.size(); i++) {
            EquipmentModel.Layer layer = declared.get(i);
            // A dyeable layer draws in the wearer's dye or its own undyed colour; one with neither draws
            // nothing, and its texture is never read, so it never reports a texture it would not draw.
            int tint = ColorMath.WHITE;
            if (layer.dyeable().isPresent()) {
                int color = dyeColor.orElseGet(() -> layer.dyeable().get().colorWhenUndyed().orElse(0));
                if (color == 0) continue;
                tint = 0xFF000000 | color;
            }

            // A matching CIT rule replaces this layer's texture (layer0 the base, layerN the overlays);
            // absent an override the equipment model's own path resolves.
            String textureId = cit.textureFor("layer" + i)
                .map(ResourceId::id)
                .orElseGet(() -> layer.textureLocation(layerType).id());
            layers.add(new Layer(resolve(context, textureId, tick), tint));
        }

        return layers;
    }

    /**
     * Measures the alpha-tight screen-space bounds the layers draw over one mesh - each layer's
     * {@link EntityGeometryKit#opaqueScreenBounds opaque bounds}, unioned. A layer clear everywhere on
     * the mesh draws nothing, so it bounds nothing.
     *
     * @param layers the layers drawn over the mesh
     * @param mesh the mesh the layers are drawn over
     * @param screenTransform the model-to-screen transform the bounds are measured through
     * @param modelScale the per-entity scale applied to every cube vertex before the screen transform
     * @return the union of the layers' bounds, empty where no layer holds an opaque texel on the mesh
     */
    public static @NotNull Optional<Box> screenBounds(
        @NotNull List<Layer> layers, @NotNull EntityMesh mesh, @NotNull Matrix4f screenTransform, float modelScale
    ) {
        Box union = null;
        for (Layer layer : layers) {
            Optional<Box> bounds = EntityGeometryKit.opaqueScreenBounds(mesh, screenTransform, modelScale, layer.texture());
            if (bounds.isPresent()) union = union == null ? bounds.get() : union.union(bounds.get());
        }
        return Optional.ofNullable(union);
    }

    /**
     * Reads one layer texture as the client reads an equipment layer, a plain texture, through the
     * context's missing-texture wrapper, sampling its animation frame when the caller supplies a tick.
     *
     * @param context the texture context the layer texture is read through
     * @param textureId the layer texture's namespaced id
     * @param tick the animation tick to sample at, or empty to take the texture unsampled
     * @return the layer's texture and the sampler it is read through, the checkerboard where no pack
     *     supplies the texture or it cannot be read
     */
    private static @NotNull SampledTexture resolve(
        @NotNull RendererContext context,
        @NotNull String textureId,
        @NotNull OptionalInt tick
    ) {
        RendererContext textures = context.withMissingTexture();
        SampledTexture texture = textures.resolvePlainTexture(textureId).get();
        return tick.isEmpty() ? texture : Flipbook.atTick(texture, textures.findFlipbook(textureId), tick.getAsInt());
    }

}
