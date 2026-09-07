/**
 * The skeletal pose an asset holds - what a model does to its bones before it is drawn, read back
 * off {@code entity_poses.json} rather than reproduced here.
 *
 * <p>{@link lib.minecraft.renderer.asset.pose.EntityPose EntityPose} is one model's whole answer:
 * an expression per bone channel it writes, the authored
 * {@link lib.minecraft.renderer.asset.pose.PoseClip clips} it plays with the rate and amplitude it
 * plays them at, and the resting silhouette of each state branch it poses
 * ({@link lib.minecraft.renderer.asset.pose.EntityPose.Silhouette EntityPose.Silhouette}) -
 * carried beside the pose for authoring to read, and read by nothing at render.
 * {@link lib.minecraft.renderer.asset.pose.StyleCatalog StyleCatalog} holds the flat
 * {@link lib.minecraft.renderer.asset.pose.PoseStyle rows} a caller selects between, each driving
 * its fields through a {@link lib.minecraft.renderer.asset.pose.StyleDriver StyleDriver}, and
 * {@link lib.minecraft.renderer.asset.pose.Drawn Drawn} pairs a mesh with the pose that moves it
 * at draw time.
 *
 * <p>These are records a loader constructs and an asset stores. The arithmetic they are written
 * in is not here: the expression grammar, its operators, its channels and its motion sources are
 * the pose language under {@code lib.minecraft.renderer.pose}, which knows neither a file nor an
 * entity and which this package depends on downward.
 *
 * <p><b>Pose is the skeletal half.</b> An {@code animation} in this renderer is the texture
 * flipbook a {@code .mcmeta} declares, which is a different thing on a different clock, and the two
 * words are kept apart deliberately - see
 * {@link lib.minecraft.renderer.asset.pack.MCMeta.Animation MCMeta.Animation} for that one.
 *
 * @see lib.minecraft.renderer.asset.pose.EntityPose
 * @see lib.minecraft.renderer.asset.pose.StyleCatalog
 */
package lib.minecraft.renderer.asset.pose;
