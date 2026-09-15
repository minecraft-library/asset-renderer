/**
 * Measuring a built style against one target row - what a chain does to the pairs of bones the
 * shipped styles already draw.
 *
 * <p>{@link lib.minecraft.renderer.pose.audit.PoseAuditor PoseAuditor} compiles the style,
 * evaluates the woven pose across the row's period, and reports every bind-adjacent pair whose
 * clearance leaves the envelope the shipped styles define, as a
 * {@link lib.minecraft.renderer.pose.audit.PoseAudit PoseAudit} of findings a caller reads.
 *
 * <p><b>The envelope is the row's own known-good motion, widened by a state silhouette only where
 * that silhouette witnesses a seat.</b> So a placement vanilla itself draws reads as no fault,
 * while the same numbers on a row carrying no silhouette read as the split or overlap they are.
 * A finding names a pair and a clearance and nothing else: attachment is told from contact by
 * co-movement rather than by adjacency at bind, and the audit cascades nothing.
 *
 * <p>Analysis rather than lowering - it runs the compiler and reads what comes back, and no
 * installed row depends on it having run.
 *
 * <p><b>Parity.</b> Nothing this store holds is reached from here: no producer builds a
 * {@code StyleRegistrar}, so every sweep, dump and digest renders the definitions the loader
 * loads, and these packages read that loader without ever being read back. The reference graph
 * answers the empty set for every type below, per file rather than as an assertion over the
 * directory, and the gate that does speak for them is the fast suite - the bit-parity pins under
 * the install package's tests, which evaluate every shipped style of every shipped row through a
 * registrar and hold each to bone-for-bone identical bits.
 *
 * @see lib.minecraft.renderer.pose.audit.PoseAuditor
 * @see lib.minecraft.renderer.pose.audit.PoseAudit
 */
@Parity(claim = "pose-authoring", scope = Scope.SUBTREE)
package lib.minecraft.renderer.pose.audit;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Scope;
