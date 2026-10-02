/**
 * Every {@link lib.minecraft.renderer.Renderer Renderer&lt;O&gt;} a caller constructs, and
 * {@code Renderer} itself, the contract they implement - its type parameter bounded by the
 * {@link lib.minecraft.renderer.request.RenderOptions RenderOptions} marker every options bag carries.
 * Every public entry point a caller wires into is a concrete implementation of {@code Renderer}, each
 * one keyed by the {@code options} record it consumes. A type that neither implements
 * {@code Renderer} nor is named by its signature does not belong here.
 *
 * <p><b>The {@link lib.minecraft.renderer.Renderer Renderer&lt;O&gt;} SPI.</b> A single
 * {@code render(options)} method that accepts an immutable {@code options} object and returns
 * an {@code ImageData} - either a {@code StaticImageData} (single PNG frame) or an
 * {@code AnimatedImageData} (multi-frame loop with per-frame delay). Implementations are
 * stateless between calls; all input flows through the options and the shared
 * {@code RendererContext} configured at construction.
 *
 * <p><b>Concrete renderers.</b> Each one lives in this package, takes the matching options
 * record, and is paired with the engine layer that does the heavy lifting.
 * <ul>
 *   <li>{@link lib.minecraft.renderer.AtlasRenderer AtlasRenderer} - bulk render every block and item model
 *       in a {@link lib.minecraft.renderer.port.RendererContext RendererContext} into a single grid image
 *       plus sidecar JSON describing each tile's coordinates.</li>
 *   <li>{@link lib.minecraft.renderer.BlockRenderer BlockRenderer} - vanilla block models composed into
 *       isometric 3D icons or flat 2D faces. Handles biome tints, block-entity composite parts,
 *       and the vanilla {@code display.gui} pose chain.</li>
 *   <li>{@link lib.minecraft.renderer.EntityRenderer EntityRenderer} - mob entities driven by the Java pipeline
 *       ({@code entity_models.json} / {@code entity_geometry.json}, produced by
 *       {@code EntityModelsFlow}). The largest renderer in
 *       the module by surface area - covers procedural loops, overlays, glints, equipment,
 *       and the per-face lighting frame that drives vanilla parity.</li>
 *   <li>{@link lib.minecraft.renderer.FluidRenderer FluidRenderer} - water / lava in isometric 3D (sloped
 *       top, flow-rotated UVs, animation) or as a flat source-face icon.</li>
 *   <li>{@link lib.minecraft.renderer.GridRenderer GridRenderer} - compose a rectangular grid of tiles, each
 *       a static PNG or animated WebP, into one output via
 *       {@link lib.minecraft.renderer.engine.frame.FrameCompositor FrameCompositor}.</li>
 *   <li>{@link lib.minecraft.renderer.ItemRenderer ItemRenderer} - vanilla item models with all the
 *       sub-systems an item icon can carry: durability bar, stack count overlay, enchantment
 *       glint, dyed leather tint, banner-pattern composite, armor-trim palette permutation.</li>
 *   <li>{@link lib.minecraft.renderer.LayoutRenderer LayoutRenderer} - free-form composition of child
 *       renderers (or pre-rendered images) into a single canvas via a
 *       {@link lib.minecraft.renderer.request.LayoutOptions.Layout LayoutOptions.Layout} strategy.</li>
 *   <li>{@link lib.minecraft.renderer.MenuRenderer MenuRenderer} - inventory-style screens (player, chest,
 *       crafting table, anvil) with the vanilla theme chrome and per-slot item icons.</li>
 *   <li>{@link lib.minecraft.renderer.PlayerRenderer PlayerRenderer} - player skin renders at three body
 *       scopes ({@code SKULL}, {@code BUST}, {@code FULL}) and two perspectives, with optional
 *       armor and trim layers.</li>
 *   <li>{@link lib.minecraft.renderer.PortalRenderer PortalRenderer} - end portal and end gateway, both
 *       reproducing vanilla's CPU-baked parallax star-field shader.</li>
 *   <li>{@link lib.minecraft.renderer.TextRenderer TextRenderer} - styled Minecraft text in lore-tooltip
 *       (purple-bordered) or plain-chat mode, with animated output when any segment is
 *       obfuscated ({@code &sect;k}).</li>
 * </ul>
 *
 * <p><b>Where the real work lives.</b> This package is intentionally a thin dispatch surface:
 * <ul>
 *   <li>Geometry building - {@link lib.minecraft.renderer.bake.mesh bake.mesh} (the per-subject kits
 *       that emit a subject's triangles) over {@link lib.minecraft.renderer.engine.mesh engine.mesh}
 *       (turning a box into triangles).</li>
 *   <li>Rasterization - {@link lib.minecraft.renderer.engine engine} (the
 *       {@link lib.minecraft.renderer.engine.raster.Rasterizer Rasterizer} triangle rasterizer and
 *       its {@link lib.minecraft.renderer.engine.camera.Camera Camera} pose value).</li>
 *   <li>Linear algebra - {@link lib.minecraft.renderer.engine.math math} (immutable
 *       {@code Matrix4f}, {@code Vector*}, {@code Quaternionf} with optional Vector API
 *       acceleration).</li>
 *   <li>Asset loading - {@link lib.minecraft.renderer.content content} (client jar acquisition,
 *       resource pack resolution, parsing, and the runtime index behind the context) and
 *       {@link lib.minecraft.renderer.asset asset} (the records it decodes).</li>
 *   <li>Resource generation - {@code tooling} (ASM-driven
 *       regenerators rerun on every Minecraft version bump).</li>
 * </ul>
 *
 * <p><b>Common defaults.</b> {@link lib.minecraft.renderer.request.OutputOptions OutputOptions}
 * carries the shared square-pixel default for single-subject renders. Every subject-scoped options
 * record ({@code BlockOptions}, {@code EntityOptions}, {@code ItemOptions}, {@code PlayerOptions},
 * {@code FluidOptions}, {@code PortalOptions}) composes that one frame, so a caller building with
 * all defaults gets a consistent tile dimension across renderers.
 *
 * <p><b>Parity.</b> Every renderer is a direct member of this package, and what each one reaches is
 * answered per file - a block renderer is under five pipelines because an entity draws a carried
 * block through it, and a portal renderer is under one. What is answered for the package is the
 * subtraction: the pipeline dump serialises what a read layer loaded and never renders, so it is
 * blind to a change here whatever else selects it. The scope stops at this package rather than
 * descending: the packages below carry their own claims, and a subtraction that reached them would
 * strip both dump manifests from every file that reads a pipeline.
 *
 * @see lib.minecraft.renderer.Renderer
 * @see lib.minecraft.renderer.request
 * @see lib.minecraft.renderer.engine
 * @see lib.minecraft.renderer.content
 */
@Parity(claim = "engine-renders", mode = Mode.DEMOTE, scope = Scope.PACKAGE)
package lib.minecraft.renderer;

import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Scope;
