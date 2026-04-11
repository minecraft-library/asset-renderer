package dev.sbs.renderer.model;

import com.google.gson.JsonObject;
import dev.sbs.renderer.biome.BiomeTintTarget;
import dev.sbs.renderer.model.asset.BlockModelData;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.persistence.JpaModel;
import dev.simplified.persistence.type.GsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
 * lazy or computed fields live on this entity. Lookup happens through the standard JPA repository
 * API ({@code findAll}, {@code findById}, etc.).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "renderer_blocks")
public class Block implements JpaModel {

    @Id
    @Column(name = "id", nullable = false)
    private @NotNull String id = "";

    @Column(name = "namespace", nullable = false)
    private @NotNull String namespace = "minecraft";

    @Column(name = "name", nullable = false)
    private @NotNull String name = "";

    @Column(name = "model", nullable = false)
    private @NotNull BlockModelData model = new BlockModelData();

    @Column(name = "textures", nullable = false)
    private @NotNull ConcurrentMap<String, String> textures = Concurrent.newMap();

    @Column(name = "variants", nullable = false)
    private @NotNull ConcurrentMap<String, Variant> variants = Concurrent.newMap();

    @Column(name = "multipart", nullable = false)
    private @NotNull Optional<Multipart> multipart = Optional.empty();

    /** Tag names this block belongs to, e.g. {@code ["minecraft:stairs", "minecraft:wooden_stairs"]}. */
    @Column(name = "tags", nullable = false)
    private @NotNull ConcurrentList<String> tags = Concurrent.newList();

    @Column(name = "tint", nullable = false)
    private @NotNull Tint tint = new Tint(BiomeTintTarget.NONE, Optional.empty());

    /** Entity model fallback for blocks rendered by tile entity renderers (chests, signs, beds, etc.). */
    @Column(name = "entity_mapping")
    private @NotNull Optional<EntityMapping> entityMapping = Optional.empty();

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
            && Objects.equals(this.getEntityMapping(), block.getEntityMapping());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getId(), this.getNamespace(), this.getName(), this.getModel(), this.getTextures(), this.getVariants(), this.getMultipart(), this.getTags(), this.getTint(), this.getEntityMapping());
    }

    /**
     * The biome tint binding for a block, selecting which colormap (or hardcoded constant) the
     * renderer samples for tinted faces.
     *
     * @param target the tint source - {@link BiomeTintTarget#NONE NONE} for untinted blocks,
     *     {@link BiomeTintTarget#CONSTANT CONSTANT} for a hardcoded ARGB value, or a colormap
     *     target like {@link BiomeTintTarget#GRASS GRASS} / {@link BiomeTintTarget#FOLIAGE FOLIAGE}
     * @param constant the hardcoded ARGB value when target is {@code CONSTANT}
     */
    @GsonType
    public record Tint(@NotNull BiomeTintTarget target, @NotNull Optional<Integer> constant) {}

    /**
     * Maps a block to an entity model for fallback rendering. Blocks whose vanilla geometry is
     * rendered by tile entity renderers (chests, signs, beds, shulker boxes) carry this mapping
     * so the block renderer can delegate to entity model geometry when the block has no elements.
     *
     * @param model the entity model id providing the geometry, e.g. {@code "minecraft:chest"}
     * @param texture the entity texture id to render with, e.g. {@code "minecraft:entity/chest/normal"}
     */
    @GsonType
    public record EntityMapping(@NotNull String model, @NotNull String texture) {}

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
    @GsonType
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
    @GsonType
    public record Multipart(@NotNull ConcurrentList<Part> parts) {

        /**
         * A single entry in a multipart blockstate.
         *
         * @param when the raw condition JSON, or {@code null} for unconditional parts
         * @param apply the model reference and rotation to render when the condition matches
         */
        public record Part(@Nullable JsonObject when, @NotNull Variant apply) {}

    }

}
