package dev.sbs.renderer.model;

import dev.sbs.renderer.biome.BiomeTintTarget;
import dev.sbs.renderer.model.asset.BlockModelData;
import dev.sbs.renderer.model.asset.BlockStateMultipart;
import dev.sbs.renderer.model.asset.BlockStateVariant;
import dev.simplified.collection.Concurrent;
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
    private @NotNull ConcurrentMap<String, BlockStateVariant> variants = Concurrent.newMap();

    @Column(name = "multipart", nullable = false)
    private @NotNull Optional<BlockStateMultipart> multipart = Optional.empty();

    @Column(name = "tint", nullable = false)
    private @NotNull Tint tint = new Tint(BiomeTintTarget.NONE, Optional.empty());

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
            && Objects.equals(this.getTint(), block.getTint());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getId(), this.getNamespace(), this.getName(), this.getModel(), this.getTextures(), this.getVariants(), this.getMultipart(), this.getTint());
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

}
