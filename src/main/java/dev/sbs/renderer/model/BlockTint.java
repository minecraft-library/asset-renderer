package dev.sbs.renderer.model;

import com.google.gson.annotations.SerializedName;
import dev.sbs.renderer.biome.BiomeTintTarget;
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
 * The biome tint binding for a single block, sourced from a pack-bundled tints table.
 * <p>
 * Vanilla tints come from {@code renderer/vanilla_tints.json}, which mirrors the bytecode of
 * {@code net.minecraft.client.color.block.BlockColors$createDefault} in the 26.1 client jar. A
 * future loader can layer user pack overrides on top by inserting their own {@code BlockTint}
 * rows; for now only the vanilla pack ships entries.
 * <p>
 * The {@link #getTarget() target} field selects which colormap (or hardcoded constant) the
 * renderer samples for this block. When {@link BiomeTintTarget#CONSTANT} is set,
 * {@link #getTintConstant()} carries the ARGB value to apply directly.
 */
@Getter
@Entity
@Table(name = "renderer_block_tints")
public class BlockTint implements JpaModel {

    @Id
    @Column(name = "block_id", nullable = false)
    @SerializedName("block")
    private @NotNull String blockId = "";

    @Column(name = "pack_id", nullable = false)
    private @NotNull String packId = "vanilla";

    @Enumerated(EnumType.STRING)
    @Column(name = "target", nullable = false)
    private @NotNull BiomeTintTarget target = BiomeTintTarget.NONE;

    @SerializedName("constant")
    @Column(name = "tint_constant")
    private @NotNull Optional<Integer> tintConstant = Optional.empty();

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        BlockTint other = (BlockTint) o;
        return Objects.equals(this.getBlockId(), other.getBlockId())
            && Objects.equals(this.getPackId(), other.getPackId())
            && this.getTarget() == other.getTarget()
            && Objects.equals(this.getTintConstant(), other.getTintConstant());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getBlockId(), this.getPackId(), this.getTarget(), this.getTintConstant());
    }

}
