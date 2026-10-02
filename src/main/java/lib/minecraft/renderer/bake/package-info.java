/**
 * Turning a decoded record and the caller's request into what a renderer draws - the per-subject kits
 * that stand between {@link lib.minecraft.renderer.asset asset}, what a run decodes, and
 * {@link lib.minecraft.renderer.engine engine}, how pixels are made.
 *
 * <p><b>Sub-packages.</b> The package declares no type of its own; the work is cut by what each kit
 * emits.
 * <ul>
 *   <li>{@link lib.minecraft.renderer.bake.mesh mesh} - a subject's triangles, walked from a decoded
 *       model, mesh or algorithmic shape to the draw list a rasterizer consumes.</li>
 *   <li>{@link lib.minecraft.renderer.bake.texture texture} - the pixel buffers a subject's triangles
 *       sample, composed at render time out of the sprites a pack ships.</li>
 *   <li>{@link lib.minecraft.renderer.bake.pose pose} - a pose or a clip played onto a decoded mesh,
 *       the bones a subject holds at one instant.</li>
 *   <li>{@link lib.minecraft.renderer.bake.armor armor} - the layers worn over a subject, and the flat
 *       player whose composite draws the body they sit on.</li>
 *   <li>{@link lib.minecraft.renderer.bake.gui gui} - drawing in Minecraft's GUI pixel space: text,
 *       windows, menu layout and tooltip chrome.</li>
 * </ul>
 *
 * <p>Every kit names a Minecraft subject, which is what keeps it out of
 * {@link lib.minecraft.renderer.engine engine}, whose members name none.
 *
 * <p><b>Parity.</b> The package declares no claim; its sub-packages and their members declare their
 * own.
 */
package lib.minecraft.renderer.bake;
