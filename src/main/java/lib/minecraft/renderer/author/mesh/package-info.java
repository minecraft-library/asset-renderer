/**
 * Reading a target row's mesh for what a pose may address - the limbs a verb names and the
 * attachments a stance has to carry, answered off the mesh and never off an entity id.
 *
 * <p>{@link lib.minecraft.renderer.author.mesh.LimbRoster LimbRoster} is the legs a mesh declares,
 * grouped into transverse rows front to back. A leg is a bone the mesh names as one; geometry decides
 * only which row and side it sits on, and whatever the resolution could not settle cleanly - a root
 * with no side, a leg named for one side and sitting on the other, a grouper holding too few legs -
 * is carried beside the rows rather than dropped. It resolves a rank and side to one bone, and any leg
 * or family address to the bones it reaches.
 * {@link lib.minecraft.renderer.author.mesh.LimbFamily LimbFamily} is the members of one indexed
 * family - a stem and a running number - in the order the mesh numbers them, and whether they hang
 * off one another as a chain or stand as siblings.
 *
 * <p>{@link lib.minecraft.renderer.author.mesh.Seats Seats} derives which top-level bone rides which
 * other bone's frame, from the state silhouettes a shipped pose places by hand. Attachment is told by
 * two bones moving together across those states, never by their touching at bind, and a seat carries
 * a position and never a rotation.
 *
 * <p>The lowering, the audit and the registrar in the sibling authoring packages read these answers
 * against the row they are working on. A type that reads no mesh does not belong here.
 *
 * <p><b>Parity.</b> Every type here carries its own declaration that it reaches the entity renderer.
 */
package lib.minecraft.renderer.author.mesh;
