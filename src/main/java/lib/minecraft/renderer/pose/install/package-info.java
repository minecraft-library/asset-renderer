/**
 * Binding built styles to entity rows and handing the result to the renderer - the one place an
 * authored pose meets a subject.
 *
 * <p>{@link lib.minecraft.renderer.pose.install.StyleRegistrar StyleRegistrar} compiles a style
 * against the target row's own mesh and shipped pose, appends one flat catalog row after the
 * shipped rows, weaves every pose row the runtime evaluates - an overlay pass sharing the body's
 * pose instance follows for free, a pass carrying a distinct row takes its own compile - and hands
 * the mutated definitions to the renderer's existing public constructor, so registration ships no
 * runtime change at all. {@link lib.minecraft.renderer.pose.install.PlayerRig PlayerRig}
 * synthesizes the row the player renders through and
 * {@link lib.minecraft.renderer.pose.install.SkinContext SkinContext} answers its reserved skin
 * ref ahead of every pack.
 * {@link lib.minecraft.renderer.pose.install.PoseEmitter PoseEmitter} spells a woven row back out
 * in the shipped table's own grammar, preserving the graph rather than the tree it stands for.
 *
 * <p><b>An install leaves every other style of the row at its bits.</b> A stance lowers to a field
 * an unselected style rests at zero, and a raw splice rides a gate on the style's own field, so
 * the shipped rows and every previously installed one render exactly as they did before. That is
 * the promise the bit-parity pins in this package's tests ask of every shipped row.
 *
 * <p>Every install guard runs where the author is, replacing the load validation a hand-built row
 * skips, and each refusal carries its context into the compile's diagnostics immediately before it
 * throws. Assembly is single-threaded state ending at the renderer it returns.
 *
 * <p><b>Parity.</b> Nothing this store holds is reached from here: no producer builds a
 * {@code StyleRegistrar}, so every sweep, dump and digest renders the definitions the loader
 * loads, and these packages read that loader without ever being read back. The reference graph
 * answers the empty set for every type below, per file rather than as an assertion over the
 * directory, and the gate that does speak for them is the fast suite - the bit-parity pins under
 * the install package's tests, which evaluate every shipped style of every shipped row through a
 * registrar and hold each to bone-for-bone identical bits.
 *
 * @see lib.minecraft.renderer.pose.install.StyleRegistrar
 * @see lib.minecraft.renderer.pose.install.PoseEmitter
 */
@Parity(claim = "pose-authoring", scope = Scope.SUBTREE)
package lib.minecraft.renderer.pose.install;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Scope;
