/**
 * The pack rule layer - OptiFine CIT / CTM parsing and evaluation, NBT conditionals,
 * {@code color.properties}, and the Catharsis conventions - consuming
 * {@link lib.minecraft.renderer.pipeline.pack pack}'s pinned types
 * ({@link lib.minecraft.renderer.pipeline.pack.PackStack PackStack},
 * {@link lib.minecraft.renderer.asset.pack.PackId PackId}) without redefining them.
 *
 * <p><b>NBT conditionals.</b> The nbt-factory dependency supplies the tag model
 * ({@code lib.minecraft.nbt.tag.CompoundTag} / {@code ListTag} / {@code NumericalTag}) and the
 * parse surface; this package supplies what its compound-only {@code getPath} cannot -
 * {@link lib.minecraft.renderer.asset.pack.rule.NbtPath NbtPath} walks lists / wildcards /
 * {@code count}, {@link lib.minecraft.renderer.asset.pack.rule.NbtPredicate NbtPredicate} carries
 * the first-class {@code pattern:}/{@code regex:}/{@code range:}/{@code exists:}/{@code raw:}
 * prefixes, and {@code NbtValues} normalizes the three nbt-factory gaps (scalar-literal wrap, SNBT
 * booleans, cross-type numeric equality). {@link lib.minecraft.renderer.pipeline.pack.rule.NbtRule
 * NbtRule} joins a path, a predicate, and the {@code !} negation flag.
 *
 * <p><b>Parity.</b> Vanilla ships no {@code optifine/} tree, so every rule-layer behavior is inert
 * with only vanilla loaded - the byte-parity contract holds without gating. CIT / CTM rule records,
 * {@code RuleSet} stack merge, {@code GlintPolicy}, and the
 * Catharsis condition evaluator land alongside this NBT foundation.
 */
package lib.minecraft.renderer.pipeline.pack.rule;
