/**
 * Decomposing a pack's GUI sprite into the parts a window paints.
 *
 * <p>{@link lib.minecraft.renderer.bake.gui.chrome.ChromeDecomposition ChromeDecomposition} is what an
 * authored chrome image is made of, in source pixels: four corner rects, four edge bands and a
 * classified interior. Each
 * {@link lib.minecraft.renderer.bake.gui.chrome.ChromeDecomposition.Band Band} repeats a period and
 * carries the {@link lib.minecraft.renderer.bake.gui.chrome.ChromeDecomposition.Feature Feature} runs
 * that break it, each anchored to an end or to the middle; the
 * {@link lib.minecraft.renderer.bake.gui.chrome.ChromeDecomposition.Interior Interior} class decides how
 * the middle grows; and a
 * {@link lib.minecraft.renderer.bake.gui.chrome.ChromeDecomposition.Border Border} is the nine-slice
 * border a sprite's sidecar declares, which supplies the four band depths outright wherever the
 * client's own validation would load it.
 *
 * <p>{@link lib.minecraft.renderer.bake.gui.chrome.ChromeSlicer ChromeSlicer} works it both ways:
 * {@code decompose} takes an image apart, deriving the periods, features and interior class inside
 * whatever depths are in force, and {@code assemble} rebuilds the art at a size its author never drew,
 * clamping the bands the way the client does when the target is narrower than two of them. Nothing in
 * either is vanilla-specific - it serves container backgrounds, which declare no border, as readily
 * as sprites whose sidecar declares one - and
 * {@link lib.minecraft.renderer.bake.gui.Window.Sliced Window.Sliced} is the window that paints from
 * the result.
 *
 * <p>A type that decomposes nothing does not belong here.
 *
 * <p><b>Parity.</b> Both members declare their own claim, each joining
 * {@link lib.minecraft.renderer.MenuRenderer MenuRenderer}'s.
 */
package lib.minecraft.renderer.bake.gui.chrome;
