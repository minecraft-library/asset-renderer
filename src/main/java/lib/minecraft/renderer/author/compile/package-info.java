/**
 * Lowering a built style onto one target row - every unit conversion, every rest rebase and every
 * content refusal of the authoring surface.
 *
 * <p>{@link lib.minecraft.renderer.author.compile.PoseCompiler PoseCompiler} runs the whole lowering
 * against a row's own mesh and shipped pose. Its one principle is that custom movement is additive
 * on fresh driver fields: a splice references the shipped expression instance directly as the
 * first operand of a sum whose second reads a {@code style$}-namespaced field, and an undriven
 * field answers zero, so under every other style of the row the woven graph evaluates to the
 * shipped value. The raw hatch is the one splice that is not a sum and carries the same promise by
 * a gate on the style's own field instead.
 * {@link lib.minecraft.renderer.author.compile.GraphInterner GraphInterner} hash-conses the result
 * so every structurally equal subtree of the row resolves to one instance, and a shipped instance
 * is never swapped or rebuilt.
 *
 * <p>{@link lib.minecraft.renderer.author.compile.FormWalker FormWalker} is the order an install
 * compiles in: a row's body and passes and every form an appearance swaps in for it, each site with
 * the coordinate its fields are spelled under and the guards it owes. It decides which sites there
 * are, and a caller decides what a compile runs over and what an address it reached nothing with
 * means - so the installer that refuses over one and the audit that reports it read one list of
 * sites.
 *
 * <p>Two relationships are derived here that no author spells and no shipped row states outright.
 * An anatomical stance lands on the articulation the pose as it shipped turns for that part, read
 * off the mesh's own parents; and a bone the state silhouettes show riding another bone's frame is
 * seated on it by {@link lib.minecraft.renderer.author.mesh.Seats Seats}, its pivot carried by
 * the leader's held stance as an ordinary additive displacement. A seat is a position and never a
 * rotation, and a pair merely adjacent at bind is a contact rather than a seat.
 *
 * <p>An address names bones rather than listing them, and which walk answers it is the kind of
 * address it is. {@link lib.minecraft.renderer.author.mesh.LimbRoster LimbRoster} answers a leg
 * address off the rows and sides it reads from the mesh's own chain transforms, and
 * {@link lib.minecraft.renderer.author.mesh.LimbFamily LimbFamily} answers a family address off a
 * stem and a running number. Only the first needs a roster, which is why the resolver takes one as
 * a supplier and a script addressing no legs derives none.
 *
 * <p>Units convert exactly once at this boundary - degrees to radians, a bone's pixels across the
 * mesh's flattened factor, a bone's scale passing through as vanilla's own field for the player to
 * cross, seconds untouched - and a refusal is an authoring error rather than a load or render
 * failure, recorded into {@link lib.minecraft.renderer.diagnostic.Diagnostics Diagnostics}
 * immediately before it throws. Recording is unconditional, emission opt-in, and no recorded line
 * renders an expression: the diagnostics speak field, bone, channel and count vocabulary only.
 *
 * <p><b>Parity.</b> Nothing this store holds is reached from here: no producer builds a
 * {@code StyleRegistrar}, so every sweep, dump and digest renders the definitions the loader
 * loads, and these packages read that loader without ever being read back. The reference graph
 * answers the empty set for every type below, per file rather than as an assertion over the
 * directory, and the gate that does speak for them is the fast suite - the bit-parity pins under
 * the install package's tests, which evaluate every shipped style of every shipped row through a
 * registrar and hold each to bone-for-bone identical bits.
 *
 * @see lib.minecraft.renderer.author.compile.PoseCompiler
 * @see lib.minecraft.renderer.author.compile.FormWalker
 * @see lib.minecraft.renderer.author.mesh.Seats
 */
package lib.minecraft.renderer.author.compile;
