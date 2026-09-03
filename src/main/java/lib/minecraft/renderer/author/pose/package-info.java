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
 * <p>The package points downward only: it reads the runtime pose vocabulary under
 * {@code lib.minecraft.renderer.asset.pose}, and nothing outside this package reads it back.
 */
package lib.minecraft.renderer.author.pose;
