/**
 * The skeletal pose form - what a model does to its bones before it is drawn, read back off
 * {@code entity_poses.json} rather than reproduced here.
 *
 * <p>{@link lib.minecraft.renderer.asset.pose.EntityPose EntityPose} is one model's whole answer:
 * an expression per bone channel it writes, and the authored clips it plays with the rate and
 * amplitude it plays them at. The expression grammar is
 * {@link lib.minecraft.renderer.asset.pose.PoseExpr PoseExpr} over
 * {@link lib.minecraft.renderer.asset.pose.PoseOperator PoseOperator}, with
 * {@link lib.minecraft.renderer.asset.pose.PosePredicate PosePredicate} deciding a choice the
 * generator could not, and
 * {@link lib.minecraft.renderer.asset.pose.PoseChannel PoseChannel} naming the eleven bone members
 * a pose can write.
 *
 * <p><b>Pose is the skeletal half.</b> An {@code animation} in this renderer is the texture
 * flipbook a {@code .mcmeta} declares, which is a different thing on a different clock, and the two
 * words are kept apart deliberately - see
 * {@link lib.minecraft.renderer.asset.pack.MCMeta.Animation MCMeta.Animation} for that one.
 *
 * <p>Nothing here is shared with the generator that writes the table. The vocabulary travels as
 * tokens rather than as types, so this is a reader's own copy of what the shipped bytes can say and
 * a token outside these rosters is a table this renderer is too old to read.
 */
package lib.minecraft.renderer.asset.pose;
