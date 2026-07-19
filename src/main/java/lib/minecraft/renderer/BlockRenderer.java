package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.AnimationData;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockStateKey;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.engine.RasterEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.LightingFrame;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.camera.View;
import lib.minecraft.renderer.engine.compose.RasterPass;
import lib.minecraft.renderer.engine.compose.Timeline;
import lib.minecraft.renderer.engine.compose.layer.GeometryLayer;
import lib.minecraft.renderer.engine.compose.layer.Layers;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.slot.BlockSlot;
import lib.minecraft.renderer.option.spec.AnimationOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.engine.texture.Biome;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Renders a {@link Block} as either a full 3D isometric tile or a single flat face by
 * dispatching to one of two sub-renderers based on {@link BlockOptions#getType()}.
 * <p>
 * Each sub-renderer is a {@code public static final} inner class implementing
 * {@link Renderer Renderer&lt;BlockOptions&gt;}:
 * <ul>
 * <li>{@link Isometric3D} poses a {@link ModelEngine} from the model's authored {@code display.gui}
 * for a default render, falling back to the standard {@code [30, 225, 0]} iso pose
 * ({@link Projection#VANILLA_ISO}) when absent. The standard {@code block/block.json} gui reproduces
 * that iso pose bit-for-bit, so only mirrored-Y blocks (stairs/slabs/fence gates ship
 * {@code [30, 135, 0]} or {@code [30, 45, 0]}) present a different side; per-state orientation comes
 * from the baked blockstate variant rotation on top of the gui pose.</li>
 * <li>{@link BlockFace2D} delegates to {@link RasterEngine} for single-face output.</li>
 * </ul>
 * Shared block lookup and biome tint resolution live as package-private static helpers on this
 * class so both sub-renderers can reach them without duplicating logic. CTM / Connected Textures
 * are resolved by the caller before invoking the renderer (the CTM integration hook lives on
 * {@link RasterEngine}).
 */
public final class BlockRenderer implements Renderer<BlockOptions> {

    /** Sub-renderer for the full 3D isometric tile path ({@link BlockOptions.Type#ISOMETRIC_3D}). */
    private final @NotNull Isometric3D isometric3D;
    /** Sub-renderer for the flat single-face path ({@link BlockOptions.Type#BLOCK_FACE_2D}). */
    private final @NotNull BlockFace2D blockFace2D;

    /**
     * Constructs a new {@code BlockRenderer} bound to the given context, eagerly creating both
     * sub-renderers so a caller can dispatch either render type without re-instantiation.
     *
     * @param context the render context supplying the block index and texture lookups
     */
    public BlockRenderer(@NotNull RendererContext context) {
        this.isometric3D = new Isometric3D(context);
        this.blockFace2D = new BlockFace2D(context);
    }

    /**
     * Renders the block, dispatching to the isometric or single-face sub-renderer per
     * {@link BlockOptions#getType()}, then composites the result over the options background.
     *
     * @param options the block options
     * @return the rendered image composited over {@link BlockOptions#getBackground()}
     */
    @Override
    public @NotNull ImageData render(@NotNull BlockOptions options) {
        ImageData rendered = switch (options.getType()) {
            case ISOMETRIC_3D -> this.isometric3D.render(options);
            case BLOCK_FACE_2D -> this.blockFace2D.render(options);
        };
        return options.getBackground().composite(rendered);
    }

    /**
     * Looks up a block by id in the renderer context, throwing a descriptive
     * {@link RenderException} when the block is missing.
     */
    static @NotNull Block requireBlock(@NotNull RendererContext context, @NotNull String blockId) {
        return context.findBlock(blockId).orElseThrow(() -> new RenderException("No block registered for id '%s'", blockId));
    }

    /**
     * Resolves the ARGB tint applied to a block's faces based on its
     * {@link Block.TintTarget}. Returns opaque white for {@code NONE}, the block's hardcoded
     * constant for {@code CONSTANT}, or a colormap sample for {@code GRASS} / {@code FOLIAGE} /
     * {@code DRY_FOLIAGE} against the {@link BlockOptions#getBiome() options biome}.
     */
    static int resolveBlockTint(@NotNull RendererContext context, @NotNull Block block, @NotNull BlockOptions options) {
        return resolveBlockTint(context, block, options.getBiome());
    }

    /**
     * Resolves the ARGB tint applied to a block's faces based on its {@link Block.TintTarget},
     * sampling colormap targets against an explicit {@code biome}. Shared by the block icon path
     * (via {@link BlockOptions}) and the entity carried-block overlay (which has no
     * {@code BlockOptions} and passes the default biome directly).
     *
     * @param context the renderer context supplying the colormaps
     * @param block the block whose tint target is resolved
     * @param biome the biome to sample colormap tints against
     * @return the ARGB tint, opaque white when the block is untinted
     */
    static int resolveBlockTint(@NotNull RendererContext context, @NotNull Block block, @NotNull Biome biome) {
        Block.TintTarget target = block.tint().target();

        if (target == Block.TintTarget.NONE)
            return ColorMath.WHITE;

        if (target == Block.TintTarget.CONSTANT)
            return block.tint().constant().map(Color::getRGB).orElse(ColorMath.WHITE);

        return new Textures(context).sampleBiomeTint(target, biome);
    }

    /**
     * Full 3D isometric block tile renderer. Every block - single- or multi-element (chests, doors,
     * pistons) - is built from its resolved model's full element list via
     * {@link BlockGeometryKit#buildFromElements}. Geometry is assembled through a
     * {@link GeometryLayer} stack (primary model, then additive / merged block-entity parts), then
     * recentered / re-lit and rasterized through {@link Projection#VANILLA_ISO}. Biome tint is
     * applied per face via the shared
     * {@link BlockRenderer#resolveBlockTint(RendererContext, Block, BlockOptions)} helper (faces
     * with {@code tintindex >= 0} only).
     */
    @RequiredArgsConstructor
    public static final class Isometric3D implements Renderer<BlockOptions> {

        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull BlockOptions options) {
            Block block = requireBlock(this.context, options.getBlockId());
            // Honor the model's authored display.gui for a default block-icon render, so mirrored-Y
            // blocks (stairs / slabs / fence gates ship [30, 135, 0] or [30, 45, 0]) face the side
            // they do in-game rather than the [30, 225, 0] mirror. block/block.json's standard gui
            // ([30, 225, 0], scale 0.625) reproduces VANILLA_ISO bit-for-bit, so plain blocks are
            // unchanged - only the authored overrides move. A non-default projection, rotation, or
            // facing keeps the projection path, since the caller drove that pose deliberately. The
            // chosen pose drives the camera AND the inventory relight together (view.lighting() defaults
            // to tracking the pose); the blockstate variant rotation (buildVariantRotation) still supplies
            // per-state orientation, and the rasterize call applies no separate model-spin.
            View resolved = resolveIconView(block, options.getOutput());
            LightingFrame lighting = resolved.lighting();

            // The Block.Entity is attached directly to the Block at PipelineRendererContext
            // construction time, so the renderer reads it straight off the block - no sidecar
            // lookup through RendererContext#findBlockEntityEntry is needed.
            Block.Entity be = block.entity().orElse(null);
            int tint = resolveRenderTint(block, be, options);
            int untintedTint = ColorMath.WHITE;

            // Fall back to the block's tooling-derived default blockstate when the caller supplies no
            // explicit variant, so blocks with per-state models
            // ({@code sweet_berry_bush}, doors, {@code furnace}, glazed terracotta, crops) render
            // their canonical default rather than whichever model registered first. Property-less
            // blocks have an empty default state, which resolves to the raw model pose. This replaces
            // the harness {@code .variant} sidecar the parity test used to consume. The default path
            // reads the pre-parsed default-state map directly; the ONLY surviving string->map parse is
            // the explicit public RenderOptions.variant, parsed once here.
            ConcurrentMap<String, String> effectiveProps = options.getVariant().isEmpty()
                ? block.defaultState()
                : BlockStateKey.parse(options.getVariant());

            // Per-frame rebuild: variant resolution, face-texture resolution,
            // geometry assembly, and the inventory relight ALL run inside the rasterizer callback at
            // the frame's tick, so an animated block face (water / fire / prismarine / sea_lantern /
            // magma) rebuilds its flipbook geometry per frame - the proven fluid pattern; capturing the
            // build once would freeze it on frame 0's textures. A frameCount=1 static render bakes one
            // frame at tick 0: the same five tick-0 face resolutions as before, byte-identical. The
            // ModelEngine is (re)built per frame so parallel strip baking stays thread-safe (the fluid
            // reference does the same).
            // tickStrip UNCONDITIONALLY (the FluidRenderer pattern): frameCount=1 yields a single static
            // frame sampled at anim.getStartTick(), so a caller-supplied non-zero startTick is honored
            // (staticFrame would hardcode tick 0). Default (startTick=0, frameCount=1) is byte-identical.
            // AUTO opt-in: deriveTickStrip probes the block's animated face textures once and derives
            // the timeline directly, so a caller need not know the flipbook cadence.
            AnimationOptions anim = options.getAnimation();
            int size = options.getOutput().getCanvasSize();
            int ssaa = Math.max(1, options.getOutput().getSupersample());
            Timeline.TickTimeline timeline = anim.isDeriveTimeline()
                ? Timeline.deriveTickStrip(collectAnimatedSources(block), anim.getStartTick())
                : Timeline.tickStrip(anim);
            return timeline.bake(
                RasterPass.of(size, size, ssaa, options.getOutput().isAntiAlias(), (target, tick) ->
                    new ModelEngine(this.context, resolved.camera()).rasterize(
                        buildRelitTriangles(tick, block, be, effectiveProps, tint, untintedTint, lighting, options),
                        target)));
        }

        /**
         * Resolves the {@link View} a block icon renders through - the camera plus its lighting frame -
         * honouring the block's authored
         * {@code display.gui} pose for a neutral inventory render. Every block - plain, mirrored-Y, and
         * block-entity - reads the same source the in-game icon and the vanilla-reference harness use:
         * the block item's {@code display.gui} (baked onto {@link Block#iconGui()} at index build, which
         * resolves a special model to its base item model), applied in FULL (rotation + translation +
         * per-axis scale) by {@link Camera#fromDisplayGui}. The standard {@code block/block.json} gui
         * ({@code [30, 225, 0]}, scale {@code 0.625}) collapses to {@link Projection#VANILLA_ISO}
         * bit-for-bit, so only blocks whose gui overrides that pose move.
         * <p>
         * A block-entity's per-family mesh placement rides separately on its model-space presentation
         * (the item def's special {@code transformation}, captured as the bone presentation), so the
         * camera is the item gui for every block without a per-family branch. A non-neutral render
         * (custom projection, rotation, or facing) falls back to the projection's own resolution so a
         * caller-driven pose is never overridden. The lighting frame tracks the resolved pose.
         *
         * @param block the block whose authored {@code display.gui} pose to resolve
         * @param output the output frame supplying the projection, rotation, and facing
         * @return the view the icon renders through
         */
        private @NotNull View resolveIconView(@NotNull Block block, @NotNull OutputOptions output) {
            if (!output.isNeutralInventoryIcon())
                return output.getProjection().resolve(output.getRotation(), output.getFacing());

            ModelTransform gui = block.iconGui().orElse(null);
            if (gui == null)
                return output.getProjection().resolve(output.getRotation(), output.getFacing());
            return new View(Camera.fromDisplayGui(gui), LightingFrame.tracking(gui.getRotation()));
        }

        /**
         * Collects every distinct animated face texture the block can render into
         * {@link Timeline.Source}s: the block-entity mesh texture, the primary element model,
         * and every blockstate-variant / multipart-apply element model. Over-inclusive across variants
         * (a per-variant animated face is folded even when that variant is not the effective one), which
         * only ever lengthens the derived loop - correct for a timeline union and cheap. Sidecar-less
         * textures are skipped, so a fully-static block yields no sources.
         */
        private @NotNull List<Timeline.Source> collectAnimatedSources(@NotNull Block block) {
            ConcurrentMap<String, Boolean> seen = Concurrent.newMap();
            List<Timeline.Source> sources = new ArrayList<>();
            block.entity().ifPresent(be -> addAnimatedSource(be.textureId(), seen, sources));
            collectAnimatedFromModel(block.model(), seen, sources);
            for (Block.Variant variant : block.variants().values())
                if (variant.geometry() instanceof Block.ElementGeometry(ModelData model))
                    collectAnimatedFromModel(model, seen, sources);
            block.multipart().ifPresent(multipart -> {
                for (Block.Multipart.Part part : multipart.parts())
                    if (part.apply().geometry() instanceof Block.ElementGeometry(ModelData model))
                        collectAnimatedFromModel(model, seen, sources);
            });
            return sources;
        }

        /** Adds every distinct concrete animated face-texture id of a model to {@code sources}. */
        private void collectAnimatedFromModel(@NotNull ModelData model, @NotNull ConcurrentMap<String, Boolean> seen, @NotNull List<Timeline.Source> sources) {
            for (ModelElement element : model.getElements())
                for (ModelFace face : element.getFaces().values()) {
                    String ref = face.getTexture();
                    if (ref.isBlank()) continue;
                    String id = Textures.resolveTextureReference(ref, model.getTextures());
                    if (id.startsWith("#")) continue;
                    addAnimatedSource(id, seen, sources);
                }
        }

        /**
         * Resolves one texture id and, when it carries an {@code .mcmeta} animation sidecar not yet
         * seen, adds a {@link Timeline.Source} carrying the strip's implicit frame count (strip
         * height / frame height) and the parsed animation. A sidecar-less or unresolvable id is skipped.
         */
        private void addAnimatedSource(@NotNull String id, @NotNull ConcurrentMap<String, Boolean> seen, @NotNull List<Timeline.Source> sources) {
            if (seen.putIfAbsent(id, Boolean.TRUE) != null) return;
            Optional<AnimationData> animation = this.context.findAnimation(id);
            if (animation.isEmpty()) return;
            Optional<PixelBuffer> strip = this.context.resolveTexture(id);
            if (strip.isEmpty()) return;
            sources.add(Timeline.Source.of(strip.get(), animation.get()));
        }

        /**
         * Assembles and inventory-relights the block's geometry at animation {@code tick}: builds the
         * primary / additive-entity / merged-parts {@link GeometryLayer} stack (each face texture
         * resolved at {@code tick}), folds it, applies the block-entity icon rotation + multi-block
         * recenter, rebuilds from the first blockstate apply when empty, then re-lights with vanilla's
         * {@code Lighting.ITEMS_3D} Lambertian. Called once per frame from the raster callback.
         *
         * <p>Re-lights on the post-{@code display.gui} normal (26.1 dropped per-face cardinal
         * multiplication from the GUI inventory path - the shader's only lighting input is two
         * directional dot products, so face-rotated geometry gets continuous per-quad lighting rather
         * than bucketing to the closest cardinal's pre-baked value). Plain block models cull back faces
         * like vanilla's block render types (all bind CULL): a zero-thickness {@code block/cross} element
         * declares both faces and the GPU keeps only the camera-facing one (without the cull, the
         * away-facing polygon's mirrored-UV cutout texels draw extra silhouette pixels - cobweb +19797
         * java-only px). Block-ENTITY surfaces (signs, banner cloth, hanging-sign chains) are genuinely
         * vanilla-no-cull ({@code entityCutoutNoCull}) and keep their two-sided faces, so the cull is
         * gated on {@code be == null}.
         */
        private @NotNull ConcurrentList<VisibleTriangle> buildRelitTriangles(
            int tick, @NotNull Block block, @Nullable Block.Entity be, @NotNull ConcurrentMap<String, String> effectiveProps,
            int tint, int untintedTint, @NotNull LightingFrame lighting, @NotNull BlockOptions options
        ) {
            // Block geometry is assembled through a GeometryLayer stack - primary model, then additive
            // block-entity geometry, then merged block-entity parts - for uniformity with the other
            // renderers and so callers can splice layers via BlockOptions.layerDecorator. The shared
            // whole-mesh steps (multi-block recenter / rotation, empty-fallback rebuild, inventory
            // relight) run on the assembled sink afterwards.
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
            LayerStack<GeometryLayer> stack = new LayerStack<>();

            stack.append(BlockSlot.PRIMARY,
                sink -> sink.addAll(buildPrimaryGeometry(block, be, effectiveProps, tint, untintedTint, tick)));

            // Atlas-time composition: merge Block.Entity parts into the primary geometry (bed foot onto
            // head, decorated_pot sides onto base, banner flag onto post). Gated on mergeParts - scene
            // callers pass false to render one variant at a time. Additive entities (bell body) overlay
            // the primary model; non-additive entity geometry IS the primary model already.
            if (be != null && options.isMergeParts()) {
                if (be.additive())
                    stack.append(BlockSlot.ADDITIVE_ENTITY, sink -> sink.addAll(buildFromBoneModel(be.boneModel(), be.textureId(), tint, tick)));
                if (!be.parts().isEmpty())
                    stack.append(BlockSlot.PARTS, sink -> sink.addAll(buildFromEntityParts(be, tint, tick)));
            }

            Layers.foldInto(stack, options.getLayerDecorator(), triangles);

            // Every block entity runs recenterAndFit: its composed bone geometry isn't measured up
            // front, and recenterAndFit self-gates on extent > 1.4 blocks - a no-op for the
            // block-sized families (chest, sign, shulker, ...) and only recentring a tall/wide model
            // (copper_golem_statue, authored X-centred at 0 and Y up to ~24px off the single-block
            // frame; beds, two blocks wide). iconRotation (beds) applies first.
            if (be != null) {
                if (be.iconRotation() != 0)
                    triangles = applyRotation(triangles, Matrix4f.createRotationY(
                        (float) Math.toRadians(be.iconRotation())));
                triangles = recenterAndFit(triangles);
            }

            // Fallback: when the block's registered model produces no faces (variant- or
            // multipart-gated blocks where every apply has a {@code when} clause), rebuild
            // using the first blockstate apply regardless of conditions. Fixes shelves,
            // chiseled_bookshelf, sniffer_egg, stem_growth, mushroom_stem, flowerbed_*,
            // pitcher_crop_top_stage_*, redstone_dust, coral_fan, brewing_stand_bottle2, etc.
            if (triangles.isEmpty())
                triangles = tryFirstBlockstateApply(block, tint, untintedTint, tick);

            return Shading.relightForItems3d(triangles, lighting, be == null);
        }

        /**
         * Resolves the ARGB tint for the block's faces: a block entity's per-entry tint when it
         * overrides the block's biome / constant tint (banners resolve DyeColor via {@code BlockColors}
         * at render time rather than baking per-colour textures, so the mapping JSON carries the
         * DyeColor diffuse colour), else the block's biome / constant tint. Only faces carrying a
         * {@code tintindex >= 0} receive the colour downstream; {@code tintindex = -1} faces render at
         * their raw texture colour (banner pole / bar wood, grass_block dirt sides).
         */
        private int resolveRenderTint(@NotNull Block block, @Nullable Block.Entity be, @NotNull BlockOptions options) {
            boolean entityTinted = be != null && be.tintArgb() != ColorMath.WHITE;
            return entityTinted
                ? be.tintArgb()
                : resolveBlockTint(this.context, block, options);
        }

        /**
         * Builds the primary-slot geometry for a block: a non-additive bone-format block entity's
         * hierarchical mesh, a multipart assembly, or the resolved blockstate-variant element model.
         * <p>
         * Bone-format block entities (chest) carry a relative bone/cube tree rather than pre-flattened
         * block elements: build hierarchically via {@link #buildFromBoneModel} (its presentation faces
         * the model at the standard {@code [30, 225, 0]} iso pose). This replaces the whole primary
         * model - a non-additive entity's geometry IS the primary geometry. Additive bone entities
         * (bell) keep their blockstate model as the primary and merge the bone body in the ADDITIVE
         * slot, so they fall through here. A state-conditional bone variant (the ceiling hanging sign's
         * straight-chain mesh under {@code attached=true}) overrides the default bone geometry; the
         * blockstate variant rotation still applies (matching the element path).
         */
        private @NotNull ConcurrentList<VisibleTriangle> buildPrimaryGeometry(@NotNull Block block, @Nullable Block.Entity be, @NotNull ConcurrentMap<String, String> effectiveProps, int tint, int untintedTint, int tick) {
            if (be != null && !be.additive()) {
                Block.Variant boneVariant = resolveVariant(block, effectiveProps);
                Block.Entity.BoneModel boneToUse = boneVariant != null && boneVariant.geometry() instanceof Block.BoneGeometry(Block.Entity.BoneModel boneModel)
                    ? boneModel
                    : be.boneModel();
                ConcurrentList<VisibleTriangle> boneTriangles = buildFromBoneModel(boneToUse, be.textureId(), tint, tick);
                if (boneVariant != null && boneVariant.hasRotation())
                    boneTriangles = applyRotation(boneTriangles, buildVariantRotation(boneVariant));
                return boneTriangles;
            }
            if (block.multipart().isPresent())
                return assembleMultipart(block.multipart().get(), effectiveProps, tint, untintedTint, tick);
            // Resolve the blockstate variant BEFORE building geometry so its model id can override
            // Block#model() (sweet_berry_bush age stages, doors). The variant key is the caller's
            // when supplied, else the block's default state key; property-less blocks fall through to
            // the raw model pose. TILE_ENTITY blocks point the variant at an empty template, so the
            // non-empty-elements check keeps the geometry-bearing BE model - while still letting a BE
            // inject a geometry variant for a mesh-varying state (hanging sign).
            Block.Variant variant = resolveVariant(block, effectiveProps);
            ModelData modelToUse = block.model();
            if (variant != null && variant.geometry() instanceof Block.ElementGeometry(ModelData model) && !model.getElements().isEmpty())
                modelToUse = model;
            ConcurrentList<VisibleTriangle> primary = buildFromBlockElements(modelToUse, variant, tint, untintedTint, tick);
            if (variant != null && variant.hasRotation())
                primary = applyRotation(primary, buildVariantRotation(variant));
            return primary;
        }

        /**
         * Assembles geometry from all matching parts of a multipart blockstate. Evaluates
         * each part's condition against the variant properties and builds triangles for every
         * matching model, applying per-part rotation where specified.
         */
        private @NotNull ConcurrentList<VisibleTriangle> assembleMultipart(@NotNull Block.Multipart multipart, @NotNull ConcurrentMap<String, String> callerProps, int tint, int untintedTint, int tick) {
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
            RasterEngine raster = new RasterEngine(this.context);

            for (Block.Multipart.Part part : multipart.parts()) {
                if (!part.when().matches(callerProps)) continue;

                Block.Variant apply = part.apply();
                // A multipart apply is always an element model (resolved from the full model set at
                // context construction); skip it when element-less (the apply's model id didn't resolve).
                if (!(apply.geometry() instanceof Block.ElementGeometry(ModelData partModel)) || partModel.getElements().isEmpty()) continue;

                // Build triangles for this part's model
                ConcurrentMap<String, PixelBuffer> faceTextures = Textures.loadElementFaceTextures(
                    partModel.getElements(), partModel.getTextures(),
                    id -> Optional.of(raster.textures().resolveTextureAtTick(id, tick)));
                var forceRefs = Textures.resolveForceTranslucentRefs(
                    partModel.getElements(), partModel.getTextures());

                ConcurrentList<VisibleTriangle> partTriangles = apply.uvlock()
                    ? BlockGeometryKit.buildFromElements(partModel.getElements(), faceTextures, tint, untintedTint, apply.x(), apply.y(), true, forceRefs)
                    : BlockGeometryKit.buildFromElements(partModel.getElements(), faceTextures, tint, untintedTint, forceRefs);

                // Apply per-part rotation if specified
                if (apply.hasRotation())
                    partTriangles = applyRotation(partTriangles, buildVariantRotation(apply));

                triangles.addAll(partTriangles);
            }

            return triangles;
        }

        /**
         * Applies a rotation matrix to all triangles in a list, transforming vertex positions
         * and surface normals. Preserves each triangle's {@code cullBackFaces} and {@code emissive}
         * traits while resetting {@code translucent} / {@code glinted} to {@code false} - block
         * geometry carries neither.
         */
        private static @NotNull ConcurrentList<VisibleTriangle> applyRotation(@NotNull ConcurrentList<VisibleTriangle> triangles, @NotNull Matrix4f rotation) {
            ConcurrentList<VisibleTriangle> rotated = Concurrent.newList();

            for (VisibleTriangle tri : triangles) {
                rotated.add(new VisibleTriangle(
                    tri.position0().transform(rotation),
                    tri.position1().transform(rotation),
                    tri.position2().transform(rotation),
                    tri.uv0(), tri.uv1(), tri.uv2(),
                    tri.texture(), tri.tintArgb(),
                    tri.normal().transformNormal(rotation),
                    tri.shading(), new SurfaceTraits(tri.traits().cullBackFaces(), tri.traits().emissive(), false, false)
                ));
            }

            return rotated;
        }


        /**
         * Builds triangles from all elements in a multi-element block model. Walks every
         * element's face texture references, dereferences {@code #variable} chains against
         * the model's texture bindings, and builds geometry via
         * {@link BlockGeometryKit#buildFromElements}. Accepts the model directly (rather than
         * a {@link Block}) so callers can pass a variant-resolved model that differs from the
         * block's primary {@link Block#model()} - e.g. {@code sweet_berry_bush_stage0} for
         * an {@code age=0} render.
         */
        private @NotNull ConcurrentList<VisibleTriangle> buildFromBlockElements(@NotNull ModelData model, @Nullable Block.Variant variant, int tint, int untintedTint, int tick) {
            RasterEngine raster = new RasterEngine(this.context);
            ConcurrentMap<String, PixelBuffer> faceTextures = Textures.loadElementFaceTextures(
                model.getElements(), model.getTextures(),
                id -> Optional.of(raster.textures().resolveTextureAtTick(id, tick)));
            var forceRefs = Textures.resolveForceTranslucentRefs(
                model.getElements(), model.getTextures());

            // uvlock counter-rotates the up/down-face UVs against the variant Y rotation so the
            // texture stays world-aligned (the position rotation is applied separately by the
            // caller via applyRotation). Non-uvlock variants fall through to the plain build.
            if (variant != null && variant.uvlock())
                return BlockGeometryKit.buildFromElements(model.getElements(), faceTextures, tint, untintedTint, variant.x(), variant.y(), true, forceRefs);
            return BlockGeometryKit.buildFromElements(model.getElements(), faceTextures, tint, untintedTint, forceRefs);
        }

        /**
         * Builds triangles from a specific bone-format geometry + presentation, sampling the given
         * entity texture. Shared by the primary bone entity, its state-conditional bone variant
         * (the ceiling hanging sign's straight-chain mesh), the bone parts, and the additive bone
         * body.
         *
         * @param boneModel the bone geometry + presentation metadata to build
         * @param textureId the entity texture id the cube UVs sample
         * @param tint the ARGB tint to apply when the model is {@link Block.Entity.BoneModel#tinted()}
         * @param tick the animation tick the entity texture is sampled at
         * @return the composed block-frame triangle list
         */
        private @NotNull ConcurrentList<VisibleTriangle> buildFromBoneModel(@NotNull Block.Entity.BoneModel boneModel, @NotNull String textureId, int tint, int tick) {
            RasterEngine raster = new RasterEngine(this.context);
            PixelBuffer texture = raster.textures().resolveTextureAtTick(textureId, tick);
            // Only a tinted model (the banner flag's tintindex-0 cloth) receives the dye/biome tint;
            // an untinted model (the banner post's wood) samples its texture raw.
            int faceTint = boneModel.tinted() ? tint : ColorMath.WHITE;
            return BlockGeometryKit.buildFromBones(boneModel.model(), texture, faceTint, boneModel.presentation());
        }

        /**
         * Builds triangles for every {@link Block.Entity.Part part} attached to a block-entity
         * block and translates them by each part's offset. Returns the combined triangle list
         * ready to concatenate with the primary geometry. Called only when
         * {@link BlockOptions#isMergeParts()} is {@code true}.
         * <p>
         * Translating the output triangles (rather than rewriting the element's from/to and
         * rotation.origin up-front) is safe because rotation composes with translation:
         * rotating around origin O then translating by D gives the same result as rotating
         * around origin O+D after the whole element has been translated by D. That means the
         * element's rotated-cube corners land at the correct final positions either way.
         * <p>
         * This is the atlas-time composition path that used to live in
         * {@link BlockModelLoader}. Moving it to render time
         * lets scene callers skip the merge for a per-variant-geometry render.
         */
        private @NotNull ConcurrentList<VisibleTriangle> buildFromEntityParts(@NotNull Block.Entity entity, int tint, int tick) {
            ConcurrentList<VisibleTriangle> combined = Concurrent.newList();
            RasterEngine raster = new RasterEngine(this.context);

            for (Block.Entity.Part part : entity.parts()) {
                // Build the part hierarchically with its own presentation, sampling the part's entity
                // texture (which may differ from the primary - decorated_pot sides use
                // entity/decorated_pot/decorated_pot_side while the base uses ..._base).
                Block.Entity.BoneModel boneModel = part.boneModel();
                PixelBuffer texture = raster.textures().resolveTextureAtTick(part.texture(), tick);
                int partTint = boneModel.tinted() ? tint : ColorMath.WHITE;
                ConcurrentList<VisibleTriangle> partTriangles =
                    BlockGeometryKit.buildFromBones(boneModel.model(), texture, partTint, boneModel.presentation());

                // Apply the part's offset to every vertex. Offset is in model units (0..16);
                // triangle vertex positions are in block units (0..1) post-GeometryKit, so
                // divide by 16.
                float dx = part.offset()[0] / BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK;
                float dy = part.offset()[1] / BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK;
                float dz = part.offset()[2] / BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK;
                if (dx != 0f || dy != 0f || dz != 0f) {
                    ConcurrentList<VisibleTriangle> shifted = Concurrent.newList();
                    for (VisibleTriangle t : partTriangles) {
                        shifted.add(new VisibleTriangle(
                            new Vector3f(t.position0().x() + dx, t.position0().y() + dy, t.position0().z() + dz),
                            new Vector3f(t.position1().x() + dx, t.position1().y() + dy, t.position1().z() + dz),
                            new Vector3f(t.position2().x() + dx, t.position2().y() + dy, t.position2().z() + dz),
                            t.uv0(), t.uv1(), t.uv2(),
                            t.texture(), t.tintArgb(), t.normal(), t.shading(), new SurfaceTraits(t.traits().cullBackFaces(), t.traits().emissive(), false, false)
                        ));
                    }
                    partTriangles = shifted;
                }

                combined.addAll(partTriangles);
            }

            return combined;
        }

        /**
         * Builds triangles from the first variant or multipart apply of a block's blockstate,
         * ignoring any {@code when} condition. Acts as a default render for blocks whose every
         * blockstate apply is gated behind property conditions (shelves, chiseled_bookshelf,
         * redstone_dust, flowerbed_*) or whose registered template model carries unresolved
         * {@code #var} face refs (sniffer_egg, stem_growth, mushroom_stem).
         * <p>
         * Returns an empty list when the block has no blockstate apply or when the referenced
         * model cannot be resolved in the block index. Per-apply rotation is preserved so the
         * rendered block faces the apply's intended direction.
         */
        private @NotNull ConcurrentList<VisibleTriangle> tryFirstBlockstateApply(@NotNull Block block, int tint, int untintedTint, int tick) {
            Block.Variant first = null;
            if (block.multipart().isPresent()) {
                ConcurrentList<Block.Multipart.Part> parts = block.multipart().get().parts();

                if (!parts.isEmpty())
                    first = parts.getFirst().apply();
            } else if (!block.variants().isEmpty())
                first = block.variants().values().iterator().next();

            if (first == null)
                return Concurrent.newList();

            if (!(first.geometry() instanceof Block.ElementGeometry(ModelData partModel)) || partModel.getElements().isEmpty())
                return Concurrent.newList();

            RasterEngine raster = new RasterEngine(this.context);
            ConcurrentMap<String, PixelBuffer> faceTextures = Textures.loadElementFaceTextures(
                partModel.getElements(), partModel.getTextures(),
                id -> Optional.of(raster.textures().resolveTextureAtTick(id, tick)));
            var forceRefs = Textures.resolveForceTranslucentRefs(
                partModel.getElements(), partModel.getTextures());

            ConcurrentList<VisibleTriangle> triangles = first.uvlock()
                ? BlockGeometryKit.buildFromElements(partModel.getElements(), faceTextures, tint, untintedTint, first.x(), first.y(), true, forceRefs)
                : BlockGeometryKit.buildFromElements(partModel.getElements(), faceTextures, tint, untintedTint, forceRefs);

            if (first.hasRotation())
                triangles = applyRotation(triangles, buildVariantRotation(first));

            return triangles;
        }

        /**
         * Builds a rotation matrix from a blockstate variant's X and Y rotation values,
         * matching vanilla's {@code BlockModelDefinition} variant baking: both angles are
         * negated because blockstate rotation is specified in the opposite sense from JOML's
         * (and this codebase's) right-handed rotation matrices. Applied to vertex positions
         * to pre-transform the geometry before the gui display transform.
         */
        private static @NotNull Matrix4f buildVariantRotation(@NotNull Block.Variant variant) {
            // Vanilla blockstate variant rotation applies Y first, then X, to a vertex - the
            // composite R_x * R_y. Built with the fluent rotate path ({@code this * R(q)},
            // post-multiply, bit-identical to vanilla's {@code PoseStack.mulPose}) rather than
            // {@code createRotationX(...).multiply(...)}, whose full matrix-matrix multiply drifts
            // 1-4 ULPs per entry vs vanilla (see {@link Matrix4f} fluent-vs-multiply note).
            // Applying X then Y under post-multiply yields IDENTITY * R_x * R_y = R_x * R_y.
            Matrix4f result = Matrix4f.IDENTITY;

            if (variant.x() != 0)
                result = result.rotateX((float) Math.toRadians(-variant.x()));

            if (variant.y() != 0)
                result = result.rotateY((float) Math.toRadians(-variant.y()));

            return result;
        }

        /**
         * Looks up the blockstate variant for a given property key. Exact-match only: an
         * empty {@code variantKey} or a key that isn't present in the blockstate returns
         * {@code null}, in which case the caller renders the raw model pose - which for
         * oriented blocks matches what vanilla inventory shows, since vanilla's inventory
         * pipeline never consults the blockstate.
         */
        private static @Nullable Block.Variant resolveVariant(@NotNull Block block, @NotNull ConcurrentMap<String, String> callerProps) {
            // A property-less caller maps to the unconditional {@code ""} blockstate variant, whose
            // model is authoritative and need NOT equal {@link Block#model()} (the by-id
            // {@code block/<id>} guess). mud_bricks points {@code ""} at
            // {@code block/mud_bricks_north_west_mirrored} (north/west faces UV-flipped) where
            // {@code getModel()} is the plain {@code block/mud_bricks} cube_all - falling through to
            // {@code getModel()} dropped the mirror. The caller only swaps in the variant's geometry
            // when it carries real elements, so an empty particle-only template (TILE_ENTITY blocks
            // whose mesh comes from the block-entity model) still falls back to the BE model. Retained
            // as a direct string lookup on the string-keyed variants map - byte-identical to today.
            if (callerProps.isEmpty()) return block.variants().get("");
            // Most-specific subset wins; first-encountered wins on ties. The caller may supply a
            // fully-qualified blockstate (e.g. `facing=north,half=lower,hinge=left,open=false,powered=false`
            // from the harness's defaultBlockState dump) while the JSON variant keys list only the
            // properties that actually affect the model (`facing/half/hinge/open` for doors, omitting
            // `powered`); the entry whose props are a SUBSET of the caller's and match the most
            // properties wins. This lets a geometry-bearing {@code attached=true} variant (injected for
            // the ceiling hanging sign) beat the unconditional {@code ""} catch-all. An exact match is
            // simply the maximal-specificity case of this same loop (vanilla keys are sorted, so no two
            // distinct keys parse to equal maps), so no separate exact fast path is needed. Each
            // variant's properties are PRE-PARSED at load - no per-render parse.
            Block.Variant best = null;
            int bestSpecificity = -1;
            for (Block.Variant variant : block.variants().values()) {
                ConcurrentMap<String, String> variantProps = variant.properties();
                if (isSubsetMatch(variantProps, callerProps) && variantProps.size() > bestSpecificity) {
                    best = variant;
                    bestSpecificity = variantProps.size();
                }
            }
            return best;
        }

        /** Returns true when every entry in {@code subset} appears with the same value in {@code superset}. */
        private static boolean isSubsetMatch(@NotNull ConcurrentMap<String, String> subset, @NotNull ConcurrentMap<String, String> superset) {
            for (Map.Entry<String, String> e : subset.entrySet()) {
                String supersetVal = superset.get(e.getKey());
                if (supersetVal == null || !supersetVal.equals(e.getValue())) return false;
            }
            return true;
        }

        /**
         * Recenters and scales a triangle list so all geometry fits within the standard
         * 1.4 unit extent. Used for multi-block entity models that extend beyond the
         * standard 0-16 single-block bounds.
         * <p>
         * Applies two distinct behaviours depending on how far the geometry overflows:
         * <ul>
         * <li><b>Horizontal multi-block (beds):</b> extent &gt; 1.4 - shrinks uniformly to
         *     1.4 and recenters around the bbox midpoint so both halves fit one tile.</li>
         * <li><b>Slightly tall single-block (decorated_pot rim y=17..20):</b> extent just
         *     above 1.0 - leaves scale at 1 and skips recentering, so the element keeps
         *     its authored Y levels and the rim naturally extends above the block top
         *     line just like vanilla's inventory icon. Previously the pot got scaled up
         *     1.12× and shifted down, which stretched the wall→rim gap and broke
         *     element-to-element alignment.</li>
         * </ul>
         */
        private static @NotNull ConcurrentList<VisibleTriangle> recenterAndFit(@NotNull ConcurrentList<VisibleTriangle> triangles) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (VisibleTriangle t : triangles) {
                for (Vector3f v : new Vector3f[]{ t.position0(), t.position1(), t.position2() }) {
                    minX = Math.min(minX, v.x()); maxX = Math.max(maxX, v.x());
                    minY = Math.min(minY, v.y()); maxY = Math.max(maxY, v.y());
                    minZ = Math.min(minZ, v.z()); maxZ = Math.max(maxZ, v.z());
                }
            }
            float extent = Math.max(Math.max(maxX - minX, maxY - minY), maxZ - minZ);
            if (extent <= 1.4f) return triangles;
            float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f, cz = (minZ + maxZ) * 0.5f;
            float scale = 1.4f / extent;

            ConcurrentList<VisibleTriangle> result = Concurrent.newList();
            for (VisibleTriangle t : triangles) {
                result.add(new VisibleTriangle(
                    new Vector3f((t.position0().x() - cx) * scale, (t.position0().y() - cy) * scale, (t.position0().z() - cz) * scale),
                    new Vector3f((t.position1().x() - cx) * scale, (t.position1().y() - cy) * scale, (t.position1().z() - cz) * scale),
                    new Vector3f((t.position2().x() - cx) * scale, (t.position2().y() - cy) * scale, (t.position2().z() - cz) * scale),
                    t.uv0(), t.uv1(), t.uv2(),
                    t.texture(), t.tintArgb(), t.normal(), t.shading(), new SurfaceTraits(t.traits().cullBackFaces(), t.traits().emissive(), false, false)
                ));
            }
            return result;
        }

    }

    /**
     * Single-face 2D block renderer. Outputs a flat textured quad for one of the six block
     * faces specified by {@link BlockOptions#getFace()}, applying any biome tint via a
     * {@link BlendMode#MULTIPLY} blit.
     */
    @RequiredArgsConstructor
    public static final class BlockFace2D implements Renderer<BlockOptions> {

        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull BlockOptions options) {
            Block block = requireBlock(this.context, options.getBlockId());
            RasterEngine engine = new RasterEngine(this.context);
            PixelBuffer buffer = engine.createBuffer(options.getOutput().getCanvasSize(), options.getOutput().getCanvasSize());

            String textureId = block.textureRef(options.getFace().direction(), "all", "side", "particle");
            PixelBuffer face = engine.textures().resolveTexture(textureId);
            int tint = resolveBlockTint(this.context, block, options);
            PixelBuffer tinted = ColorMath.tint(face, tint);
            int size = options.getOutput().getCanvasSize();
            buffer.blitScaled(tinted, 0, 0, size, size);

            return Timeline.still(buffer);
        }

    }

}
