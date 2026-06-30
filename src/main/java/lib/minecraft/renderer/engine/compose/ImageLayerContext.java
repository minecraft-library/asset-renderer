package lib.minecraft.renderer.engine.compose;

import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.options.ItemOptions;
import org.jetbrains.annotations.NotNull;

/**
 * Per-render state passed to every {@link ImageLayer} in a 2D item composite stack.
 *
 * @param context renderer context for texture and override resolution
 * @param textures texture-resolution service for layer texture lookups
 * @param item resolved item definition being rendered
 * @param options caller-supplied item render options
 */
public record ImageLayerContext(
    @NotNull RendererContext context,
    @NotNull Textures textures,
    @NotNull Item item,
    @NotNull ItemOptions options
) {}
