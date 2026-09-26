/**
 * The value predicates a pack rule matches with.
 *
 * <p>The numeric ones are {@link lib.minecraft.renderer.asset.rule.filter.IntRange IntRange}, one
 * inclusive range - the atom of the {@code range:}, damage, stack-size and enchantment-level filters -
 * and {@link lib.minecraft.renderer.asset.rule.filter.IntRanges IntRanges}, the grammar's
 * list-of-values-or-ranges form, which contains a value when any entry does.
 *
 * <p>The NBT ones answer a CIT {@code nbt.<path>=<predicate>} condition, which
 * {@link lib.minecraft.renderer.asset.rule.filter.NbtRule NbtRule} is whole: an
 * {@link lib.minecraft.renderer.asset.rule.filter.NbtPath NbtPath} selecting the leaves the condition
 * is about, walking keys, indices, wildcards and counts where a compound-only path cannot; an
 * {@link lib.minecraft.renderer.asset.rule.filter.NbtPredicate NbtPredicate} testing them, one variant
 * per first-class prefix of the grammar ({@code pattern:}, {@code regex:}, {@code range:},
 * {@code exists:}, {@code raw:}) beside the exact-value test; and the {@code !} negation.
 * {@link lib.minecraft.renderer.asset.rule.filter.NbtValues NbtValues} is the comparison those tests
 * go through - a rule literal parsed to a typed scalar, SNBT booleans coerced, numbers equal across
 * their widths, and a reached branch printed back as SNBT for {@code raw:}.
 *
 * <p>The rules in {@link lib.minecraft.renderer.asset.rule asset.rule} hold these as the filters they
 * match with. A type that is not a predicate over a value does not belong here.
 */
package lib.minecraft.renderer.asset.rule.filter;
