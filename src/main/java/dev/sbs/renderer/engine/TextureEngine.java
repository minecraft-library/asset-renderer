package dev.sbs.renderer.engine;

import dev.sbs.renderer.biome.Biome;
import dev.sbs.renderer.biome.BiomeTintTarget;
import dev.sbs.renderer.draw.AnimationKit;
import dev.sbs.renderer.draw.BlendMode;
import dev.sbs.renderer.draw.ColorKit;
import dev.sbs.renderer.exception.RendererException;
import dev.sbs.renderer.model.ColorMap;
import dev.sbs.renderer.model.asset.AnimationData;
import dev.simplified.image.PixelBuffer;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Baseline texture-aware engine. Every higher-level engine ({@link RasterEngine},
 * {@link ModelEngine}) extends this class and inherits pack resolution, biome tint sampling, and
 * colour overlay helpers.
 * <p>
 * The engine itself is stateless beyond its {@link RendererContext}. All methods are idempotent
 * and thread-safe provided the underlying context is too.
 */
@Getter
public class TextureEngine implements RenderEngine {

    private final @NotNull RendererContext context;

    /**
     * Constructs a texture engine using the given ambient context for pack, colormap, and
     * repository lookups.
     *
     * @param context the ambient renderer context
     */
    public TextureEngine(@NotNull RendererContext context) {
        this.context = context;
    }

    /**
     * Resolves a texture identifier through the active pack stack, throwing if no pack provides it.
     *
     * @param textureId the namespaced texture identifier
     * @return the decoded texture
     * @throws RendererException if no pack provides the texture
     */
    public @NotNull PixelBuffer resolveTexture(@NotNull String textureId) {
        return this.context.resolveTexture(textureId)
            .orElseThrow(() -> new RendererException("No texture registered for id '%s'", textureId));
    }

    /**
     * Resolves a texture identifier, returning empty instead of throwing when the pack stack has
     * no match. Useful for optional overlays where the caller wants a graceful fallback.
     *
     * @param textureId the namespaced texture identifier
     * @return the decoded texture, or empty if unknown
     */
    public @NotNull Optional<PixelBuffer> tryResolveTexture(@NotNull String textureId) {
        return this.context.resolveTexture(textureId);
    }

    /**
     * Returns the parsed {@code .mcmeta} animation sidecar for the given texture, if any. Wraps
     * {@link RendererContext#animationFor(String)}.
     *
     * @param textureId the namespaced texture identifier
     * @return the animation metadata, or empty when the texture has no sidecar
     */
    public @NotNull Optional<AnimationData> animationFor(@NotNull String textureId) {
        return this.context.animationFor(textureId);
    }

    /**
     * Resolves a texture and returns the specific animation frame that should be displayed at
     * the given tick. For textures without an {@code .mcmeta} sidecar the source buffer is
     * returned unchanged; for animated textures {@link AnimationKit#sampleFrame} extracts the
     * correct strip frame, blending adjacent frames when {@link AnimationData#isInterpolate()}
     * is set.
     *
     * @param textureId the namespaced texture identifier
     * @param tick the current animation tick (free-running, signed)
     * @return the frame to render at this tick
     * @throws RendererException when no pack provides the texture
     */
    public @NotNull PixelBuffer resolveTextureAtTick(@NotNull String textureId, int tick) {
        PixelBuffer strip = resolveTexture(textureId);
        Optional<AnimationData> animation = animationFor(textureId);
        if (animation.isEmpty()) return strip;
        return AnimationKit.sampleFrame(strip, animation.get(), tick);
    }

    /**
     * Returns {@code true} when the given texture has a parsed animation sidecar. Convenience
     * wrapper over {@link #animationFor(String)} for callers that only need to branch on
     * animated-vs-static without inspecting the metadata.
     *
     * @param textureId the namespaced texture identifier
     * @return whether the texture has an {@code .mcmeta} sidecar registered in the context
     */
    public boolean isAnimated(@NotNull String textureId) {
        return this.context.animationFor(textureId).isPresent();
    }

    /**
     * Samples the biome tint for the given target using the specified biome's temperature,
     * downfall, and optional colour overrides.
     * <p>
     * Priority order:
     * <ol>
     * <li>{@link BiomeTintTarget#NONE} returns opaque white - no tint applied.</li>
     * <li>{@link BiomeTintTarget#CONSTANT} defers to the block DTO's {@code tintConstant} and
     * should not be routed through this method.</li>
     * <li>The biome's matching hardcoded override (badlands, cherry grove, etc.).</li>
     * <li>A sample from the corresponding {@link ColorMap} at {@code (temperature, downfall)}.</li>
     * </ol>
     * The result is post-processed by the biome's {@link Biome.GrassColorModifier}.
     *
     * @param target the tint target
     * @param biome the biome context
     * @return the sampled ARGB colour
     */
    public int sampleBiomeTint(@NotNull BiomeTintTarget target, @NotNull Biome biome) {
        if (target == BiomeTintTarget.NONE || target == BiomeTintTarget.CONSTANT)
            return ColorKit.WHITE;

        Optional<Integer> override = switch (target) {
            case GRASS -> biome.grassColorOverride();
            case FOLIAGE -> biome.foliageColorOverride();
            case DRY_FOLIAGE -> biome.dryFoliageColorOverride();
            default -> Optional.empty();
        };

        if (override.isPresent())
            return applyModifier(override.get(), biome.grassColorModifier(), target);

        ColorMap.Type type = switch (target) {
            case GRASS -> ColorMap.Type.GRASS;
            case FOLIAGE -> ColorMap.Type.FOLIAGE;
            case DRY_FOLIAGE -> ColorMap.Type.DRY_FOLIAGE;
            default -> null;
        };
        if (type == null) return ColorKit.WHITE;

        Optional<ColorMap> map = this.context.colorMap(type);
        if (map.isEmpty()) return ColorKit.WHITE;

        int sampled = ColorKit.sampleColormap(unpackColorMap(map.get()), biome.temperature(), biome.downfall());
        return applyModifier(sampled, biome.grassColorModifier(), target);
    }

    /**
     * Composites an overlay texture on top of a base texture after tinting the overlay with the
     * given ARGB colour. Used by the item renderer for leather armour, potions, spawn eggs, and
     * firework stars.
     *
     * @param base the base texture
     * @param overlay the overlay texture
     * @param argbTint the tint applied to the overlay before compositing
     * @param mode the blend mode for the composite step
     * @return the composited pixel buffer
     */
    public @NotNull PixelBuffer applyColorOverlay(
        @NotNull PixelBuffer base,
        @NotNull PixelBuffer overlay,
        int argbTint,
        @NotNull BlendMode mode
    ) {
        PixelBuffer tinted = ColorKit.tint(overlay, argbTint);
        int w = Math.min(base.getWidth(), tinted.getWidth());
        int h = Math.min(base.getHeight(), tinted.getHeight());

        int[] result = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dst = base.getPixel(x, y);
                int src = tinted.getPixel(x, y);
                result[y * w + x] = ColorKit.blend(src, dst, mode);
            }
        }
        return PixelBuffer.of(result, w, h);
    }

    private int applyModifier(int argb, @NotNull Biome.GrassColorModifier modifier, @NotNull BiomeTintTarget target) {
        // Vanilla only runs the grass colour modifier on the grass tint - foliage and dry foliage
        // pass through untouched. See {@code Biome.getGrassColor} vs {@code Biome.getFoliageColor}
        // in the MC 26.1 client source: only the former invokes {@code grassColorModifier.modifyColor}.
        if (target != BiomeTintTarget.GRASS) return argb;

        return switch (modifier) {
            case NONE -> argb;
            case DARK_FOREST -> {
                // Verified against MC 26.1 deobfuscated client source:
                // net.minecraft.world.level.biome.BiomeSpecialEffects$GrassColorModifier$2.modifyColor
                // which computes ARGB.opaque(((baseColor & 0xFEFEFE) + 0x28340A) >> 1).
                // Applied channel-by-channel: the low bit is masked off, the dark green offset
                // (0x28/0x34/0x0A per channel) is added, and the sum is halved. Vanilla forces the
                // result to be opaque, which we mirror with a hardcoded 0xFF alpha.
                int r = (((argb >>> 16) & 0xFE) + 0x28) >> 1;
                int g = (((argb >>> 8) & 0xFE) + 0x34) >> 1;
                int b = ((argb & 0xFE) + 0x0A) >> 1;
                yield ColorKit.pack(0xFF, r & 0xFF, g & 0xFF, b & 0xFF);
            }
            case SWAMP -> {
                // Verified against MC 26.1 deobfuscated client source:
                // net.minecraft.world.level.biome.BiomeSpecialEffects$GrassColorModifier$3.modifyColor
                // samples Biome.BIOME_INFO_NOISE at (temperature * 0.0225, downfall * 0.0225) and
                // returns 0xFF4C763C when the noise is below -0.1, else 0xFF6A7039. The Perlin-noise
                // cold variant depends on world coordinates that are absent in icon rendering, so we
                // always return the warm swamp colour. Callers that want the cold variant can
                // override via {@link Biome.Builder#grassColorOverride(Optional)} with
                // {@link Biome#SWAMP_GRASS_COLD}.
                yield Biome.SWAMP_GRASS_WARM;
            }
        };
    }

    /**
     * Unpacks the row-major ARGB bytes from a {@link ColorMap} entity into an {@code int[]}
     * colormap suitable for {@link ColorKit#sampleColormap(int[], float, float)}.
     */
    private int @NotNull [] unpackColorMap(@NotNull ColorMap map) {
        byte[] bytes = map.getPixels();
        int[] pixels = new int[bytes.length / 4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.asIntBuffer().get(pixels);
        return pixels;
    }

}
