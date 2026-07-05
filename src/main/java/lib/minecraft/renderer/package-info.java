/**
 * Top-level renderer API for the {@code asset-renderer} module. Every public entry point a
 * caller wires into is a concrete implementation of {@link lib.minecraft.renderer.Renderer Renderer},
 * each one keyed by the {@code options} record it consumes.
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
 *   <li>{@link lib.minecraft.renderer.AtlasRenderer AtlasRenderer} - bulk render every block / item / entity
 *       in a {@link lib.minecraft.renderer.engine.RendererContext RendererContext} into a single grid image
 *       plus sidecar JSON describing each tile's coordinates.</li>
 *   <li>{@link lib.minecraft.renderer.BlockRenderer BlockRenderer} - vanilla block models composed into
 *       isometric 3D icons or flat 2D faces. Handles biome tints, block-entity composite parts,
 *       and the vanilla {@code display.gui} pose chain.</li>
 *   <li>{@link lib.minecraft.renderer.EntityRenderer EntityRenderer} - mob entities driven by the Java pipeline
 *       ({@code entity_models.json} / {@code entity_geometry.json}, produced by
 *       {@link lib.minecraft.renderer.tooling.ToolingEntityModels ToolingEntityModels}). The largest renderer in
 *       the module by surface area - covers procedural loops, overlays, glints, equipment,
 *       and the per-face lighting frame that drives vanilla parity.</li>
 *   <li>{@link lib.minecraft.renderer.FluidRenderer FluidRenderer} - water / lava in isometric 3D (sloped
 *       top, flow-rotated UVs, animation) or as a flat source-face icon.</li>
 *   <li>{@link lib.minecraft.renderer.GridRenderer GridRenderer} - compose a rectangular grid of tiles, each
 *       a static PNG or animated WebP, into one output via
 *       {@link lib.minecraft.renderer.engine.compose.FrameCompositor FrameCompositor}.</li>
 *   <li>{@link lib.minecraft.renderer.ItemRenderer ItemRenderer} - vanilla item models with all the
 *       sub-systems an item icon can carry: durability bar, stack count overlay, enchantment
 *       glint, dyed leather tint, banner-pattern composite, armor-trim palette permutation.</li>
 *   <li>{@link lib.minecraft.renderer.LayoutRenderer LayoutRenderer} - free-form composition of child
 *       renderers (or pre-rendered images) into a single canvas via a
 *       {@link lib.minecraft.renderer.option.LayoutOptions.Layout LayoutOptions.Layout} strategy.</li>
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
 *   <li>Geometry building - {@link lib.minecraft.renderer.engine.kit kit} (per-asset-type kits) and
 *       {@link lib.minecraft.renderer.face face} (face-unwrap enums).</li>
 *   <li>Rasterization - {@link lib.minecraft.renderer.engine engine} (the
 *       {@link lib.minecraft.renderer.engine.ModelEngine ModelEngine} triangle rasterizer and
 *       its {@link lib.minecraft.renderer.engine.camera.Camera Camera} pose value).</li>
 *   <li>Linear algebra - {@link lib.minecraft.renderer.tensor tensor} (immutable
 *       {@code Matrix4f}, {@code Vector*}, {@code Quaternionf} with optional Vector API
 *       acceleration).</li>
 *   <li>Asset loading - {@link lib.minecraft.renderer.pipeline pipeline} (resource pack and
 *       client jar acquisition; JSON / NBT / mcmeta parsing) and
 *       {@link lib.minecraft.renderer.asset asset} (the DTOs the pipeline produces).</li>
 *   <li>Resource generation - {@link lib.minecraft.renderer.tooling tooling} (ASM-driven
 *       regenerators rerun on every Minecraft version bump).</li>
 * </ul>
 *
 * <p><b>Common defaults.</b> {@link lib.minecraft.renderer.Renderer#DEFAULT_OUTPUT_SIZE Renderer#DEFAULT_OUTPUT_SIZE} is the
 * shared square-pixel default for single-subject renders. Every subject-scoped options record
 * ({@code BlockOptions}, {@code EntityOptions}, {@code ItemOptions}, {@code PlayerOptions},
 * {@code FluidOptions}, {@code PortalOptions}) picks it up so a caller building with all defaults
 * gets a consistent tile dimension across renderers.
 *
 * @see lib.minecraft.renderer.Renderer
 * @see lib.minecraft.renderer.option
 * @see lib.minecraft.renderer.engine
 * @see lib.minecraft.renderer.pipeline
 */
package lib.minecraft.renderer;
