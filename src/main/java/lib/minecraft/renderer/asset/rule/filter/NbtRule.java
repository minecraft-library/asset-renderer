package lib.minecraft.renderer.asset.rule.filter;

import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.Tag;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One CIT {@code nbt.<path>=<predicate>} condition: a path, the value test its reached leaves must
 * satisfy, and the OptiFine {@code !} negation flag.
 *
 * @param path the selector
 * @param predicate the value test
 * @param negated whether a {@code !} prefix inverts the result
 */
@Parity(claim = "cit-grammar", mode = Mode.DEMOTE)
public record NbtRule(@NotNull NbtPath path, @NotNull NbtPredicate predicate, boolean negated) {

    /** The refusals already reported, so a condition refused on every render is named once. */
    private static final @NotNull Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /** The most refusals reported in one process, which keeps what a stream of packs can make it remember bounded. */
    private static final int MAX_REPORTED = 256;

    /**
     * Whether an item's NBT satisfies this condition.
     * <p>
     * A condition whose glob or regex cannot be matched against the item's text within its budget
     * holds for no item, negated or not: the rule it belongs to fails closed, as one that does not
     * parse does, and the refusal is reported on standard error the first time it is met.
     *
     * @param root the item's root compound
     * @return {@code true} when the predicate (after negation) is satisfied
     */
    public boolean matches(@NotNull CompoundTag root) {
        List<Tag<?>> leaves = this.path.walk(root).toList();

        try {
            return this.negated != this.predicate.matches(leaves);
        } catch (ContentException ex) {
            if (REPORTED.size() < MAX_REPORTED && REPORTED.add(ex.getMessage()))
                System.err.printf("Rule condition refused: %s%n", ex.getMessage());
            return false;
        }
    }

}
