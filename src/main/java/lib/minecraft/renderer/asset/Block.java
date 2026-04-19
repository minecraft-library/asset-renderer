package lib.minecraft.renderer.asset;

import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.model.BlockModelData;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.geometry.Biome;
import lib.minecraft.renderer.options.BlockOptions;
import lib.minecraft.renderer.pipeline.loader.BlockEntityLoader;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

/**
 * A fully-parsed block definition backed by its vanilla model JSON and blockstate variants.
 * <p>
 * Every field is populated once during {@code AssetPipeline} bootstrap and stored verbatim; no
 * lazy or computed fields live on this DTO. Lookup happens through the active
 * {@link RendererContext}.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Block {

    private @NotNull String id = "";

    private @NotNull String namespace = "minecraft";

    private @NotNull String name = "";

    private @NotNull BlockModelData model = new BlockModelData();

    private @NotNull ConcurrentMap<String, String> textures = Concurrent.newMap();

    private @NotNull ConcurrentMap<String, Variant> variants = Concurrent.newMap();

    private @NotNull Optional<Multipart> multipart = Optional.empty();

    /** Tag names this block belongs to, e.g. {@code ["minecraft:stairs", "minecraft:wooden_stairs"]}. */
    private @NotNull ConcurrentList<String> tags = Concurrent.newList();

    private @NotNull Tint tint = new Tint(Biome.TintTarget.NONE, Optional.empty());

    /**
     * Rendering override for blocks whose visual geometry comes from a vanilla
     * {@code BlockEntityRenderer} (beds, chests, banners, shulkers, signs, skulls, conduit,
     * decorated_pot, etc.). When present, renderers prefer {@link Entity#model()} over
     * {@link #getModel()}, multiply {@link Entity#tintArgb()} against sampled texels, honour
     * {@link Entity#iconRotation()} for the atlas icon, and optionally compose
     * {@link Entity#parts()} for multi-part atlas views (bed head + foot, decorated_pot body +
     * sides, banner post + flag). Absent for plain blocks.
     */
    private @NotNull Optional<Entity> entity = Optional.empty();

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Block block = (Block) o;
        return Objects.equals(this.getId(), block.getId())
            && Objects.equals(this.getNamespace(), block.getNamespace())
            && Objects.equals(this.getName(), block.getName())
            && Objects.equals(this.getModel(), block.getModel())
            && Objects.equals(this.getTextures(), block.getTextures())
            && Objects.equals(this.getVariants(), block.getVariants())
            && Objects.equals(this.getMultipart(), block.getMultipart())
            && Objects.equals(this.getTags(), block.getTags())
            && Objects.equals(this.getTint(), block.getTint())
            && Objects.equals(this.getEntity(), block.getEntity());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getId(), this.getNamespace(), this.getName(), this.getModel(), this.getTextures(), this.getVariants(), this.getMultipart(), this.getTags(), this.getTint(), this.getEntity());
    }

    /**
     * The biome tint binding for a block, selecting which colormap (or hardcoded constant) the
     * renderer samples for tinted faces.
     *
     * @param target the tint source - {@link Biome.TintTarget#NONE NONE} for untinted blocks,
     *     {@link Biome.TintTarget#CONSTANT CONSTANT} for a hardcoded ARGB value, or a colormap
     *     target like {@link Biome.TintTarget#GRASS GRASS} / {@link Biome.TintTarget#FOLIAGE FOLIAGE}
     * @param constant the hardcoded ARGB value when target is {@code CONSTANT}
     */
    public record Tint(@NotNull Biome.TintTarget target, @NotNull Optional<Integer> constant) {}

    /**
     * A single blockstate variant entry, specifying which model to use and what whole-block
     * rotation to apply. Parsed from blockstate JSON files like
     * {@code assets/minecraft/blockstates/furnace.json}.
     * <p>
     * The {@code x} and {@code y} rotations are multiples of 90 degrees applied to the entire
     * model before rendering. These are distinct from element-level rotations in the model JSON.
     *
     * @param modelId the namespaced model reference (e.g. {@code "minecraft:block/furnace"})
     * @param x the whole-model X rotation in degrees (0, 90, 180, or 270)
     * @param y the whole-model Y rotation in degrees (0, 90, 180, or 270)
     * @param uvlock whether UVs should be locked to the block grid during rotation
     */
    public record Variant(@NotNull String modelId, int x, int y, boolean uvlock) {

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
     * {@link BlockEntityLoader} for the ~180 block ids whose
     * visual appearance comes from a tile-entity renderer rather than their {@code block.json}.
     *
     * @param beType vanilla {@code BlockEntityType} reference for diagnostics ({@code "minecraft:bed"})
     * @param model extracted geometry (elements + face UVs)
     * @param textureId entity texture id bound to the {@code "#entity"} texture variable, e.g.
     *     {@code "minecraft:entity/bed/red"}
     * @param tintArgb ARGB tint multiplied against every sampled texel - used for per-dye banner
     *     colouring; {@link dev.simplified.image.pixel.ColorMath#WHITE} for no tint
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
        @NotNull BlockModelData model,
        @NotNull String textureId,
        int tintArgb,
        int iconRotation,
        boolean multiBlock,
        @NotNull ConcurrentList<Part> parts,
        boolean additive
    ) {

        /** Backwards-compatible constructor for the existing replace-the-model entries. */
        public Entity(@NotNull String beType, @NotNull BlockModelData model, @NotNull String textureId,
                      int tintArgb, int iconRotation, boolean multiBlock, @NotNull ConcurrentList<Part> parts) {
            this(beType, model, textureId, tintArgb, iconRotation, multiBlock, parts, false);
        }

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
            @NotNull BlockModelData model,
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
                    && java.util.Arrays.equals(this.offset, part.offset);
            }

            @Override
            public int hashCode() {
                return Objects.hash(this.modelId, this.model, this.texture, java.util.Arrays.hashCode(this.offset));
            }

        }

    }

}
