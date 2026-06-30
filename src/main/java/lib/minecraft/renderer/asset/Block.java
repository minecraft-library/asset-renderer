package lib.minecraft.renderer.asset;

import com.google.gson.JsonObject;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.options.BlockOptions;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * A fully-parsed block definition backed by its vanilla model JSON and blockstate variants.
 * <p>
 * Every field is populated once during {@code Pipeline} bootstrap and stored verbatim; no
 * lazy or computed fields live on this DTO. Lookup happens through the active renderer
 * context.
 */
@Getter
@AllArgsConstructor
@EqualsAndHashCode
public final class Block {

    /**
     * The block's namespaced identifier (e.g. {@code minecraft:furnace}).
     */
    private final @NotNull ResourceId id;

    /**
     * The resolved model supplying the block's geometry and texture bindings.
     */
    private final @NotNull ModelData model;

    /**
     * The model's texture variable bindings, keyed by variable name.
     */
    private final @NotNull ConcurrentMap<String, String> textures;

    /**
     * The blockstate variants keyed by their sorted {@code property=value} state key, each carrying
     * a resolved model and whole-block rotation.
     */
    private final @NotNull ConcurrentMap<String, Variant> variants;

    /**
     * The multipart blockstate definition, or empty when the block uses discrete variants.
     */
    private final @NotNull Optional<Multipart> multipart;

    /**
     * Tag names this block belongs to, e.g. {@code ["minecraft:stairs", "minecraft:wooden_stairs"]}.
     */
    private final @NotNull ConcurrentList<String> tags;

    /**
     * The biome tint binding selecting which colormap or constant the renderer samples for tinted faces.
     */
    private final @NotNull Tint tint;

    /**
     * Rendering override for blocks whose visual geometry comes from a vanilla
     * {@code BlockEntityRenderer} (beds, chests, banners, shulkers, signs, skulls, conduit,
     * decorated_pot, etc.). When present, renderers prefer {@link Entity#model()} over
     * {@link #getModel()}, multiply {@link Entity#tintArgb()} against sampled texels, honour
     * {@link Entity#iconRotation()} for the atlas icon, and optionally compose
     * {@link Entity#parts()} for multi-part atlas views (bed head + foot, decorated_pot body +
     * sides, banner post + flag). Absent for plain blocks.
     */
    private final @NotNull Optional<Entity> entity;

    /**
     * Where this block's registration originated. Used by atlas tile classification to label the
     * source path (block-model file, blockstate-only fallback, or block-entity geometry override)
     * without forcing consumers to type-check the renderer-context implementation.
     */
    private final @NotNull Source source;

    /**
     * The block's canonical default blockstate key as {@code property=value} pairs sorted
     * alphabetically (e.g. {@code "facing=north,half=lower,hinge=left,open=false,powered=false"}),
     * or empty when the block has no properties. Sourced from {@code block_defaults.json} (an ASM
     * bytewalk of {@code registerDefaultState}) and baked on at pipeline-context construction. The
     * renderer falls back to this key when a caller supplies no explicit variant, so blocks with
     * per-state models render their default rather than whichever state registered first.
     */
    private final @NotNull String defaultStateKey;

    /**
     * The provenance of a {@link Block}'s registration. Drives atlas tile classification and any
     * future caller that needs to know how the block reached the renderer context.
     */
    public enum Source {

        /**
         * Registered from a primary {@code block/<id>.json} model file.
         */
        PRIMARY,

        /**
         * Registered via the blockstate-only fallback path - the blockstate definition exists but
         * no matching block-model file was found, so the model is resolved through the first
         * variant or multipart entry instead.
         */
        BLOCKSTATE_ONLY,

        /**
         * Geometry is sourced from a vanilla {@code BlockEntityRenderer} (beds, chests, banners,
         * shulkers, signs, skulls, conduit, decorated_pot, etc.) rather than a model file.
         */
        TILE_ENTITY

    }

    /**
     * Identifies which biome colormap drives a block face's tint, or flags that the tint comes
     * from a hardcoded constant on the block DTO.
     */
    public enum TintTarget {

        /**
         * The face is not biome-tinted.
         */
        NONE,

        /**
         * Sample the grass colormap. Applies to grass blocks, tall grass, ferns, etc.
         */
        GRASS,

        /**
         * Sample the foliage colormap. Applies to most leaves.
         */
        FOLIAGE,

        /**
         * Sample the dry-foliage colormap. Applies to pale oak and a handful of other biomes.
         */
        DRY_FOLIAGE,

        /**
         * Use the biome's water colour override when present, or the engine-level default
         * {@code 0xFF3F76E4} otherwise. Vanilla water has no colormap; biomes either carry an
         * explicit {@code water_color} value or inherit the default.
         */
        WATER,

        /**
         * Use the block's {@code tintConstant} field directly. Applies to redstone wire, stems, etc.
         */
        CONSTANT

    }

    /**
     * The biome tint binding for a block, selecting which colormap (or hardcoded constant) the
     * renderer samples for tinted faces.
     *
     * @param target the tint source - {@link TintTarget#NONE NONE} for untinted blocks,
     *     {@link TintTarget#CONSTANT CONSTANT} for a hardcoded ARGB value, or a colormap
     *     target like {@link TintTarget#GRASS GRASS} / {@link TintTarget#FOLIAGE FOLIAGE}
     * @param constant the hardcoded ARGB value when target is {@code CONSTANT}
     */
    public record Tint(@NotNull TintTarget target, @NotNull Optional<Integer> constant) {}

    /**
     * A single blockstate variant entry, specifying which model to use and what whole-block
     * rotation to apply. Parsed from blockstate JSON files like
     * {@code assets/minecraft/blockstates/furnace.json}.
     * <p>
     * The {@code x} and {@code y} rotations are multiples of 90 degrees applied to the entire
     * model before rendering. These are distinct from element-level rotations in the model JSON.
     * <p>
     * The {@code model} is the resolved {@link ModelData} this variant references, baked in
     * at pipeline-context construction time so a variant reaches its geometry through its owning
     * {@link Block} rather than a context-level model registry. Variants whose {@code modelId}
     * cannot be resolved against the loaded model set carry an element-less {@code ModelData}.
     *
     * @param modelId the namespaced model reference (e.g. {@code "minecraft:block/furnace"})
     * @param model the resolved model this variant references, or element-less when unresolved
     * @param x the whole-model X rotation in degrees (0, 90, 180, or 270)
     * @param y the whole-model Y rotation in degrees (0, 90, 180, or 270)
     * @param uvlock whether UVs should be locked to the block grid during rotation
     */
    public record Variant(@NotNull String modelId, @NotNull ModelData model, int x, int y, boolean uvlock) {

        /**
         * Returns {@code true} when this variant applies rotation to the model.
         */
        public boolean hasRotation() {
            return this.x != 0 || this.y != 0;
        }

    }

    /**
     * A parsed {@code "multipart"} blockstate definition. Each part carries an optional condition
     * and a model reference (with rotation) to apply when the condition matches the block's
     * properties. Parts without a condition are unconditional and always rendered.
     *
     * @param parts the ordered list of conditional or unconditional parts
     */
    public record Multipart(@NotNull ConcurrentList<Part> parts) {

        /**
         * A single entry in a multipart blockstate.
         *
         * @param when the raw condition JSON, or {@code null} for unconditional parts
         * @param apply the model reference and rotation to render when the condition matches
         */
        public record Part(@Nullable JsonObject when, @NotNull Variant apply) {}

    }

    /**
     * Rendering metadata for a block entity - carries the custom geometry extracted from a vanilla
     * {@code BlockEntityRenderer} plus per-block presentation knobs (entity texture, dye tint, icon
     * rotation, multi-block flag, atlas-time composition parts). Populated by
     * {@link BlockModelLoader} for the ~180 block ids whose
     * visual appearance comes from a tile-entity renderer rather than their {@code block.json}.
     *
     * @param beType vanilla {@code BlockEntityType} reference for diagnostics ({@code "minecraft:bed"})
     * @param model extracted geometry (elements + face UVs)
     * @param textureId entity texture id bound to the {@code "#entity"} texture variable, e.g
     *     {@code "minecraft:entity/bed/red"}
     * @param tintArgb ARGB tint multiplied against every sampled texel - used for per-dye banner
     *     colouring; {@link ColorMath#WHITE} for no tint
     * @param iconRotation Y-axis rotation in degrees applied only to the atlas icon (beds use 90°
     *     to angle the headboard toward the camera)
     * @param multiBlock {@code true} when the geometry extends outside the {@code 0..16} block
     *     bbox and the atlas icon needs runtime {@code recenterAndFit}
     * @param parts atlas-time composition instructions - additional entity models merged at an
     *     offset (bed foot merged onto bed head, decorated_pot sides onto the base, banner flag
     *     onto the post). Empty for single-piece entities.
     * @param additive when {@code true}, the entity {@link #model()} is merged ON TOP of the
     *     block's blockstate-resolved primary model rather than replacing it. Used for blocks
     *     whose vanilla render is "blockstate fixture + entity overlay" - the bell hangs from
     *     posts in {@code block/bell_floor.json} but its bell-cup body comes from
     *     {@code BellModel.createBodyLayer}. Default {@code false} preserves the original
     *     replace-the-model semantics used by chests / beds / banners / shulkers / signs / skulls.
     */
    public record Entity(
        @NotNull String beType,
        @NotNull ModelData model,
        @NotNull String textureId,
        int tintArgb,
        int iconRotation,
        boolean multiBlock,
        @NotNull ConcurrentList<Part> parts,
        boolean additive
    ) {

        /**
         * An atlas-time composition instruction - additional geometry merged into the parent
         * {@link Entity} at a positional offset. Used when vanilla's {@code BlockEntityRenderer}
         * stitches multiple {@code LayerDefinition}s into the same in-world block render (bed head
         * + foot, decorated_pot base + sides, banner post + flag).
         * <p>
         * The renderer merges these at render time - gated on
         * {@link BlockOptions#isMergeParts()}. Atlas callers pass
         * {@code mergeParts=true} (default) to produce the composed icon; future scene callers
         * pass {@code false} to render one variant's geometry at a time.
         *
         * @param modelId source entity model id for diagnostics ({@code "minecraft:bed_foot"})
         * @param model part geometry (elements + face UVs) ready to append to the parent
         * @param texture absolute texture id that rebinds the part's {@code "#entity"} face refs
         * @param offset model-unit shift applied to every from/to + rotation.origin on the merged
         *     elements ({@code [0, 0, 16]} to place the bed foot one block past the head)
         */
        public record Part(
            @NotNull String modelId,
            @NotNull ModelData model,
            @NotNull String texture,
            float @NotNull [] offset
        ) {

            @Override
            public boolean equals(Object o) {
                if (o == null || getClass() != o.getClass()) return false;
                Part part = (Part) o;
                return Objects.equals(this.modelId, part.modelId)
                    && Objects.equals(this.model, part.model)
                    && Objects.equals(this.texture, part.texture)
                    && Arrays.equals(this.offset, part.offset);
            }

            @Override
            public int hashCode() {
                return Objects.hash(this.modelId, this.model, this.texture, Arrays.hashCode(this.offset));
            }

        }

    }

}
