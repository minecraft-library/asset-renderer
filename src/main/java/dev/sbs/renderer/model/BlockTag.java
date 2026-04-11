package dev.sbs.renderer.model;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.persistence.JpaModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A fully resolved vanilla block tag, containing the flattened set of block IDs that belong to
 * it after all {@code #tag} inheritance references have been walked.
 * <p>
 * Vanilla ships ~248 block tags under {@code data/minecraft/tags/block/} defining semantic
 * groups like {@code stairs}, {@code logs}, {@code wool}, {@code candles}, etc. Tags can
 * reference other tags via the {@code #} prefix; this entity stores the final resolved member
 * list so consumers never need to re-resolve inheritance at query time.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "renderer_block_tags")
public class BlockTag implements JpaModel {

    /** Namespaced tag id, e.g. {@code "minecraft:stairs"}. */
    @Id
    @Column(name = "id", nullable = false)
    private @NotNull String id = "";

    /** Fully resolved block IDs that belong to this tag. */
    @Column(name = "values", nullable = false)
    private @NotNull ConcurrentList<String> values = Concurrent.newList();

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        BlockTag that = (BlockTag) o;
        return Objects.equals(this.id, that.id)
            && Objects.equals(this.values, that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.id, this.values);
    }

}
