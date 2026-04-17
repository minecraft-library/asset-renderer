package dev.sbs.renderer.engine;

import dev.sbs.renderer.exception.RendererException;
import dev.sbs.renderer.geometry.Biome;

import dev.sbs.renderer.kit.AnimationKit;
import dev.sbs.renderer.kit.GlintKit;
import dev.sbs.renderer.asset.pack.ColorMap;
import dev.sbs.renderer.asset.pack.AnimationData;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class TextureEngine implements RenderEngine {

    /**
     * Edge length of the square ARGB biome colormap (grass / foliage). Every vanilla colormap
     * ships as a 256x256 image, so sampling indexes as {@code y * COLORMAP_SIZE + x}.
     */
    private static final int COLORMAP_SIZE = 256;

    /**
     * Upper index of the colormap lookup coordinate in normalized space. Multiplying a clamped
     * {@code [0, 1]} temperature / downfall by this value maps it to a {@code [0, 255]} column
     * or row.
     */
    private static final float COLORMAP_COORD_MAX = 255f;

    /**
     * Low-bit mask applied per channel to the base ARGB before the dark-forest offset is added.
     * Matches vanilla {@code BiomeSpecialEffects$GrassColorModifier$2.modifyColor} which clears
     * the LSB of each channel before blending.
     */
    private static final int DARK_FOREST_LOW_BIT_MASK = 0xFE;

    /** Red-channel add vanilla applies to the base grass color for dark-forest biomes. */
    private static final int DARK_FOREST_RED_OFFSET = 0x28;

    /** Green-channel add for dark-forest grass modifier. */
    private static final int DARK_FOREST_GREEN_OFFSET = 0x34;

    /** Blue-channel add for dark-forest grass modifier. */
    private static final int DARK_FOREST_BLUE_OFFSET = 0x0A;

    /**
     * Vanilla's default water ARGB, used by {@link #sampleBiomeTint} when a biome carries no
     * {@link Biome#waterColorOverride()}. Matches the default value in the Minecraft 26.1 biome
     * {@code effects.water_color} field for biomes that don't override it.
     */
    private static final int DEFAULT_WATER_ARGB = 0xFF3F76E4;

    private final @NotNull RendererContext context;

    /**
     * Resolves a texture identifier through the active pack stack, throwing if no pack provides it.
     *
     * @param textureId the namespaced texture identifier
     * @return the decoded texture
     * @throws RendererException if no pack provides the texture
     */
    public @NotNull PixelBuffer resolveTexture(@NotNull String textureId) {
        return this.context.resolveTexture(textureId).orElseThrow(() -> new RendererException("No texture registered for id '%s'", textureId));
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
        return animation.map(animationData -> AnimationKit.sampleFrame(strip, animationData, tick)).orElse(strip);
    }

    /**
     * Wraps a finished buffer into an {@link ImageData}, optionally applying a scrolling glint
     * overlay animation. If {@code enchanted} is {@code false} or the active pack stack has no
     * glint texture, the buffer is returned as a single-frame static image; otherwise the
     * configured glint is composed via {@link GlintKit#apply} and the frames are emitted at
     * {@code glintOptions.framesPerSecond()}.
     * <p>
     * Callers differ only in the enchanted predicate (per-item {@code isEnchanted} vs
     * armor-slot scan) and the {@link GlintKit.GlintOptions} preset ({@code itemDefault} vs
     * {@code armorDefault} vs {@code entityItemDefault}). All other structure - pack resolution,
     * empty-texture fallback, frame-rate derivation - is identical across renderers.
     *
     * @param buffer the finished render surface
     * @param enchanted whether the item / entity is enchanted and should show a glint
     * @param glintOptions the glint preset, carrying the texture id and frame rate
     * @return a static image when no glint is applied, an animated image otherwise
     */
    public @NotNull ImageData finaliseWithGlint(
        @NotNull PixelBuffer buffer,
        boolean enchanted,
        @NotNull GlintKit.GlintOptions glintOptions
    ) {
        if (!enchanted)
            return RenderEngine.staticFrame(buffer);

        Optional<PixelBuffer> glintTexture = tryResolveTexture(glintOptions.glintTextureId());
        if (glintTexture.isEmpty())
            return RenderEngine.staticFrame(buffer);

        ConcurrentList<PixelBuffer> frames = GlintKit.apply(buffer, glintTexture.get(), glintOptions);
        int frameDelayMs = Math.max(1, Math.round(1000f / glintOptions.framesPerSecond()));
        return RenderEngine.output(frames, frameDelayMs);
    }

    /**
     * Samples the biome tint for the given target using the specified biome's temperature,
     * downfall, and optional colour overrides.
     * <p>
     * Priority order:
     * <ol>
     * <li>{@link Biome.TintTarget#NONE} returns opaque white - no tint applied.</li>
     * <li>{@link Biome.TintTarget#CONSTANT} defers to the block DTO's {@code tintConstant} and
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
    public int sampleBiomeTint(@NotNull Biome.TintTarget target, @NotNull Biome biome) {
        if (target == Biome.TintTarget.NONE || target == Biome.TintTarget.CONSTANT)
            return ColorMath.WHITE;

        // Water has no colormap in vanilla - the tint is either the per-biome override or the
        // engine-level default. Skip the colormap path entirely and skip grassColorModifier
        // (water is unaffected by the dark-forest / swamp modifiers that only apply to grass).
        if (target == Biome.TintTarget.WATER)
            return biome.waterColorOverride().orElse(DEFAULT_WATER_ARGB);

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
        if (type == null) return ColorMath.WHITE;

        Optional<ColorMap> map = this.context.colorMap(type);
        if (map.isEmpty()) return ColorMath.WHITE;

        int sampled = sampleColormap(unpackColorMap(map.get()), biome.temperature(), biome.downfall());
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
        PixelBuffer tinted = ColorMath.tint(overlay, argbTint);
        int w = Math.min(base.width(), tinted.width());
        int h = Math.min(base.height(), tinted.height());

        int[] result = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dst = base.getPixel(x, y);
                int src = tinted.getPixel(x, y);
                result[y * w + x] = ColorMath.blend(src, dst, mode);
            }
        }
        return PixelBuffer.of(result, w, h);
    }

    private int applyModifier(int argb, @NotNull Biome.GrassColorModifier modifier, @NotNull Biome.TintTarget target) {
        // Vanilla only runs the grass colour modifier on the grass tint - foliage and dry foliage
        // pass through untouched. See {@code Biome.getGrassColor} vs {@code Biome.getFoliageColor}
        // in the MC 26.1 client source: only the former invokes {@code grassColorModifier.modifyColor}.
        if (target != Biome.TintTarget.GRASS) return argb;

        return switch (modifier) {
            case NONE -> argb;
            case DARK_FOREST -> {
                // Verified against MC 26.1 deobfuscated client source:
                // net.minecraft.world.level.biome.BiomeSpecialEffects$GrassColorModifier$2.modifyColor
                // which computes ARGB.opaque(((baseColor & 0xFEFEFE) + 0x28340A) >> 1).
                // Applied channel-by-channel: the low bit is masked off, the dark green offset
                // (0x28/0x34/0x0A per channel) is added, and the sum is halved. Vanilla forces the
                // result to be opaque, which we mirror with a hardcoded 0xFF alpha.
                int r = (((argb >>> 16) & DARK_FOREST_LOW_BIT_MASK) + DARK_FOREST_RED_OFFSET) >> 1;
                int g = (((argb >>> 8) & DARK_FOREST_LOW_BIT_MASK) + DARK_FOREST_GREEN_OFFSET) >> 1;
                int b = ((argb & DARK_FOREST_LOW_BIT_MASK) + DARK_FOREST_BLUE_OFFSET) >> 1;
                yield ColorMath.pack(0xFF, r & 0xFF, g & 0xFF, b & 0xFF);
            }
            case SWAMP ->
                // Verified against MC 26.1 deobfuscated client source:
                // net.minecraft.world.level.biome.BiomeSpecialEffects$GrassColorModifier$3.modifyColor
                // samples Biome.BIOME_INFO_NOISE at (temperature * 0.0225, downfall * 0.0225) and
                // returns 0xFF4C763C when the noise is below -0.1, else 0xFF6A7039. The Perlin-noise
                // cold variant depends on world coordinates that are absent in icon rendering, so we
                // always return the warm swamp colour. Callers that want the cold variant can
                // override via {@link Biome.Builder#grassColorOverride(Optional)} with
                // {@link Biome#SWAMP_GRASS_COLD}.
                Biome.SWAMP_GRASS_WARM;
        };
    }

    /**
     * Walks a {@code #variable} chain until it terminates at a concrete namespaced id or fails
     * to resolve. Handles bare variable names (vanilla shorthand where {@code "texture": "all"}
     * means {@code "texture": "#all"}). Cycle-guarded so a malformed pack cannot hang the caller.
     *
     * @param reference the texture reference, possibly starting with {@code #}
     * @param variables the variable map to resolve against
     * @return the resolved namespaced texture id, or the last unresolvable {@code #variable}
     */
    public static @NotNull String dereferenceVariable(@NotNull String reference, @NotNull ConcurrentMap<String, String> variables) {
        String current = reference;

        if (!current.startsWith("#") && !current.contains(":") && variables.containsKey(current))
            current = "#" + current;

        ConcurrentSet<String> visited = Concurrent.newSet();
        while (current.startsWith("#")) {
            if (!visited.add(current)) return current;
            String next = variables.get(current.substring(1));
            if (next == null) return current;
            current = next;
        }

        return current;
    }

    /**
     * Samples a 256x256 ARGB colormap at the location described by a biome's temperature and
     * downfall.
     * <p>
     * The sampling formula is byte-for-byte identical to vanilla's
     * {@code net.minecraft.world.level.ColorMapColorUtil.get(double, double, int[], int)} from the
     * MC 26.1 deobfuscated client, verified via {@code javap} disassembly:
     * <pre>{@code
     * adjTemp = clamp(temperature, 0, 1)   // vanilla clamps in Biome.getGrassColorFromTexture
     * adjRain = clamp(downfall, 0, 1) * adjTemp
     * x = floor((1 - adjTemp) * 255)
     * y = floor((1 - adjRain) * 255)
     * index = (y << 8) | x
     * }</pre>
     * Vanilla returns a magenta fallback ({@code 0xFFFF00FF}) when the index is out of bounds;
     * this helper clamps instead for defensive parity with malformed colormaps.
     *
     * @param colormap the 256x256 colormap pixels in row-major ARGB order
     * @param temperature the biome temperature
     * @param downfall the biome downfall
     * @return the sampled ARGB pixel
     */
    public static int sampleColormap(int @NotNull [] colormap, float temperature, float downfall) {
        float adjTemp = Math.clamp(temperature, 0f, 1f);
        float adjRain = Math.clamp(downfall, 0f, 1f) * adjTemp;

        int x = Math.clamp((int) ((1.0f - adjTemp) * COLORMAP_COORD_MAX), 0, (int) COLORMAP_COORD_MAX);
        int y = Math.clamp((int) ((1.0f - adjRain) * COLORMAP_COORD_MAX), 0, (int) COLORMAP_COORD_MAX);

        return colormap[y * COLORMAP_SIZE + x];
    }

    /**
     * Unpacks the row-major ARGB bytes from a {@link ColorMap} entity into an {@code int[]}
     * colormap suitable for {@link #sampleColormap(int[], float, float)}.
     */
    private int @NotNull [] unpackColorMap(@NotNull ColorMap map) {
        byte[] bytes = map.getPixels();
        int[] pixels = new int[bytes.length / 4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.asIntBuffer().get(pixels);
        return pixels;
    }

}
