package dev.sbs.renderer.asset;

import com.google.gson.JsonObject;

import dev.sbs.renderer.geometry.Biome;
import dev.sbs.renderer.asset.model.BlockModelData;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
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
 * {@link dev.sbs.renderer.engine.RendererContext}.
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
            && Objects.equals(this.getTint(), block.getTint());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getId(), this.getNamespace(), this.getName(), this.getModel(), this.getTextures(), this.getVariants(), this.getMultipart(), this.getTags(), this.getTint());
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

}
