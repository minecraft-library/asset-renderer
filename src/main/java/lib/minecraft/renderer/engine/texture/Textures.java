package lib.minecraft.renderer.engine.texture;

import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.request.Biome;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.AnimationData;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.rule.ItemContext;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.kit.AnimationKit;
import lib.minecraft.renderer.options.ItemOptions;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.function.Function;

/**
 * Pack-aware texture resolution service - the texture subsystem every renderer and engine composes
 * ({@code ModelEngine} and {@code RasterEngine} each hold one; the 2D / 3D scene contexts carry it
 * to their layers) for its three families of helpers:
 * <ul>
 *   <li><b>Pack resolution</b> - {@code resolveTexture}, animation strip extraction via
 *       {@link AnimationKit AnimationKit}, and the CIT override lookup.</li>
 *   <li><b>Biome tint sampling</b> - the vanilla
 *       {@code BiomeSpecialEffects$GrassColorModifier} dark-forest / swamp variants and the
 *       default water tint table.</li>
 *   <li><b>Colour overlay</b> - leather-armor dye, dyed-item layers, and arbitrary ARGB tint
 *       compositing.</li>
 * </ul>
 *
 * <p>Stateless beyond its {@link RendererContext}. All methods are idempotent and thread-safe
 * provided the underlying context is too.
 *
 * @see RendererContext
 */
@Getter
@RequiredArgsConstructor
public class Textures {

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

    /**
     * Red-channel add vanilla applies to the base grass color for dark-forest biomes.
     */
    private static final int DARK_FOREST_RED_OFFSET = 0x28;

    /**
     * Green-channel add for dark-forest grass modifier.
     */
    private static final int DARK_FOREST_GREEN_OFFSET = 0x34;

    /**
     * Blue-channel add for dark-forest grass modifier.
     */
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
     * @throws RenderException if no pack provides the texture
     */
    public @NotNull PixelBuffer resolveTexture(@NotNull String textureId) {
        return this.context.resolveTexture(textureId).orElseThrow(() -> new RenderException("No texture registered for id '%s'", textureId));
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
     * {@link RendererContext#findAnimation(String)}.
     *
     * @param textureId the namespaced texture identifier
     * @return the animation metadata, or empty when the texture has no sidecar
     */
    public @NotNull Optional<AnimationData> findAnimation(@NotNull String textureId) {
        return this.context.findAnimation(textureId);
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
     * @throws RenderException when no pack provides the texture
     */
    public @NotNull PixelBuffer resolveTextureAtTick(@NotNull String textureId, int tick) {
        PixelBuffer strip = resolveTexture(textureId);
        Optional<AnimationData> animation = findAnimation(textureId);
        return animation.map(animationData -> AnimationKit.sampleFrame(strip, animationData, tick)).orElse(strip);
    }

    /**
     * Samples the biome tint for the given target using the specified biome's temperature,
     * downfall, and optional colour overrides.
     * <p>
     * Priority order:
     * <ol>
     * <li>{@link Block.TintTarget#NONE} returns opaque white - no tint applied.</li>
     * <li>{@link Block.TintTarget#CONSTANT} defers to the block DTO's {@code tintConstant} and
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
    public int sampleBiomeTint(@NotNull Block.TintTarget target, @NotNull Biome biome) {
        if (target == Block.TintTarget.NONE || target == Block.TintTarget.CONSTANT)
            return ColorMath.WHITE;

        // Pack-supplied colour overrides win over both biome-hardcoded overrides and the colormap
        // sample. The grassColorModifier still applies post-override so dark-forest darkening and
        // swamp warm-grass substitution match vanilla behaviour even when an Optifine pack swaps
        // the base colour. Water short-circuits below: it has its own override key shape and no
        // grass modifier.
        String packKey = packOverrideKeyFor(target, biome);
        if (packKey != null) {
            Optional<Integer> packOverride = this.context.findColorOverride(packKey);
            if (packOverride.isPresent()) {
                if (target == Block.TintTarget.WATER) return packOverride.get();
                return applyModifier(packOverride.get(), biome.grassColorModifier(), target);
            }
        }

        // Water has no colormap in vanilla - the tint is either the per-biome override or the
        // engine-level default. Skip the colormap path entirely and skip grassColorModifier
        // (water is unaffected by the dark-forest / swamp modifiers that only apply to grass).
        if (target == Block.TintTarget.WATER)
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

        Optional<ColorMap> map = this.context.findColorMap(type);
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

    /**
     * Resolves the {@code layer0} texture id for an item, consulting any matching CIT rule via
     * {@link RendererContext#resolveItemTextureOverride(ItemContext)} before falling back to the
     * model's bound layer0. Returns {@code null} when the item supplies no layer0 binding and no
     * CIT rule matches; callers raise their own error in that case.
     * <p>
     * The {@link ItemContext#EMPTY} sentinel short-circuits the override lookup so callers that
     * never populate {@link ItemOptions#getContext()} pay zero rule-walk cost. CIT in vanilla
     * Optifine semantics replaces only {@code layer0}; {@code layer1+} overlays (potion liquid,
     * leather armor overlay, leather helmet pattern) pass through unchanged via
     * {@link Item#getTextures()} and don't go through this helper.
     *
     * @param item the item DTO
     * @param options the per-render options carrying the optional {@link ItemContext}
     * @return the namespaced layer0 texture id, or {@code null} when none is bound
     */
    public String resolveLayer0(@NotNull Item item, @NotNull ItemOptions options) {
        if (options.getContext() != ItemContext.EMPTY) {
            Optional<String> override = this.context.resolveItemTextureOverride(options.getContext());
            if (override.isPresent()) return override.get();
        }
        return item.getTextures().get("layer0");
    }

    /**
     * Returns the {@code optifine/color.properties} key that addresses the given biome-target
     * combination, or {@code null} when the target has no override key shape ({@code NONE},
     * {@code CONSTANT}). The key format mirrors Optifine's grammar:
     * {@code grass.<biome>}, {@code foliage.<biome>}, {@code dryfoliage.<biome>},
     * {@code water.<biome>}, where {@code <biome>} is the biome's local name (everything after
     * the {@code minecraft:} namespace prefix).
     */
    private static String packOverrideKeyFor(@NotNull Block.TintTarget target, @NotNull Biome biome) {
        String prefix = switch (target) {
            case GRASS -> "grass.";
            case FOLIAGE -> "foliage.";
            case DRY_FOLIAGE -> "dryfoliage.";
            case WATER -> "water.";
            default -> null;
        };
        if (prefix == null) return null;
        String id = biome.id();
        int colon = id.indexOf(':');
        return prefix + (colon >= 0 ? id.substring(colon + 1) : id);
    }

    private int applyModifier(int argb, @NotNull Biome.GrassColorModifier modifier, @NotNull Block.TintTarget target) {
        // Vanilla only runs the grass colour modifier on the grass tint - foliage and dry foliage
        // pass through untouched. See {@code Biome.getGrassColor} vs {@code Biome.getFoliageColor}
        // in the MC 26.1 client source: only the former invokes {@code grassColorModifier.modifyColor}.
        if (target != Block.TintTarget.GRASS) return argb;

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
    public static @NotNull String resolveTextureReference(@NotNull String reference, @NotNull ConcurrentMap<String, String> variables) {
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
     * Resolves and loads every unique face texture referenced by a model's elements into a map
     * keyed by the raw {@link ModelFace#getTexture()} reference (including any leading {@code #}).
     * <p>
     * Walks each element's faces, dereferences the {@code #variable} chain via
     * {@link #resolveTextureReference}, skips refs that stay unresolved ({@code #}-prefixed) or
     * blank, and loads each concrete id through the supplied {@code resolve} function exactly
     * once. The caller chooses how a concrete id becomes a {@link PixelBuffer} - block paths pass
     * a tick-aware {@code id -> Optional.of(resolveTextureAtTick(id, 0))}, the entity path passes
     * the context's {@code Optional}-returning lookup - so this helper never decides the
     * resolution strategy. Refs whose {@code resolve} yields an empty {@link Optional} are
     * dropped, leaving the kit to treat them as no-texture faces.
     *
     * @param elements the model elements whose faces reference textures
     * @param textureVars the model's {@code #variable} bindings to resolve refs against
     * @param resolve maps a concrete namespaced texture id to its pixel buffer, or empty to skip
     * @return a new map from raw face ref to its loaded pixel buffer
     */
    public static @NotNull ConcurrentMap<String, PixelBuffer> loadElementFaceTextures(
        @NotNull Iterable<ModelElement> elements,
        @NotNull ConcurrentMap<String, String> textureVars,
        @NotNull Function<String, Optional<PixelBuffer>> resolve
    ) {
        ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
        for (ModelElement element : elements) {
            for (ModelFace face : element.getFaces().values()) {
                String ref = face.getTexture();
                if (ref.isBlank() || faceTextures.containsKey(ref)) continue;
                String resolvedId = resolveTextureReference(ref, textureVars);
                if (resolvedId.startsWith("#")) continue;
                resolve.apply(resolvedId).ifPresent(buffer -> faceTextures.put(ref, buffer));
            }
        }
        return faceTextures;
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
