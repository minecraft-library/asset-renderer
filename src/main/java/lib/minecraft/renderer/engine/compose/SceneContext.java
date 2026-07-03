package lib.minecraft.renderer.engine.compose;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

/**
 * Immutable per-render scene state shared by every {@link GeometryLayer} in a 3D render.
 * <p>
 * Computed once by the renderer (canvas fit, centring anchor, scale, resolved base texture, textures)
 * and read by each layer, replacing the positional parameter threading that previously fanned these
 * values into every geometry-build call.
 *
 * @param baseTexture resolved base entity/model texture the layers sample from
 * @param modelAnchor model-space point the rasterizer maps to canvas centre
 * @param ndcScale normalized-device scale from the auto-fit window
 * @param modelScale per-subject render scale (for entities, the vanilla renderer-scale combined with
 *        state-scale)
 * @param textures texture-resolution service the layers sample overlay / armor textures through
 * @param context renderer context for overlay-texture and block lookups
 */
public record SceneContext(
    @NotNull PixelBuffer baseTexture,
    @NotNull Vector3f modelAnchor,
    float ndcScale,
    float modelScale,
    @NotNull Textures textures,
    @NotNull RendererContext context
) {}
