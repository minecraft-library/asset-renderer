/**
 * The human verb surface of pose authoring - what an author spells, captured and nothing more.
 *
 * <p>Verbs here speak human units: whole degrees, model pixels, seconds. A chain opens at
 * {@link lib.minecraft.renderer.pose.author.Poses Poses} on one of three tiers - the canonical
 * seven of {@link lib.minecraft.renderer.pose.author.HumanoidPose HumanoidPose}, the
 * head-body-legs-tail roster of
 * {@link lib.minecraft.renderer.pose.author.LeggedPose LeggedPose}, or
 * {@link lib.minecraft.renderer.pose.author.CustomPose CustomPose}, which names no anatomy and
 * carries the raw expression hatch - over the shared tail
 * {@link lib.minecraft.renderer.pose.author.PoseBuilder PoseBuilder} owns, whose
 * {@code bone(name, stance)} reaches a part outside any tier's roster by its mesh name and whose
 * {@code family(stem, stance)} reaches a numbered set of them by the one word they share.
 * {@link lib.minecraft.renderer.pose.author.LimbStance LimbStance} carries the per-limb writes,
 * aim targets and procedural motion, {@link lib.minecraft.renderer.pose.author.Keyframes Keyframes}
 * the keyframes, {@link lib.minecraft.renderer.pose.author.Gait Gait} one walking cycle over
 * however many legs a mesh answers with, and
 * {@link lib.minecraft.renderer.pose.author.Preset Preset} a whole silhouette
 * at once, addressed limb by limb through
 * {@link lib.minecraft.renderer.pose.author.Side Side},
 * {@link lib.minecraft.renderer.pose.author.Rank Rank},
 * {@link lib.minecraft.renderer.pose.author.Turn Turn} and
 * {@link lib.minecraft.renderer.pose.author.Ease Ease}.
 *
 * <p><b>Capture is the whole of what this package does.</b> A chain snapshots into the
 * unit-agnostic {@link lib.minecraft.renderer.pose.author.PoseScript PoseScript} and the portable
 * {@link lib.minecraft.renderer.pose.author.BuiltStyle BuiltStyle}, which knows no entity: unit
 * conversion, rest rebasing and every content refusal wait for a target row. That is what lets one
 * built value install on many rows, each lowering it against its own mesh and rest values.
 *
 * <p>The package points downward only - it reads the pose language and the loaded pose, and
 * nothing under {@code asset}, {@code engine}, {@code pipeline} or {@code option} reads it back.
 *
 * <p><b>Parity.</b> Nothing this store holds is reached from here: no producer builds a
 * {@code StyleRegistrar}, so every sweep, dump and digest renders the definitions the loader
 * loads, and these packages read that loader without ever being read back. The reference graph
 * answers the empty set for every type below, per file rather than as an assertion over the
 * directory, and the gate that does speak for them is the fast suite - the bit-parity pins under
 * the install package's tests, which evaluate every shipped style of every shipped row through a
 * registrar and hold each to bone-for-bone identical bits.
 *
 * @see lib.minecraft.renderer.pose.author.Poses
 * @see lib.minecraft.renderer.pose.author.BuiltStyle
 */
@Parity(claim = "pose-authoring", scope = Scope.SUBTREE)
package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Scope;
