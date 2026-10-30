/**
 * Decomposing container art into the parts a window paints.
 *
 * <p>{@link lib.minecraft.renderer.bake.gui.chrome.ChromeDecomposition ChromeDecomposition} is what an
 * authored chrome image is made of, in source pixels: four corner rects, four edge bands and a
 * classified interior. Each
 * {@link lib.minecraft.renderer.bake.gui.chrome.ChromeDecomposition.Band Band} repeats a period and
 * carries the {@link lib.minecraft.renderer.bake.gui.chrome.ChromeDecomposition.Feature Feature} runs
 * that break it, each anchored to an end or to the middle, and the
 * {@link lib.minecraft.renderer.bake.gui.chrome.ChromeDecomposition.Interior Interior} class decides how
 * the middle grows.
 *
 * <p>{@link lib.minecraft.renderer.bake.gui.chrome.ChromeSlicer ChromeSlicer} works it both ways:
 * {@code decompose} takes an image apart, deriving its band depths, periods, features and interior
 * class from its own pixels, and {@code assemble} rebuilds the art at a size its author never drew,
 * clamping the bands when the target is narrower than two of them. Nothing in either is
 * vanilla-specific - it serves any container art - and
 * {@link lib.minecraft.renderer.bake.gui.Window.Sliced Window.Sliced} is the window that paints from
 * the result.
 *
 * <p>A type that decomposes nothing does not belong here.
 *
 * <p><b>Parity.</b> Both members declare their own claim, each joining
 * {@link lib.minecraft.renderer.MenuRenderer MenuRenderer}'s.
 */
package lib.minecraft.renderer.bake.gui.chrome;
