/**
 * The pose language - the arithmetic a shipped pose is written in, knowing neither a file nor an
 * entity.
 *
 * <p>{@link lib.minecraft.renderer.pose.PoseExpr PoseExpr} is one value a bone channel is decided
 * by, over the operations {@link lib.minecraft.renderer.pose.PoseOperator PoseOperator} names and
 * the widths each computes at, with
 * {@link lib.minecraft.renderer.pose.PosePredicate PosePredicate} deciding a choice the generator
 * could not fold away. {@link lib.minecraft.renderer.pose.PoseChannel PoseChannel} names the bone
 * members an expression may be written for, and
 * {@link lib.minecraft.renderer.pose.MotionSource MotionSource} what drives a clip that plays.
 * The two the graph is built from are one vocabulary under
 * {@link lib.minecraft.renderer.pose.PoseNode PoseNode}, which is what a walk that has to name the
 * thing it is standing on names.
 *
 * <p><b>A pose is a graph, never a tree.</b> One instance stands for as many paths as reach it - a
 * humanoid's arms are nine hundred nodes standing for twenty-two million - so every walk over one
 * memoizes on node identity and short-circuits on an instance already seen. That binds evaluation,
 * interning, every scan the compiler runs and the text form each arm prints: a node names itself
 * and refers to its children rather than rendering them.
 *
 * <p>Nothing here is shared with the generator that writes the table. The vocabulary travels as
 * tokens rather than as types, so this is a reader's own copy of what the shipped bytes can say,
 * and a token outside these rosters is a table this renderer is too old to read.
 *
 * <p>The package points nowhere: it names no loaded record, no mesh and no entity, which is what
 * lets everything above it - the loaded pose under {@code lib.minecraft.renderer.asset.pose}, the
 * evaluator, and the authoring stack in the packages beside it - depend on it and not the reverse.
 *
 * <p><b>Parity.</b> The arithmetic decides where every bone of every posed subject goes, so this
 * package reaches each artifact that draws one, and both dumps besides, which carry the loaded
 * expressions as serialised data. Which of them each type here reaches is answered per file off the
 * reference graph rather than asserted for the package. Nothing is subtracted: no artifact has been
 * measured blind to a change in the arithmetic, and a demotion nobody measured would be a claim
 * rather than a reading.
 *
 * @see lib.minecraft.renderer.pose.PoseExpr
 * @see lib.minecraft.renderer.pose.PoseOperator
 * @see lib.minecraft.renderer.pose.PoseChannel
 */
@Parity(claim = "pose-vocabulary", scope = Scope.SUBTREE)
package lib.minecraft.renderer.pose;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Scope;
