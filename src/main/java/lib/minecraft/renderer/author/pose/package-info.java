/**
 * Programmatic pose authoring for the entity style axis.
 *
 * <p>Verbs here speak human units - whole degrees, model pixels, seconds - and capture what an
 * author spells into the unit-agnostic
 * {@link lib.minecraft.renderer.author.pose.PoseScript PoseScript} intermediate form: per-limb
 * stances ({@link lib.minecraft.renderer.author.pose.LimbStance LimbStance}) carrying absolute
 * and additive writes, aim targets and procedural motion, and keyframed timelines
 * ({@link lib.minecraft.renderer.author.pose.Timeline Timeline}).
 * {@link lib.minecraft.renderer.author.pose.Preset Preset} stamps a whole silhouette at once,
 * whole-degree triples the selector enums ({@link lib.minecraft.renderer.author.pose.Side Side},
 * {@link lib.minecraft.renderer.author.pose.Corner Corner},
 * {@link lib.minecraft.renderer.author.pose.Turn Turn},
 * {@link lib.minecraft.renderer.author.pose.Ease Ease}) address limb by limb.
 *
 * <p>Capture is the whole of what the verb surface does: unit conversion, rest rebasing and every
 * refusal happen at compilation against a target row, so one captured script installs on many
 * rows and each row lowers it against its own mesh and rest values.
 * {@link lib.minecraft.renderer.author.pose.StyleDiagnostics StyleDiagnostics} records what
 * authoring decided - recording unconditional, emission opt-in, never load-bearing.
 *
 * <p>Two relationships the shipped rows leave unsaid are derived at compilation and never
 * declared by an author. A tier verb names anatomy, and its stance lands on the articulation the
 * shipped pose turns for that part - the bone itself where the pose writes its rotation, else the
 * nearest ancestor it does - so an equine head verb turns the neck assembly the snout, mane and
 * ears hang from. A bone whose pivot vanilla places by hand where another bone's frame carries
 * it - a sitting wolf's tail and hind legs on its lowered body - is seated on that bone, read off
 * the state silhouettes the row ships, and a stance on the leader carries the follower's pivot
 * with it. Attachment is told from contact by that co-movement and never by adjacency at bind:
 * a contact is measured by {@link lib.minecraft.renderer.author.pose.PoseValidator PoseValidator}
 * against the envelope the shipped styles draw, widened by a silhouette only where it witnesses
 * a seat, and cascades nothing.
 *
 * <p>The package points downward only: it reads the runtime pose vocabulary under
 * {@code lib.minecraft.renderer.asset.pose}, and nothing outside this package reads it back.
 */
package lib.minecraft.renderer.author.pose;
