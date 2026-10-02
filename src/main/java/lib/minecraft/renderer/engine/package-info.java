/**
 * The rendering machine and the vocabulary it is written in - every type that takes part in a render,
 * as a value the render passes, the machinery that consumes or produces one, or the buffer it lands
 * in, and that does its whole job without knowing which Minecraft subject it is drawing.
 *
 * <p>The package declares no type of its own. Its sub-packages are cut by what a member consumes and
 * what it yields:
 * <ul>
 *   <li><b>Vocabulary.</b> {@link lib.minecraft.renderer.engine.geometry geometry} holds the values a
 *       mesh and its projection are written in, {@link lib.minecraft.renderer.engine.draw draw} the draw
 *       list a rasterizer consumes, {@link lib.minecraft.renderer.engine.layer layer} a stack of
 *       caller-splicable contributions and the fold that applies them to one target, and
 *       {@link lib.minecraft.renderer.engine.pose pose} the expression language a pose is written in and
 *       the evaluator that samples it at a tick.</li>
 *   <li><b>Machinery.</b> {@link lib.minecraft.renderer.engine.mesh mesh} turns a box into triangles,
 *       {@link lib.minecraft.renderer.engine.light light} a lighting frame and a normal into a shade
 *       factor, {@link lib.minecraft.renderer.engine.camera camera} decides where the subject is seen
 *       from and how the result is fitted into a buffer,
 *       {@link lib.minecraft.renderer.engine.texture texture} produces a pixel buffer out of pixel
 *       buffers, {@link lib.minecraft.renderer.engine.raster raster} turns one draw list into one pixel
 *       buffer, and {@link lib.minecraft.renderer.engine.frame frame} turns rasterized buffers into one
 *       still or timed image.</li>
 * </ul>
 *
 * <p>A type no render reaches does not belong anywhere under here - nothing a {@code render(...)} call
 * constructs, calls, passes or returns. Neither does one whose code, imports and javadoc aside, names a
 * Minecraft subject - a block, an item, an entity, a {@code minecraft:} id or a vanilla class - or one
 * that imports from {@code vanilla}, {@code asset}, {@code request}, {@code port}, {@code content},
 * {@code bake} or the root package. A type that passes both still belongs elsewhere if
 * it extends {@code Throwable}, reports, computes on numbers alone, implements {@code LayerSlot} or
 * implements {@code Renderer}, and so does one that reads or writes bytes, one the caller constructs,
 * or one holding mutable state that outlives the call.
 *
 * <p><b>Vanilla parity.</b> The triangle rasterizer reproduces vanilla's CPU-side vertex chain
 * bit-for-bit at the per-vertex level (verified by {@code [PX] TRI} per-vertex dumps against the
 * vanilla reference harness) and applies hardware-style conventions at the per-pixel level -
 * {@code 1/256} fixed-point edge functions, top-left fill, {@code 1/400} coverage snap. The snap is
 * documented at length on {@link lib.minecraft.renderer.engine.raster.Rasterizer Rasterizer}; it is the
 * deterministic cheap workaround for hardware-specific GPU coverage that cannot be bit-reproduced in
 * software at any reasonable cost.
 *
 * <p><b>Parity.</b> Everything here is a render, and which render is answered per file: the pose
 * evaluator is an entity render where the rasterizer is every render, so a change to the former costs
 * the entity sweeps and not a fluid manifest. The pipeline dump is the exception, and every
 * sub-package but {@link lib.minecraft.renderer.engine.pose pose} declares that exception for its own
 * subtree - the dump serialises what a read layer loaded and never calls a renderer, so an identical
 * dump says nothing about a change made there. The pose language is the part of the engine the dump
 * does write: every posed entity's digest spells its channel, operator and drive tokens, so that
 * package declares no demotion of its own.
 *
 * @see lib.minecraft.renderer.engine.raster.Rasterizer
 * @see lib.minecraft.renderer.engine.draw.VisibleTriangle
 * @see lib.minecraft.renderer.engine.frame.Timeline
 */
package lib.minecraft.renderer.engine;
