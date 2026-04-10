package dev.sbs.renderer.model;

import com.google.gson.annotations.SerializedName;
import dev.sbs.renderer.biome.BiomeTintTarget;
import dev.sbs.renderer.model.asset.BlockModelData;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.persistence.JpaModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
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

    @Column(name = "states", nullable = false)
    private @NotNull ConcurrentMap<String, BlockModelData> states = Concurrent.newMap();

    @Enumerated(EnumType.STRING)
    @Column(name = "tint_target", nullable = false)
    private @NotNull BiomeTintTarget tintTarget = BiomeTintTarget.NONE;

    @SerializedName("tint_constant")
    @Column(name = "tint_constant")
    private @NotNull Optional<Integer> tintConstant = Optional.empty();

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Block block = (Block) o;
        return Objects.equals(this.getId(), block.getId())
            && Objects.equals(this.getNamespace(), block.getNamespace())
            && Objects.equals(this.getName(), block.getName())
            && Objects.equals(this.getModel(), block.getModel())
            && Objects.equals(this.getTextures(), block.getTextures())
            && Objects.equals(this.getStates(), block.getStates())
            && this.getTintTarget() == block.getTintTarget()
            && Objects.equals(this.getTintConstant(), block.getTintConstant());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getId(), this.getNamespace(), this.getName(), this.getModel(), this.getTextures(), this.getStates(), this.getTintTarget(), this.getTintConstant());
    }

}
