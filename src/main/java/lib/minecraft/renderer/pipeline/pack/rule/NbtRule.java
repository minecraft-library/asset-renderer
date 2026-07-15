package lib.minecraft.renderer.pipeline.pack.rule;

import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.Tag;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * One CIT {@code nbt.<path>=<predicate>} condition: a path, the value test its reached leaves must
 * satisfy, and the OptiFine {@code !} negation flag.
 *
 * @param path the selector
 * @param predicate the value test
 * @param negated whether a {@code !} prefix inverts the result
 */
public record NbtRule(@NotNull NbtPath path, @NotNull NbtPredicate predicate, boolean negated) {

    /**
     * Whether an item's NBT satisfies this condition.
     *
     * @param root the item's root compound
     * @return {@code true} when the predicate (after negation) is satisfied
     */
    public boolean matches(@NotNull CompoundTag root) {
        List<Tag<?>> leaves = this.path.walk(root).toList();
        return this.negated != this.predicate.matches(leaves);
    }

}
