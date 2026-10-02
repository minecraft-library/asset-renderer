/**
 * An entity's bone tree as parsed - the decoded geometry dialect, and nothing that is not a bone, a
 * cube or a face of one.
 *
 * <p>{@link lib.minecraft.renderer.asset.mesh.EntityMesh EntityMesh} lists each entity's bones and
 * their cube geometry in vanilla Java's native frame (Y-down, right-handed): a
 * {@link lib.minecraft.renderer.asset.mesh.EntityMesh.Bone Bone} pivot is parent-relative, matching
 * {@code PartPose.offset}, while a {@link lib.minecraft.renderer.asset.mesh.EntityMesh.Cube Cube}
 * origin and pivot are bone-local.
 * {@link lib.minecraft.renderer.asset.mesh.TextureSize TextureSize} is the atlas the cube UVs resolve
 * against, carried as the dialect's own {@code texture_size:[w, h]} array.
 *
 * <p>The tree is shared by neither {@link lib.minecraft.renderer.asset.Block Block} nor
 * {@link lib.minecraft.renderer.asset.Item Item}, which is why it sits apart from the element models
 * in {@link lib.minecraft.renderer.asset.model asset.model}.
 */
package lib.minecraft.renderer.asset.mesh;
