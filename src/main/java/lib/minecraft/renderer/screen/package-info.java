/**
 * Drawing in Minecraft's GUI pixel space - every member places and paints Minecraft pixels, and none
 * of them ever sees a triangle.
 *
 * <p>Text is drawn by {@link lib.minecraft.renderer.screen.TextKit TextKit}, with vanilla's shadow,
 * strikethrough, underline and obfuscation, and by its gradient counterpart
 * {@link lib.minecraft.renderer.screen.GradientKit GradientKit};
 * {@link lib.minecraft.renderer.screen.ItemStackKit ItemStackKit} draws the durability bar and stack
 * count over a GUI item icon. Sprites are drawn by
 * {@link lib.minecraft.renderer.screen.NineSliceKit NineSliceKit}, which blits a {@code gui.scaling}
 * sprite into a destination rect, and {@link lib.minecraft.renderer.screen.TooltipChrome TooltipChrome}
 * contributes a tooltip's background and border layers, from the pack's sprite pair or procedurally.
 *
 * <p>A container panel is a {@link lib.minecraft.renderer.screen.Window Window} - its frame, interior
 * fill and slot cells at a caller-chosen size, painted either as vanilla's geometry in one
 * {@link lib.minecraft.renderer.screen.Window.Theme Theme}'s inks or as authored art cut up by
 * {@link lib.minecraft.renderer.screen.Window.Sliced Sliced} through
 * {@link lib.minecraft.renderer.screen.chrome screen.chrome}.
 * {@link lib.minecraft.renderer.screen.MenuLayout MenuLayout} is the arithmetic placing every cell and
 * mark between a screen's metrics and a window; the package-private {@code MarkPainter} paints each
 * {@link lib.minecraft.renderer.vanilla.gui.Mark Mark} a screen places beside its cells, keyed by the
 * mark through one switch with no default; the package-private {@code Stencil} stamps the few
 * pictures measured off shipped art rather than drawn by rule; and
 * {@link lib.minecraft.renderer.screen.TextField TextField} puts the text and caret into a field's well.
 *
 * <p>The refusal is a grep over imports: a type importing
 * {@link lib.minecraft.renderer.engine.raster.Rasterizer Rasterizer},
 * {@link lib.minecraft.renderer.engine.draw.VisibleTriangle VisibleTriangle},
 * {@link lib.minecraft.renderer.engine.camera.Camera Camera}, the engine's
 * {@link lib.minecraft.renderer.engine.geometry.Box Box} or
 * {@link lib.minecraft.renderer.math.Matrix4f Matrix4f} has left GUI pixel space and does not belong
 * here.
 *
 * <p><b>Parity.</b> Everything here draws downstream of the load, and the pipeline dump serialises
 * loaded data without calling a renderer, so the package declares the engine-renders claim as a
 * demotion of the dump's verdict on a change made here. The claim takes the default subtree scope, so
 * it covers {@code screen.chrome} as well.
 */
@Parity(claim = "engine-renders", mode = Mode.DEMOTE, scope = Scope.SUBTREE)
package lib.minecraft.renderer.screen;

import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Scope;
