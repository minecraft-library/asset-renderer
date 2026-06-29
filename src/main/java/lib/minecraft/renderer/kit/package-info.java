/**
 * Per-asset-type geometry and composition kits. Each kit owns the vanilla-parity logic for one
 * narrowly scoped rendering concern - building a cube's twelve triangles, scrolling a glint
 * texture, palette-permuting an armor trim - so that the
 * {@link lib.minecraft.renderer.Renderer renderer} layer can stay a thin dispatch surface and
 * the {@link lib.minecraft.renderer.engine.ModelEngine engine} layer never sees asset-shaped
 * data.
 *
 * <p><b>Geometry kits.</b> Build the triangle lists that the engine rasterizes. Each consumes
 * a parsed asset DTO and emits a {@code ConcurrentList<VisibleTriangle>} ready for
 * {@code ModelEngine.rasterize}.
 * <ul>
 *   <li>{@link lib.minecraft.renderer.kit.BlockGeometryKit BlockGeometryKit} - the {@code block/*.json}
 *       element list builder. Walks {@code from / to / faces} entries, applies element-level
 *       rotation, and emits the six cardinal cube faces with vanilla's
 *       {@link lib.minecraft.renderer.geometry.BlockFace BlockFace} UV unwrap and
 *       {@code AmbientOcclusionFace} lighting scalar baked in.</li>
 *   <li>{@link lib.minecraft.renderer.kit.EntityGeometryKit EntityGeometryKit} - the entity {@code ModelPart}
 *       chain builder. Consumes the
 *       {@link lib.minecraft.renderer.tooling.ToolingEntityModels ToolingEntityModels}-produced
 *       {@code entity_geometry.json} and walks bone hierarchies, cube inflations, pivot
 *       rotations, and {@link lib.minecraft.renderer.geometry.EntityFace EntityFace} UV
 *       strips. Carries the per-face lighting frame {@code L_kit = FLIP_Y * M_view^T *
 *       L_camera} that drives the cardinal-shaded entity parity.</li>
 *   <li>{@link lib.minecraft.renderer.kit.FluidGeometryKit FluidGeometryKit} - vanilla fluid cube (1x1x1 with
 *       optional sloped top, flow-rotated side UVs). Fluid geometry is entirely algorithmic in
 *       vanilla - there is no {@code block/water.json} element list to walk.</li>
 * </ul>
 *
 * <p><b>Texture composition kits.</b> Build or transform the texture that a renderer will then
 * sample, without touching the geometry layer.
 * <ul>
 *   <li>{@link lib.minecraft.renderer.kit.ArmorKit ArmorKit} - composites the four humanoid armor
 *       layers (helmet, chestplate, leggings, boots) for either 3D body-inflated cubes or 2D
 *       texture overlays. Pairs with the matching {@code SkinFace} UV layout so atlas crops
 *       work directly on the armor texture.</li>
 *   <li>{@link lib.minecraft.renderer.kit.BannerKit BannerKit} - banner / shield pattern stack
 *       compositor. Matches vanilla's
 *       {@code net.minecraft.client.renderer.BannerRenderer}: start with the base dye, then
 *       for each layer blit the grayscale pattern tinted with the layer's dye colour using
 *       straight-alpha compositing.</li>
 *   <li>{@link lib.minecraft.renderer.kit.GlintKit GlintKit} - enchantment glint animator. Reproduces
 *       vanilla's {@code setupGlintTexturing(float)} translate / rotate / scale per-frame
 *       chain, including the texture split between
 *       {@code enchanted_glint_item.png} and {@code enchanted_glint_armor.png}.</li>
 *   <li>{@link lib.minecraft.renderer.kit.TrimKit TrimKit} - armor trim palette permutation. Maps the
 *       grayscale trim pattern's pixel values through the per-material palette texture, the
 *       algorithm vanilla's
 *       {@code net.minecraft.client.renderer.texture.atlas.sources.PalettedPermutations}
 *       ships.</li>
 *   <li>{@link lib.minecraft.renderer.kit.AnimationKit AnimationKit} - {@code .mcmeta} frame strip
 *       playback (vertically stacked frames, per-entry duration override, optional linear
 *       interpolation between adjacent frames).</li>
 *   <li>{@link lib.minecraft.renderer.kit.RedstoneKit RedstoneKit} - the 16-entry redstone-wire-by-power
 *       ARGB table from {@code RedstoneWireBlock.COLORS}, plus a context-aware resolver that
 *       honours {@code optifine/color.properties}'s {@code redstone.0..redstone.15}
 *       overrides.</li>
 *   <li>{@link lib.minecraft.renderer.kit.ItemStackKit ItemStackKit} - durability bar and stack count
 *       overlay drawn on top of a GUI item icon. Operates in 16-pixel logical space and scales
 *       to the buffer size automatically.</li>
 * </ul>
 *
 * <p><b>Text and composition kits.</b>
 * <ul>
 *   <li>{@link lib.minecraft.renderer.kit.TextKit TextKit} - styled Minecraft text rendering: font
 *       style resolution, vanilla colour mapping, drop shadow, strikethrough, underline. Wraps
 *       a {@code MinecraftGraphics} that owns the supersampling factor.</li>
 *   <li>{@link lib.minecraft.renderer.kit.ObfuscationKit ObfuscationKit} - per-frame deterministic glyph
 *       substitution for {@code &sect;k} obfuscated runs. Seeds the random stream on frame
 *       index so animated output is reproducible across runs.</li>
 *   <li>{@link lib.minecraft.renderer.compose.FrameCompositor FrameCompositor} - composes static and animated layers
 *       into a single output, computing a merged loop period (LCM of animated layers, capped
 *       at 10 seconds) and sampling each layer at the correct time offset for every output
 *       frame.</li>
 * </ul>
 *
 * <p><b>Vanilla pairings.</b> Every kit's class-level javadoc names the vanilla class it
 * mirrors (e.g. {@code BannerRenderer}, {@code RedstoneWireBlock}, {@code PalettedPermutations})
 * and, where the algorithm is non-trivial, reproduces the relevant vanilla snippet inline
 * inside {@code <pre>{@code ...}</pre>} block. Constants are transcribed byte-for-byte from
 * the MC 26.1 deobfuscated client source so a side-by-side diff against vanilla is the
 * intended audit path.
 *
 * @see lib.minecraft.renderer.geometry
 * @see lib.minecraft.renderer.engine
 * @see lib.minecraft.renderer.tooling.ToolingEntityModels
 */
package lib.minecraft.renderer.kit;
