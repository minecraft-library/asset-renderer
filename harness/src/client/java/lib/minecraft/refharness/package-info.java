/**
 * The reference harness: a headless Fabric mod that drives the real Minecraft client to produce the
 * byte-stable ground-truth PNGs every parity sweep is diffed against.
 *
 * <p>{@link lib.minecraft.refharness.RefHarnessClient RefHarnessClient} is the entry point,
 * {@link lib.minecraft.refharness.RefHarnessRenderer RefHarnessRenderer} advances one sweep step per
 * client tick, and {@link lib.minecraft.refharness.HarnessMode HarnessMode} says which sweeps a run
 * covers. Below it sit the sweeps themselves, the frame renderers they draw through, the offscreen
 * target every render lands in, and the mixins that let a headless boot reach any of it.
 *
 * <p><b>Parity.</b> This is the producer of the reference tree, so a change to a frame renderer or
 * the bounds walker moves the bytes every sweep compares against - and moves them for sweeps nobody
 * re-ran, which is how stale ground truth was left on disk twice. Only the whole-tree render
 * refreshes all of it, so a partial refresh is the failure mode rather than the fix, and a reference
 * that moves on a re-render with the change stashed was stale rather than reached. The renderer
 * compiles none of this itself - this is its own Gradle build with its own wrapper - so its fast
 * suite passes over a harness that does not compile, and the task that shells into this wrapper is
 * what closes that.
 *
 * @see lib.minecraft.refharness.RefHarnessRenderer
 * @see lib.minecraft.refharness.HarnessMode
 */
@Parity(claim = "harness-render")
package lib.minecraft.refharness;

import lib.minecraft.renderer.parity.Parity;
