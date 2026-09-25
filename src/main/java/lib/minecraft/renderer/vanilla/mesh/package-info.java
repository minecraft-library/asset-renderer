/**
 * Meshes and bone lattices vanilla declares in code rather than in a file - geometry a model class
 * spells out box by box, transcribed one for one from the client code that declares it.
 *
 * <p>Three are worn and held meshes. {@link lib.minecraft.renderer.vanilla.mesh.ShieldMesh ShieldMesh}
 * is the shield's plate and handle on their shared 64x64 atlas,
 * {@link lib.minecraft.renderer.vanilla.mesh.ElytraMesh ElytraMesh} the two elytra wings at adult and
 * baby scale, and {@link lib.minecraft.renderer.vanilla.mesh.CapeMesh CapeMesh} the one box the
 * player's cape is cut from - its extent and its atlas origin on the cape sheet.
 *
 * <p>The player is a lattice rather than a mesh.
 * {@link lib.minecraft.renderer.vanilla.mesh.HumanoidPart HumanoidPart} is the six boxes a humanoid
 * player is built from - each one's pixel extent, the bone name it answers to and the skin regions its
 * base and overlay faces are read out of - and
 * {@link lib.minecraft.renderer.vanilla.mesh.PlayerLattice PlayerLattice} the body scopes that draw
 * them: which parts each scope draws, the scale one skin pixel spans in its frame, where each part is
 * seated, and which armour slots dress each part.
 *
 * <p>A member a loader can produce does not belong here: geometry a pack or a shipped table supplies is
 * decoded, not declared.
 *
 * <p><b>Parity.</b> Every member declares its own claims.
 */
package lib.minecraft.renderer.vanilla.mesh;
