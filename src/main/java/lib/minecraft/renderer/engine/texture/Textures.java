package lib.minecraft.renderer.engine.texture;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.AnimationData;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.asset.model.ModelTexture;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.kit.AnimationKit;
import lib.minecraft.renderer.exception.RenderException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.Function;

/**
 * Pack-aware texture resolution service - the texture subsystem every renderer and engine composes
 * ({@code ModelEngine} and {@code RasterEngine} each hold one; the 2D / 3D scene contexts carry it
 * to their layers) for its two families of helpers:
 * <ul>
 *   <li><b>Pack resolution</b> - {@code resolveTexture} / {@code resolveTextureAtTick} (animation
 *       strip extraction via {@link AnimationKit AnimationKit}) and the {@code minecraft:entity/}
 *       entity-texture prefix ({@code resolveEntityTextureAtTick}).</li>
 *   <li><b>Tint sampling</b> - biome grass / foliage / water tint (the vanilla
 *       {@code BiomeSpecialEffects$GrassColorModifier} dark-forest / swamp variants and the default
 *       water table) and the redstone-wire-by-power tint, each honouring pack
 *       {@code color.properties} overrides.</li>
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

    /**
     * Vanilla redstone-wire ARGB tints indexed by power level {@code 0..15}, transcribed byte-for-byte
     * from {@code net.minecraft.world.level.block.RedstoneWireBlock.COLORS} - the 16-step gradient the
     * wire renderer applies to {@code redstone_dust_dot} / {@code redstone_dust_line0/1}.
     * Package-private so {@code TexturesTest} can pin the table content.
     */
    static final int @NotNull [] REDSTONE_TINTS = {
        0xFF4B0000, 0xFF6F0000, 0xFF790000, 0xFF820000,
        0xFF8A0000, 0xFF940000, 0xFF9D0000, 0xFFA50000,
        0xFFAE0000, 0xFFB70000, 0xFFBF0000, 0xFFC90000,
        0xFFD20000, 0xFFDA0000, 0xFFE30000, 0xFFEC0000
    };

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
     * Resolves an entity texture ref against the vanilla pack at {@code minecraft:entity/<ref>} at a
     * specific animation tick, wrapping {@link #tryResolveTextureAtTick} with the
     * {@code minecraft:entity/} prefix. Centralises the {@code minecraft:entity/} prefix idiom the
     * entity renderer's base / overlay / collar / equipment / family-member paths all share. A
     * sidecar-less entity texture (every vanilla entity) returns its buffer unchanged, so
     * {@code tick 0} is byte-identical to the raw lookup; a sidecar-carrying texture samples the frame
     * for {@code tick} (frame-0-at-default when static).
     *
     * @param ref the entity texture sub-path (without the {@code minecraft:entity/} prefix or the
     *     {@code .png} suffix)
     * @param tick the current animation tick (free-running, signed)
     * @return the resolved frame, or empty when the pack has no match
     */
    public @NotNull Optional<PixelBuffer> resolveEntityTextureAtTick(@NotNull String ref, int tick) {
        return tryResolveTextureAtTick("minecraft:entity/" + ref, tick);
    }

    /**
     * The villager hat flag of an entity texture ref, resolved against the vanilla pack at
     * {@code minecraft:entity/<ref>} - the {@code villager} sidecar section of the selected type or
     * profession texture. Centralises the {@code minecraft:entity/} prefix idiom exactly as
     * {@link #resolveEntityTextureAtTick} does. A texture with no sidecar, or a sidecar with no
     * {@code villager} section, reads as {@link MCMeta.Villager.Hat#NONE} - vanilla's own default for an
     * absent sidecar.
     *
     * @param ref the entity texture sub-path (without the {@code minecraft:entity/} prefix or the
     *     {@code .png} suffix)
     * @return the declared hat flag, or {@link MCMeta.Villager.Hat#NONE}
     */
    public @NotNull MCMeta.Villager.Hat findVillagerHat(@NotNull String ref) {
        return this.context.findVillager("minecraft:entity/" + ref)
            .map(MCMeta.Villager::hat)
            .orElse(MCMeta.Villager.Hat.NONE);
    }

    /**
     * Returns the parsed {@code .mcmeta} animation sidecar for the given texture, if any. Wraps
     * {@link RendererContext#findAnimation(String)}.
     *
     * @param textureId the namespaced texture identifier
     * @return the animation metadata, or empty when the texture has no sidecar
     */
    private @NotNull Optional<AnimationData> findAnimation(@NotNull String textureId) {
        return this.context.findAnimation(textureId);
    }

    /**
     * Resolves a texture and returns the specific animation frame that should be displayed at
     * the given tick. For textures without an {@code .mcmeta} sidecar the source buffer is
     * returned unchanged; for animated textures {@link AnimationKit#sampleFrame} extracts the
     * correct strip frame, blending adjacent frames when {@link AnimationData#interpolate()}
     * is set.
     *
     * @param textureId the namespaced texture identifier
     * @param tick the current animation tick (free-running, signed)
     * @return the frame to render at this tick
     * @throws RenderException when no pack provides the texture
     */
    public @NotNull PixelBuffer resolveTextureAtTick(@NotNull String textureId, int tick) {
        return tryResolveTextureAtTick(textureId, tick)
            .orElseThrow(() -> new RenderException("No texture registered for id '%s'", textureId));
    }

    /**
     * Like {@link #resolveTextureAtTick} but returns empty instead of throwing when the pack stack
     * has no match - the frame-flattening counterpart of {@link #tryResolveTexture}. For textures
     * without an {@code .mcmeta} sidecar the source buffer is returned unchanged; for animated
     * textures {@link AnimationKit#sampleFrame} extracts the correct strip frame (blending adjacent
     * frames when {@link AnimationData#interpolate()} is set).
     *
     * @param textureId the namespaced texture identifier
     * @param tick the current animation tick (free-running, signed)
     * @return the frame to render at this tick, or empty when the texture is unknown
     */
    public @NotNull Optional<PixelBuffer> tryResolveTextureAtTick(@NotNull String textureId, int tick) {
        Optional<PixelBuffer> strip = tryResolveTexture(textureId);
        if (strip.isEmpty()) return strip;
        Optional<AnimationData> animation = findAnimation(textureId);
        return animation.map(animationData -> AnimationKit.sampleFrame(strip.get(), animationData, tick)).or(() -> strip);
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

        int sampled = sampleColormap(map.get().pixels(), biome.temperature(), biome.downfall());
        return applyModifier(sampled, biome.grassColorModifier(), target);
    }

    /**
     * Resolves the redstone-wire ARGB tint for a power level, consulting the active pack's
     * {@code redstone.<power>} {@code color.properties} override before falling back to the bundled
     * vanilla {@link #REDSTONE_TINTS} table - the same pack-override-then-vanilla shape as
     * {@link #sampleBiomeTint}.
     *
     * @param power the redstone wire power level, {@code 0..15}
     * @return the resolved ARGB tint
     * @throws IllegalArgumentException if {@code power} is outside {@code [0, 15]}
     */
    public int sampleRedstoneTint(int power) {
        if (power < 0 || power >= REDSTONE_TINTS.length)
            throw new IllegalArgumentException("Redstone power '%d' is outside [0, 15]".formatted(power));
        return this.context.findColorOverride("redstone." + power).orElse(REDSTONE_TINTS[power]);
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
     * @param variables the variable map to resolve against, valued by {@link ModelTexture}
     * @return the resolved namespaced texture id, or the last unresolvable {@code #variable}
     */
    public static @NotNull String resolveTextureReference(@NotNull String reference, @NotNull ConcurrentMap<String, ModelTexture> variables) {
        String current = reference;

        if (!current.startsWith("#") && !current.contains(":") && variables.containsKey(current))
            current = "#" + current;

        ConcurrentSet<String> visited = Concurrent.newSet();
        while (current.startsWith("#")) {
            if (!visited.add(current)) return current;
            ModelTexture next = variables.get(current.substring(1));
            if (next == null) return current;
            current = next.sprite();
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
        @NotNull ConcurrentMap<String, ModelTexture> textureVars,
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
     * Resolves which of a model's element face refs are force-translucent - the raw
     * {@link ModelFace#getTexture()} refs whose texture variable carried {@code force_translucent} in
     * the 26.1 object form. Keys match {@link #loadElementFaceTextures} exactly (the raw ref including
     * any leading {@code #}) and the {@code #variable} chain is walked as in
     * {@link #resolveTextureReference}, so a returned ref is the same key
     * {@link lib.minecraft.renderer.engine.kit.BlockGeometryKit#buildFromElements} looks up. A face is
     * force-translucent when any variable in its deref chain is flagged.
     *
     * @param elements the model's element boxes
     * @param textureVars the model's {@code #variable} bindings, valued by {@link ModelTexture}
     * @return the raw face refs to force into the translucent pass, empty when nothing is flagged
     */
    public static @NotNull ConcurrentSet<String> resolveForceTranslucentRefs(
        @NotNull Iterable<ModelElement> elements,
        @NotNull ConcurrentMap<String, ModelTexture> textureVars
    ) {
        ConcurrentSet<String> refs = Concurrent.newSet();
        if (textureVars.values().stream().noneMatch(ModelTexture::forceTranslucent)) return refs;

        for (ModelElement element : elements) {
            for (ModelFace face : element.getFaces().values()) {
                String ref = face.getTexture();
                if (ref.isBlank() || refs.contains(ref)) continue;
                if (isForceTranslucent(ref, textureVars)) refs.add(ref);
            }
        }
        return refs;
    }

    /**
     * Walks the {@code #variable} chain of a face ref, reporting whether any hop resolves to a
     * texture flagged {@code force_translucent}.
     *
     * @param reference the raw face-texture ref, possibly starting with {@code #}
     * @param variables the variable map to resolve against, valued by {@link ModelTexture}
     * @return {@code true} when any variable in the chain carried {@code force_translucent}
     */
    private static boolean isForceTranslucent(
        @NotNull String reference,
        @NotNull ConcurrentMap<String, ModelTexture> variables
    ) {
        String current = reference;
        if (!current.startsWith("#") && !current.contains(":") && variables.containsKey(current))
            current = "#" + current;

        ConcurrentSet<String> visited = Concurrent.newSet();
        while (current.startsWith("#")) {
            if (!visited.add(current)) return false;
            ModelTexture texture = variables.get(current.substring(1));
            if (texture == null) return false;
            if (texture.forceTranslucent()) return true;
            current = texture.sprite();
        }
        return false;
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
     * <p>
     * The pixel is read straight out of the {@link ColorMap}'s bytes at its own offset. A colormap
     * is 256x256, so unpacking the whole thing first cost a 65,536-element {@code int[]} - 256 KiB -
     * to hand back one element. The four-byte big-endian read here is the same value bit for bit:
     * {@link ColorMap} stores what {@code ColorMapLoader} packed big-endian, which is also how the
     * unpack read it back, since {@code ByteBuffer.wrap} is big-endian by default. Each byte must be
     * masked to {@code 0xFF} - dropping the mask on any of the low three sign-extends and corrupts
     * every pixel whose channel reaches {@code 0x80}.
     *
     * @param colormap the 256x256 colormap's raw ARGB bytes, row-major and big-endian
     * @param temperature the biome temperature
     * @param downfall the biome downfall
     * @return the sampled ARGB pixel
     */
    public static int sampleColormap(byte @NotNull [] colormap, float temperature, float downfall) {
        float adjTemp = Math.clamp(temperature, 0f, 1f);
        float adjRain = Math.clamp(downfall, 0f, 1f) * adjTemp;

        int x = Math.clamp((int) ((1.0f - adjTemp) * COLORMAP_COORD_MAX), 0, (int) COLORMAP_COORD_MAX);
        int y = Math.clamp((int) ((1.0f - adjRain) * COLORMAP_COORD_MAX), 0, (int) COLORMAP_COORD_MAX);

        int offset = (y * COLORMAP_SIZE + x) * Integer.BYTES;
        return ((colormap[offset] & 0xFF) << 24)
            | ((colormap[offset + 1] & 0xFF) << 16)
            | ((colormap[offset + 2] & 0xFF) << 8)
            | (colormap[offset + 3] & 0xFF);
    }

}
