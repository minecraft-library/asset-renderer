package lib.minecraft.renderer;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.bake.mesh.BlockGeometryKit;
import lib.minecraft.renderer.bake.texture.TextureRefusal;
import lib.minecraft.renderer.bake.texture.Tints;
import lib.minecraft.renderer.content.index.BlockModelLoader;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.index.VariantMatcher;
import lib.minecraft.renderer.diagnostic.Substitutions;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.camera.View;
import lib.minecraft.renderer.engine.draw.GeometryLayer;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.draw.SurfaceTraits;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.frame.RasterPass;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.engine.geometry.ModelUnits;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.engine.light.LightingFrame;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.mesh.MissingMesh;
import lib.minecraft.renderer.engine.raster.Rasterizer;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.request.AnimationOptions;
import lib.minecraft.renderer.request.BlockOptions;
import lib.minecraft.renderer.request.OutputOptions;
import lib.minecraft.renderer.request.slot.BlockSlot;
import lib.minecraft.renderer.vanilla.Biome;
import lib.minecraft.renderer.vanilla.TintSource;
import lib.minecraft.renderer.vanilla.id.BlockStateKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Renders a {@link Block} as either a full 3D isometric tile or a single flat face by
 * dispatching to one of two sub-renderers based on {@link BlockOptions#getType()}.
 * <p>
 * Each sub-renderer is a {@code public static final} inner class implementing
 * {@link Renderer Renderer&lt;BlockOptions&gt;}:
 * <ul>
 * <li>{@link Isometric3D} poses a {@link Rasterizer} from the model's authored {@code display.gui}
 * for a default render, falling back to the standard {@code [30, 225, 0]} iso pose
 * ({@link Projection#VANILLA_ISO}) when absent. The standard {@code block/block.json} gui reproduces
 * that iso pose bit-for-bit, so only mirrored-Y blocks (stairs/slabs/fence gates ship
 * {@code [30, 135, 0]} or {@code [30, 45, 0]}) present a different side; per-state orientation comes
 * from the baked blockstate variant rotation on top of the gui pose.</li>
 * <li>{@link BlockFace2D} blits a single tinted face straight into its output buffer.</li>
 * </ul>
 * Shared block lookup and biome tint resolution live as package-private static helpers on this
 * class so both sub-renderers can reach them without duplicating logic. Connected Textures are
 * resolved per face during the isometric build: {@link Isometric3D} hands
 * {@link BlockGeometryKit.FaceTextureResolver a resolver} to the kit that swaps a matched face's
 * base texture for the CTM tile {@link RendererContext#resolveConnectedTexture} returns (inert on a
 * vanilla-only stack).
 */
public final class BlockRenderer implements Renderer<BlockOptions> {

    /**
     * Sub-renderer for the full 3D isometric tile path ({@link BlockOptions.Type#ISOMETRIC_3D}).
     */
    private final @NotNull Isometric3D isometric3D;
    /**
     * Sub-renderer for the flat single-face path ({@link BlockOptions.Type#BLOCK_FACE_2D}).
     */
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
     * Renders a block-backed slot icon: the isometric render, except that where the block's
     * {@link Block#modelIcon()} holds, each face of its model takes the item-definition tint its
     * tintindex names - white where the definition names none - rather than the options' biome.
     * <p>
     * Vanilla calculates a slot icon's tints from its item definition in every display context, while
     * the block's own tint source colours only the placed and the no-world block. The definition's
     * tints index the faces of the model it names, which is {@link Block#model()} exactly where
     * {@code modelIcon} holds; a stand-in draws a model the definition does not name, so it keeps the
     * plain render's tint.
     *
     * @param options the block options, which must name {@link BlockOptions.Type#ISOMETRIC_3D}
     * @param definitionTints the item definition's tints, calculated, indexed by tintindex
     * @return the rendered image composited over {@link BlockOptions#getBackground()}
     * @throws IllegalArgumentException if the options name a face render, which has no slot-icon build
     */
    @NotNull ImageData renderIcon(@NotNull BlockOptions options, int @NotNull [] definitionTints) {
        if (options.getType() != BlockOptions.Type.ISOMETRIC_3D)
            throw new IllegalArgumentException(String.format("A slot icon renders isometric, not '%s'", options.getType()));

        return options.getBackground().composite(this.isometric3D.render(options, definitionTints));
    }

    /**
     * Answers what a block render draws for an id the block index does not know, or refuses where the
     * caller turned the substitution off. A block the index knows as one that draws nothing, such as
     * air, never reaches it: it draws an empty frame on either arm, which is what vanilla draws for it.
     * A fluid or a portal does reach it, since this renderer cannot draw one.
     * <p>
     * Both entry points decide that here, so the flag is read in one place and the refusal is worded
     * once. The picture stays the caller's, because the two draw different ones - a slot's flat square
     * where a posed render gets the cube.
     *
     * @param options the caller's options, supplying the id and the substitution flag
     * @param drawn the picture to draw where the substitution is on
     * @return the drawn picture
     * @throws RenderException where the caller turned the substitution off
     */
    static @NotNull ImageData missingBlock(@NotNull BlockOptions options, @NotNull Supplier<ImageData> drawn) {
        if (!options.isSubstituteMissing())
            throw new RenderException("No block registered for id '%s'", options.getBlockId());

        Substitutions.model(options.getBlockId());
        return drawn.get();
    }

    /**
     * Resolves the ARGB tint applied to a block's faces based on its
     * {@link TintSource}, sampling against the {@link BlockOptions#getBiome() options biome}.
     */
    static int resolveBlockTint(@NotNull RendererContext context, @NotNull Block block, @NotNull BlockOptions options) {
        return resolveBlockTint(context, block, options.getBiome());
    }

    /**
     * Resolves the ARGB tint applied to a block's faces based on its {@link TintSource},
     * sampling against an explicit {@code biome}. Shared by the block icon path (via
     * {@link BlockOptions}) and the entity carried-block overlay (which has no
     * {@code BlockOptions} and passes the default biome directly).
     * <p>
     * {@link TintSource#CONSTANT CONSTANT} is the one target answered here, because its
     * colour is baked on the block's own {@link Block.Tint} and no biome can supply it. Every other
     * target - including {@link TintSource#NONE NONE}, which carries no biome channel and so
     * answers opaque white - is the context's to resolve.
     *
     * @param context the renderer context supplying the colormaps
     * @param block the block whose tint target is resolved
     * @param biome the biome to sample colormap tints against
     * @return the ARGB tint, opaque white when the block is untinted
     */
    static int resolveBlockTint(@NotNull RendererContext context, @NotNull Block block, @NotNull Biome biome) {
        TintSource target = block.tint().target();

        if (target == TintSource.CONSTANT)
            return block.tint().constant().map(Color::getRGB).orElse(ColorMath.WHITE);

        return Tints.biome(context, target, biome);
    }

    /**
     * The frame a texture displays at a tick, refusing a texture no pack supplies or one that cannot be
     * decoded - this renderer draws nothing without it.
     *
     * @param textures the context the texture resolves through
     * @param textureId the namespaced texture id
     * @param tick the animation tick
     * @return the frame to draw
     * @throws RenderException if no pack supplies the texture, or the file it supplies yields no pixels
     */
    private static @NotNull PixelBuffer requireFrame(@NotNull RendererContext textures, @NotNull String textureId, int tick) {
        return TextureRefusal.require(
            Flipbook.atTick(textures.resolveTexture(textureId), textures.findFlipbook(textureId), tick), textureId);
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
            return render(options, Optional.empty());
        }

        /**
         * Renders a slot icon, whose identity build takes the item definition's tints in place of the
         * options' biome.
         *
         * @param options the block options
         * @param definitionTints the item definition's tints, calculated, indexed by tintindex
         * @return the rendered image, before the background composite
         */
        @NotNull ImageData render(@NotNull BlockOptions options, int @NotNull [] definitionTints) {
            return render(options, Optional.of(definitionTints));
        }

        /**
         * Renders the block, with or without the item definition's tints.
         *
         * @param options the block options
         * @param definitionTints the item definition's tints for a slot icon, empty for a plain render
         * @return the rendered image, before the background composite
         */
        private @NotNull ImageData render(@NotNull BlockOptions options, @NotNull Optional<int[]> definitionTints) {
            Possible<Block> block = this.context.findBlock(options.getBlockId());
            return switch (block.getState()) {
                case PRESENT -> new Assembly(this.context, options, block.get(), definitionTints).bake();
                case EMPTY -> emptyFrames(options);
                case ABSENT -> missingBlock(options, () -> missingCube(this.context, options));
            };
        }

        /**
         * Draws the frames the missing-model cube would have drawn on, with nothing on them - what a
         * block the game registers draws where it draws nothing, such as air.
         * <p>
         * The frames keep the caller's canvas, supersample and timing, so the render answers in the
         * shape a resolving subject would have, minus the subject.
         *
         * @param options the caller's options, supplying the output frame and the timing
         * @return the empty frames
         */
        private static @NotNull ImageData emptyFrames(@NotNull BlockOptions options) {
            OutputOptions output = options.getOutput();
            int canvas = output.getCanvasSize();
            return options.getAnimation().timeline().bake(
                RasterPass.of(canvas, canvas, output.getSupersample(), output.isAntiAlias(), (target, tick) -> { }));
        }

        /**
         * Draws the missing-model cube through the caller's own output frame - the same view a block
         * with no authored {@code display.gui} pose resolves to.
         * <p>
         * An id no index carries has no model to pose and no flipbooks to derive a timeline from, so
         * the pose stays the caller's and only the subject is substituted.
         *
         * @param context the render context the cube rasterizes through
         * @param options the caller's options, supplying the output frame and the timing
         * @return the posed cube
         */
        private static @NotNull ImageData missingCube(
            @NotNull RendererContext context, @NotNull BlockOptions options) {
            OutputOptions output = options.getOutput();
            View missing = output.getProjection().resolve(output.getRotation(), output.getFacing());
            int canvas = output.getCanvasSize();
            return options.getAnimation().timeline().bake(
                RasterPass.of(canvas, canvas, output.getSupersample(), output.isAntiAlias(), (target, tick) ->
                    new Rasterizer(missing.camera()).rasterize(
                        Shading.relightForItems3d(MissingMesh.cube(), missing.lighting(), true), target)));
        }

        /**
         * Resolves the {@link View} a block icon renders through - the camera plus its lighting frame -
         * honouring the block's authored
         * {@code display.gui} pose for a neutral inventory render. Every block - plain, mirrored-Y, and
         * block-entity - reads the same source the in-game icon and the vanilla-reference harness use:
         * the block item's {@code display.gui} (baked onto {@link Block#iconGui()} at index build, which
         * resolves a special model to its base item model), applied in FULL (rotation + translation +
         * per-axis scale) by {@link Camera#fromTransform}. The standard {@code block/block.json} gui
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
        private static @NotNull View resolveIconView(@NotNull Block block, @NotNull OutputOptions output) {
            if (!output.isNeutralInventoryIcon())
                return output.getProjection().resolve(output.getRotation(), output.getFacing());

            ModelTransform gui = block.iconGui().orElse(null);
            if (gui == null)
                return output.getProjection().resolve(output.getRotation(), output.getFacing());
            return new View(
                Camera.fromTransform(
                    gui.getRotation(),
                    new Vector3f(gui.getTranslationX(), gui.getTranslationY(), gui.getTranslationZ()),
                    new Vector3f(gui.getScaleX(), gui.getScaleY(), gui.getScaleZ())
                ),
                LightingFrame.tracking(gui.getRotation())
            );
        }

        /**
         * Resolves the ARGB tint for the block's faces: a block entity's per-entry tint when it
         * overrides the block's biome / constant tint (banners resolve DyeColor via {@code BlockColors}
         * at render time rather than baking per-colour textures, so the mapping JSON carries the
         * DyeColor diffuse colour), else the block's biome / constant tint. Only faces carrying a
         * {@code tintindex >= 0} receive the colour downstream; {@code tintindex = -1} faces render at
         * their raw texture colour (banner pole / bar wood, grass_block dirt sides).
         *
         * @param context the renderer context supplying the colormaps
         * @param block the block whose tint is resolved
         * @param entity the block's entity, where it has one
         * @param options the options supplying the biome a colormap tint samples against
         * @return the ARGB tint the render's tinted faces receive
         */
        private static int resolveRenderTint(
            @NotNull RendererContext context, @NotNull Block block,
            @NotNull Optional<Block.BlockEntity> entity, @NotNull BlockOptions options
        ) {
            return entity.map(Block.BlockEntity::tintArgb)
                .filter(argb -> argb != ColorMath.WHITE)
                .orElseGet(() -> resolveBlockTint(context, block, options));
        }

        /**
         * One isometric render's fixed inputs, bound once, and the per-tick geometry build that reads
         * them.
         * <p>
         * Everything held here is a property of the render rather than of the frame - the subject, the
         * state it resolves at, the tint its faces receive, the view it is posed and lit through - so
         * binding them in the constructor is what lets the geometry build read fields where it would
         * otherwise carry the same values down eight signatures. It is also what keeps the blockstate
         * string parsed exactly once: the parse happens here and nowhere else.
         * <p>
         * The build itself stays per frame. Variant resolution, face-texture resolution, geometry
         * assembly and the inventory relight all run inside the rasterizer callback at the frame's
         * tick, so an animated block face (water / fire / prismarine / sea_lantern / magma) rebuilds
         * its flipbook geometry per frame - the fluid pattern; capturing the build once would freeze it
         * on frame 0's textures. The {@link Rasterizer} is rebuilt per frame so parallel strip baking
         * stays thread-safe.
         */
        private static final class Assembly {

            /**
             * The render context supplying the colormap and connected-texture lookups.
             */
            private final @NotNull RendererContext context;

            /**
             * The lookups every face reads its texture through - the substituting wrapper where the
             * caller asked a texture no pack supplies to draw the checkerboard, and the context itself
             * where it asked to be refused instead.
             */
            private final @NotNull RendererContext textures;

            /**
             * The caller's options, read for the output frame, the layer decorator and the merge flag.
             */
            private final @NotNull BlockOptions options;

            /**
             * The subject this render draws.
             */
            private final @NotNull Block block;

            /**
             * The subject's own namespaced id, which is what a connected-texture rule matches on.
             */
            private final @NotNull String blockId;

            /**
             * The subject's block entity, where it has one.
             */
            private final @NotNull Optional<Block.BlockEntity> entity;

            /**
             * The blockstate this render resolves at - the caller's variant where it named one, else
             * the block's tooling-derived default state, so blocks with per-state models
             * ({@code sweet_berry_bush}, doors, {@code furnace}, glazed terracotta, crops) render their
             * canonical default rather than whichever model registered first. A property-less block has
             * an empty default state, which resolves to the raw model pose.
             */
            private final @NotNull ConcurrentMap<String, String> state;

            /**
             * The ARGB tint every {@code tintindex >= 0} face receives.
             */
            private final int tint;

            /**
             * The colour each face of the identity build takes: the item definition's tint its
             * tintindex names on a slot icon, else {@link #tint} on every tinted face.
             */
            private final @NotNull BlockGeometryKit.FaceTint iconTint;

            /**
             * The view the icon is posed through, supplying the camera every frame rasterizes with.
             */
            private final @NotNull View view;

            /**
             * The lighting frame the inventory relight runs against, tracking the resolved pose.
             */
            private final @NotNull LightingFrame lighting;

            /**
             * Whether the render is the inventory icon, in which the blockstate plays no part.
             * <p>
             * Vanilla bakes a block item's icon from the model its {@code minecraft:item_model}
             * component names, at {@code BlockModelRotation.IDENTITY} - so the icon carries no variant
             * model, no variant rotation, no {@code uvlock} and no multipart assembly. That model is
             * what {@code BlockIndexBuilder} has already stamped onto {@link Block#model()} for every
             * block the flag is set on, so the icon is one build of it. It is visibly all three: a
             * stair presents its riser rather than its back, a fence icon is a post and two arms rather
             * than the multipart's four, and a button icon is {@code block/oak_button_inventory} rather
             * than the wall button its default state selects. A caller who names a state asked for that
             * state and gets the whole blockstate treatment.
             */
            private final boolean identityModelState;

            /**
             * Binds one render's fixed inputs, resolving the pose, the tint and the blockstate from the
             * subject and the caller's options.
             *
             * @param context the render context supplying the block, texture and colormap lookups
             * @param options the caller's options
             * @param block the resolved subject
             * @param definitionTints the item definition's tints for a slot icon, empty for a plain render
             */
            private Assembly(
                @NotNull RendererContext context, @NotNull BlockOptions options, @NotNull Block block,
                @NotNull Optional<int[]> definitionTints
            ) {
                this.context = context;
                this.textures = options.isSubstituteMissing() ? context.withMissingTexture() : context;
                this.options = options;
                this.block = block;
                this.blockId = block.id().id();
                // The Block.BlockEntity is attached directly to the Block at IndexedRendererContext
                // construction time, so the renderer reads it straight off the block - no sidecar
                // lookup through RendererContext#findBlockEntityEntry is needed.
                this.entity = block.entity();
                this.view = resolveIconView(block, options.getOutput());
                this.lighting = this.view.lighting();
                this.tint = resolveRenderTint(context, block, this.entity, options);
                this.state = options.getVariant().isEmpty()
                    ? block.defaultState()
                    : BlockStateKey.parse(options.getVariant());
                // A caller who names no state is asking for the inventory icon. Where vanilla has one of
                // its own (Block#modelIcon), it is this block's model baked at the identity model state,
                // so the default state selects the model and nothing else. Where it has none - a sprite
                // icon, or a block entity's mesh - the 3D render is this pipeline's own stand-in and keeps
                // the default state's orientation, there being no vanilla pose to reproduce.
                this.identityModelState = options.getVariant().isEmpty() && block.modelIcon();
                this.iconTint = definitionTints
                    .filter(tints -> this.identityModelState)
                    .map(BlockGeometryKit.FaceTint::layers)
                    .orElseGet(() -> BlockGeometryKit.FaceTint.split(this.tint, ColorMath.WHITE));
            }

            /**
             * Bakes the render's frames, rebuilding the geometry at each one's tick.
             * <p>
             * The schedule is built unconditionally: a {@code frameCount} of one yields a single static
             * frame sampled at the animation's own start tick, so a caller-supplied non-zero start tick
             * is honoured where a hardcoded tick 0 would drop it. Opting into derivation folds the
             * block's own flipbooks - resolved once at index build - into the timeline, so a caller need
             * not know the cadence.
             *
             * @return the baked frames
             */
            private @NotNull ImageData bake() {
                AnimationOptions anim = this.options.getAnimation();
                int size = this.options.getOutput().getCanvasSize();
                int ssaa = this.options.getOutput().getSupersample();
                Timeline.TickTimeline timeline = anim.isDeriveTimeline()
                    ? Flipbook.deriveTickStrip(this.block.flipbooks(), anim.getStartTick())
                    : anim.timeline();
                return timeline.bake(
                    RasterPass.of(size, size, ssaa, this.options.getOutput().isAntiAlias(), (target, tick) ->
                        new Rasterizer(this.view.camera()).rasterize(relightAt(tick), target)));
            }

            /**
             * Assembles and inventory-relights the geometry at animation {@code tick}: builds the
             * primary / additive-entity / merged-parts {@link GeometryLayer} stack (each face texture
             * resolved at {@code tick}), folds it, applies the block-entity icon rotation + multi-block
             * recenter, rebuilds from the first blockstate apply when empty, then re-lights with
             * vanilla's {@code Lighting.ITEMS_3D} Lambertian.
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
             * gated on the subject carrying no entity.
             *
             * @param tick the animation tick the faces are resolved at
             * @return the relit triangle list
             */
            private @NotNull ConcurrentList<VisibleTriangle> relightAt(int tick) {
                // Block geometry is assembled through a GeometryLayer stack - primary model, then additive
                // block-entity geometry, then merged block-entity parts - for uniformity with the other
                // renderers and so callers can splice layers via BlockOptions.layerDecorator. The shared
                // whole-mesh steps (multi-block recenter / rotation, empty-fallback rebuild, inventory
                // relight) run on the assembled sink afterwards.
                ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
                LayerStack<GeometryLayer> stack = new LayerStack<>();

                stack.append(BlockSlot.PRIMARY, sink -> sink.addAll(primaryAt(tick)));

                // Atlas-time composition: merge Block.BlockEntity parts into the primary geometry (bed foot onto
                // head, decorated_pot sides onto base, banner flag onto post). Gated on mergeParts - scene
                // callers pass false to render one variant at a time. Additive entities (bell body) overlay
                // the primary model; non-additive entity geometry IS the primary model already.
                if (this.entity.isPresent() && this.options.isMergeParts()) {
                    Block.BlockEntity be = this.entity.get();
                    if (be.additive())
                        stack.append(BlockSlot.ADDITIVE_ENTITY, sink -> sink.addAll(bonesAt(be.boneModel(), be.textureId(), tick)));
                    if (!be.parts().isEmpty())
                        stack.append(BlockSlot.PARTS, sink -> sink.addAll(partsAt(be, tick)));
                }

                Layers.foldInto(stack, this.options.getLayerDecorator(), triangles);

                // Every block entity runs BlockGeometryKit.recenterAndFit: its composed bone geometry isn't
                // measured up front, and the fit self-gates on extent > 1.4 blocks - a no-op for the
                // block-sized families (chest, sign, shulker, ...) and only recentring a tall/wide model
                // (copper_golem_statue, authored X-centred at 0 and Y up to ~24px off the single-block
                // frame; beds, two blocks wide). iconRotation (beds) applies first.
                if (this.entity.isPresent()) {
                    Block.BlockEntity be = this.entity.get();
                    if (be.iconRotation() != 0)
                        triangles = BlockGeometryKit.applyRotation(triangles, Matrix4f.createRotationY(
                            (float) Math.toRadians(be.iconRotation())));
                    triangles = BlockGeometryKit.recenterAndFit(triangles);
                }

                // Fallback: when the block's registered model produces no faces (variant- or
                // multipart-gated blocks where every apply has a {@code when} clause), rebuild
                // using the first blockstate apply regardless of conditions. Fixes shelves,
                // chiseled_bookshelf, sniffer_egg, stem_growth, mushroom_stem, flowerbed_*,
                // pitcher_crop_top_stage_*, redstone_dust, coral_fan, brewing_stand_bottle2, etc.
                if (triangles.isEmpty())
                    triangles = firstBlockstateApplyAt(tick);

                return Shading.relightForItems3d(triangles, this.lighting, this.entity.isEmpty());
            }

            /**
             * Builds the primary-slot geometry: a non-additive bone-format block entity's hierarchical
             * mesh, a multipart assembly, or the resolved blockstate-variant element model.
             * <p>
             * Bone-format block entities (chest) carry a relative bone/cube tree rather than pre-flattened
             * block elements: build hierarchically via {@link #bonesAt} (its presentation faces
             * the model at the standard {@code [30, 225, 0]} iso pose). This replaces the whole primary
             * model - a non-additive entity's geometry IS the primary geometry. Additive bone entities
             * (bell) keep their blockstate model as the primary and merge the bone body in the ADDITIVE
             * slot, so they fall through here. A state-conditional bone variant (the ceiling hanging sign's
             * straight-chain mesh under {@code attached=true}) overrides the default bone geometry; the
             * blockstate variant rotation applies exactly where the element path applies it.
             *
             * @param tick the animation tick the faces are resolved at
             * @return the primary-slot triangle list
             */
            private @NotNull ConcurrentList<VisibleTriangle> primaryAt(int tick) {
                Optional<Block.BlockEntity> mesh = this.entity.filter(be -> !be.additive());
                if (mesh.isPresent()) {
                    Block.BlockEntity be = mesh.get();
                    Block.Variant boneVariant = VariantMatcher.resolve(this.block, this.state);
                    Block.BlockEntity.BoneModel boneToUse = boneVariant != null && boneVariant.geometry() instanceof Block.BoneGeometry(Block.BlockEntity.BoneModel boneModel)
                        ? boneModel
                        : be.boneModel();
                    ConcurrentList<VisibleTriangle> boneTriangles = bonesAt(boneToUse, be.textureId(), tick);
                    if (boneVariant != null && boneVariant.hasRotation())
                        boneTriangles = BlockGeometryKit.applyRotation(boneTriangles, BlockGeometryKit.buildVariantRotation(boneVariant));
                    return boneTriangles;
                }
                if (this.identityModelState)
                    return elementsAt(this.block.model(), null, this.iconTint, tick);
                if (this.block.multipart().isPresent())
                    return multipartAt(this.block.multipart().get(), tick);
                // Resolve the blockstate variant BEFORE building geometry so its model id can override
                // Block#model() (sweet_berry_bush age stages, doors). The variant key is the caller's
                // when supplied, else the block's default state key; property-less blocks fall through to
                // the raw model pose. TILE_ENTITY blocks point the variant at an empty template, so the
                // non-empty-elements check keeps the geometry-bearing BE model - while still letting a BE
                // inject a geometry variant for a mesh-varying state (hanging sign).
                Block.Variant variant = VariantMatcher.resolve(this.block, this.state);
                ModelData modelToUse = this.block.model();
                if (variant != null && variant.geometry() instanceof Block.ElementGeometry(ModelData model) && !model.getElements().isEmpty())
                    modelToUse = model;
                ConcurrentList<VisibleTriangle> primary = elementsAt(modelToUse, variant,
                    BlockGeometryKit.FaceTint.split(this.tint, ColorMath.WHITE), tick);
                if (variant != null && variant.hasRotation())
                    primary = BlockGeometryKit.applyRotation(primary, BlockGeometryKit.buildVariantRotation(variant));
                return primary;
            }

            /**
             * Assembles geometry from all matching parts of a multipart blockstate. Evaluates
             * each part's condition against the render's state and builds triangles for every
             * matching model, applying per-part rotation where specified.
             *
             * @param multipart the multipart blockstate to assemble
             * @param tick the animation tick the faces are resolved at
             * @return the assembled triangle list
             */
            private @NotNull ConcurrentList<VisibleTriangle> multipartAt(@NotNull Block.Multipart multipart, int tick) {
                ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

                for (Block.Multipart.Part part : multipart.parts()) {
                    if (!part.when().matches(this.state)) continue;

                    Block.Variant apply = part.apply();
                    // A multipart apply is always an element model (resolved from the full model set at
                    // context construction); skip it when element-less (the apply's model id didn't resolve).
                    if (!(apply.geometry() instanceof Block.ElementGeometry(ModelData partModel)) || partModel.getElements().isEmpty()) continue;

                    // Build triangles for this part's model
                    ConcurrentMap<String, PixelBuffer> faceTextures = partModel.loadElementFaceTextures(facesAt(tick));
                    var forceRefs = partModel.resolveForceTranslucentRefs();

                    boolean uvlock = apply.uvlock();
                    ConcurrentList<VisibleTriangle> partTriangles = BlockGeometryKit.buildFromElements(partModel.getElements(), faceTextures,
                        new BlockGeometryKit.ElementBuildParams(BlockGeometryKit.FaceTint.split(this.tint, ColorMath.WHITE),
                            uvlock ? apply.x() : 0, uvlock ? apply.y() : 0, uvlock, forceRefs,
                            ctmResolver(partModel, tick)));

                    // Apply per-part rotation if specified
                    if (apply.hasRotation())
                        partTriangles = BlockGeometryKit.applyRotation(partTriangles, BlockGeometryKit.buildVariantRotation(apply));

                    triangles.addAll(partTriangles);
                }

                return triangles;
            }

            /**
             * Builds triangles from all elements in a multi-element block model. Walks every
             * element's face texture references, dereferences {@code #variable} chains against
             * the model's texture bindings, and builds geometry via
             * {@link BlockGeometryKit#buildFromElements}. Accepts the model directly (rather than
             * reading {@link Block#model()}) so a caller can pass a variant-resolved model that differs
             * from the block's primary one - {@code sweet_berry_bush_stage0} for an {@code age=0}
             * render.
             *
             * @param model the model whose elements are built
             * @param variant the blockstate variant supplying the uvlock rotation, or {@code null} for none
             * @param faceTint the colour each face takes, picked by its tintindex
             * @param tick the animation tick the faces are resolved at
             * @return the built triangle list
             */
            private @NotNull ConcurrentList<VisibleTriangle> elementsAt(
                @NotNull ModelData model, @Nullable Block.Variant variant,
                @NotNull BlockGeometryKit.FaceTint faceTint, int tick) {
                ConcurrentMap<String, PixelBuffer> faceTextures = model.loadElementFaceTextures(facesAt(tick));
                var forceRefs = model.resolveForceTranslucentRefs();

                // uvlock counter-rotates the up/down-face UVs against the variant Y rotation so the
                // texture stays world-aligned (the position rotation is applied separately by the
                // caller via BlockGeometryKit.applyRotation). Non-uvlock variants pass zero rotation, reproducing the plain build.
                boolean uvlock = variant != null && variant.uvlock();
                BlockGeometryKit.ElementBuildParams params = new BlockGeometryKit.ElementBuildParams(
                    faceTint,
                    uvlock ? variant.x() : 0, uvlock ? variant.y() : 0, uvlock, forceRefs,
                    ctmResolver(model, tick));
                return BlockGeometryKit.buildFromElements(model.getElements(), faceTextures, params);
            }

            /**
             * Builds the Connected Textures per-face resolver for a block model - it resolves each face's raw
             * {@code #ref} to its concrete base texture id, then substitutes a matching non-overlay CTM tile
             * through {@link RendererContext#resolveConnectedTexture}. A face whose matching rule keeps its
             * base texture and a face no rule matches both answer empty, and so does every face on a
             * vanilla-only stack (no {@code optifine/} tree, so no CTM rules), so the build falls through to
             * the pre-loaded texture byte-for-byte.
             *
             * @param model the model whose {@code #var} bindings deref each face ref
             * @param tick the animation tick a substitute tile is sampled at
             * @return the per-face resolver
             */
            private @NotNull BlockGeometryKit.FaceTextureResolver ctmResolver(@NotNull ModelData model, int tick) {
                return (face, rawRef) -> {
                    String baseId = model.resolveTextureReference(rawRef);
                    if (baseId.startsWith("#")) return Optional.empty();
                    return this.context.resolveConnectedTexture(this.blockId, this.state, baseId, face)
                        .map(id -> requireFrame(this.textures, id.id(), tick))
                        .toOptional();
                };
            }

            /**
             * The per-face resolver a model's element walk loads its textures through, sampling each at
             * {@code tick}.
             * <p>
             * It answers present for every id it is asked about, because both arms of {@link #textures}
             * are total: one draws the checkerboard and the other raises. Nothing here answers empty, and
             * that is the point - an empty would have the walk drop the face and the render come out with
             * a hole in it, where a render that is not substituting asked to be refused instead.
             *
             * @param tick the animation tick each face is sampled at
             * @return the resolver
             */
            private @NotNull Function<String, Optional<PixelBuffer>> facesAt(int tick) {
                return textureId -> Optional.of(requireFrame(this.textures, textureId, tick));
            }

            /**
             * Builds triangles from a specific bone-format geometry + presentation, sampling the given
             * entity texture. Shared by the primary bone entity, its state-conditional bone variant
             * (the ceiling hanging sign's straight-chain mesh), the bone parts, and the additive bone
             * body.
             *
             * @param boneModel the bone geometry + presentation metadata to build
             * @param textureId the entity texture id the cube UVs sample
             * @param tick the animation tick the entity texture is sampled at
             * @return the composed block-frame triangle list
             */
            private @NotNull ConcurrentList<VisibleTriangle> bonesAt(
                @NotNull Block.BlockEntity.BoneModel boneModel, @NotNull String textureId, int tick) {
                PixelBuffer texture = requireFrame(this.textures, textureId, tick);
                // Only a tinted model (the banner flag's tintindex-0 cloth) receives the dye/biome tint;
                // an untinted model (the banner post's wood) samples its texture raw.
                int faceTint = boneModel.tinted() ? this.tint : ColorMath.WHITE;
                return BlockGeometryKit.buildFromBones(boneModel.model(), texture, faceTint, boneModel.presentation());
            }

            /**
             * Builds triangles for every {@link Block.BlockEntity.Part part} attached to a block-entity
             * block and translates them by each part's offset. Returns the combined triangle list
             * ready to concatenate with the primary geometry. Called only when
             * {@link BlockOptions#isMergeParts()} is {@code true}.
             * <p>
             * The entity arrives as a parameter rather than off the field, so the one caller that has
             * already proved the subject carries one goes on proving it at the compiler.
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
             *
             * @param entity the block entity whose parts are built
             * @param tick the animation tick the part textures are sampled at
             * @return the combined, offset part triangles
             */
            private @NotNull ConcurrentList<VisibleTriangle> partsAt(@NotNull Block.BlockEntity entity, int tick) {
                ConcurrentList<VisibleTriangle> combined = Concurrent.newList();

                for (Block.BlockEntity.Part part : entity.parts()) {
                    // Build the part hierarchically with its own presentation, sampling the part's entity
                    // texture (which may differ from the primary - decorated_pot sides use
                    // entity/decorated_pot/decorated_pot_side while the base uses ..._base).
                    Block.BlockEntity.BoneModel boneModel = part.boneModel();
                    PixelBuffer texture = requireFrame(this.textures, part.texture(), tick);
                    int partTint = boneModel.tinted() ? this.tint : ColorMath.WHITE;
                    ConcurrentList<VisibleTriangle> partTriangles =
                        BlockGeometryKit.buildFromBones(boneModel.model(), texture, partTint, boneModel.presentation());

                    // Apply the part's offset to every vertex. Offset is in model units (0..16);
                    // triangle vertex positions are in block units (0..1) post-BoxKit, so
                    // divide by 16.
                    float dx = part.offset()[0] / ModelUnits.PIXELS_PER_BLOCK;
                    float dy = part.offset()[1] / ModelUnits.PIXELS_PER_BLOCK;
                    float dz = part.offset()[2] / ModelUnits.PIXELS_PER_BLOCK;
                    if (dx != 0f || dy != 0f || dz != 0f) {
                        partTriangles = partTriangles.stream()
                            .map(t -> new VisibleTriangle(
                                new Vector3f(t.position0().x() + dx, t.position0().y() + dy, t.position0().z() + dz),
                                new Vector3f(t.position1().x() + dx, t.position1().y() + dy, t.position1().z() + dz),
                                new Vector3f(t.position2().x() + dx, t.position2().y() + dy, t.position2().z() + dz),
                                t.uv0(), t.uv1(), t.uv2(),
                                t.texture(), t.tintArgb(), t.normal(), t.shading(), new SurfaceTraits(t.traits().cullBackFaces(), false, false,
                                    t.traits().directionalLight(),
                                    PassDeclaration.DEFAULT.withEmissive(t.traits().pass().emissive()))
                            ))
                            .collect(Concurrent.toWideList());
                    }

                    combined.addAll(partTriangles);
                }

                return combined;
            }

            /**
             * Builds triangles from the first variant or multipart apply of the block's blockstate,
             * ignoring any {@code when} condition. Acts as a default render for blocks whose every
             * blockstate apply is gated behind property conditions (shelves, chiseled_bookshelf,
             * redstone_dust, flowerbed_*) or whose registered template model carries unresolved
             * {@code #var} face refs (sniffer_egg, stem_growth, mushroom_stem).
             * <p>
             * Returns an empty list when the block has no blockstate apply or when the referenced
             * model cannot be resolved in the block index. Per-apply rotation is preserved so the
             * rendered block faces the apply's intended direction.
             *
             * @param tick the animation tick the faces are resolved at
             * @return the rebuilt triangle list, empty when nothing resolves
             */
            private @NotNull ConcurrentList<VisibleTriangle> firstBlockstateApplyAt(int tick) {
                Block.Variant first = null;
                if (this.block.multipart().isPresent()) {
                    ConcurrentList<Block.Multipart.Part> parts = this.block.multipart().get().parts();

                    if (!parts.isEmpty())
                        first = parts.getFirst().apply();
                } else if (!this.block.variants().isEmpty())
                    first = this.block.variants().values().iterator().next();

                if (first == null)
                    return Concurrent.newList();

                if (!(first.geometry() instanceof Block.ElementGeometry(ModelData partModel)) || partModel.getElements().isEmpty())
                    return Concurrent.newList();

                ConcurrentMap<String, PixelBuffer> faceTextures = partModel.loadElementFaceTextures(facesAt(tick));
                var forceRefs = partModel.resolveForceTranslucentRefs();

                boolean uvlock = first.uvlock();
                ConcurrentList<VisibleTriangle> triangles = BlockGeometryKit.buildFromElements(partModel.getElements(), faceTextures,
                    new BlockGeometryKit.ElementBuildParams(BlockGeometryKit.FaceTint.split(this.tint, ColorMath.WHITE),
                        uvlock ? first.x() : 0, uvlock ? first.y() : 0, uvlock, forceRefs,
                        ctmResolver(partModel, tick)));

                if (first.hasRotation())
                    triangles = BlockGeometryKit.applyRotation(triangles, BlockGeometryKit.buildVariantRotation(first));

                return triangles;
            }

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
            // A single face is a flat square whether or not the subject resolves, so an unknown id
            // draws the checkerboard filling the same canvas the resolved face would have, and a
            // registered block that draws nothing fills it transparent.
            Possible<Block> block = this.context.findBlock(options.getBlockId());
            int size = options.getOutput().getCanvasSize();
            return switch (block.getState()) {
                case PRESENT -> faceOf(block.get(), options);
                case EMPTY -> Timeline.still(PixelBuffer.create(size, size));
                case ABSENT -> missingBlock(options, () -> Timeline.still(MissingMesh.icon(size)));
            };
        }

        /**
         * Blits the block's chosen face flat, tinted where its own model asks the face to be.
         *
         * @param block the resolved subject
         * @param options the caller's options, supplying the face and the canvas
         * @return the flat face
         */
        private @NotNull ImageData faceOf(@NotNull Block block, @NotNull BlockOptions options) {
            PixelBuffer buffer = PixelBuffer.create(options.getOutput().getCanvasSize(), options.getOutput().getCanvasSize());

            String direction = options.getFace().direction();
            String textureId = block.textureRef(direction, "all", "side", "particle");
            // The whole-strip arm rather than the tick one, which is what a flat face has always read:
            // an animated id blits its whole strip squashed onto the square, where sampling a frame
            // would show one of them.
            RendererContext textures = options.isSubstituteMissing()
                ? this.context.withMissingTexture()
                : this.context;
            PixelBuffer face = TextureRefusal.require(textures.resolveTexture(textureId), textureId);
            int tint = tintIndexFor(block, direction) >= 0
                ? resolveBlockTint(this.context, block, options)
                : ColorMath.WHITE;
            PixelBuffer tinted = ColorMath.tint(face, tint);
            int size = options.getOutput().getCanvasSize();
            buffer.blitScaled(tinted, 0, 0, size, size);

            return Timeline.still(buffer);
        }

        /**
         * Answers the tint index the block's model declares for a face direction.
         * <p>
         * The first element that declares the direction wins, which is what matches the sprite this
         * path draws: a block declaring one direction twice binds the flat face to whichever texture
         * {@link Block#textureRef} resolves first, and that is the earlier element's. A face present
         * with no {@code tintindex} answers {@code -1} and is drawn untinted, which is how a flower
         * pot's own sides stay uncoloured while the plant inside them does not.
         * <p>
         * A direction <i>no</i> element declares is different in kind and takes the model's first
         * declared index instead. Nothing was drawn for that face, so {@code textureRef} fell through
         * to its own {@code all} / {@code side} / {@code particle} chain and put one of the model's
         * other sprites on the square - a cross-shaped plant has no top face, yet a top render still
         * shows its tinted sprite. The tint index follows the texture rather than the direction,
         * because the texture is what is actually on the canvas.
         *
         * @param block the block whose model declares the faces
         * @param direction the vanilla direction key the face is drawn for
         * @return the tint index governing this face, or {@code -1} when nothing tints it
         */
        private static int tintIndexFor(@NotNull Block block, @NotNull String direction) {
            for (ModelElement element : block.model().getElements()) {
                ModelFace face = element.getFaces().get(direction);
                if (face != null) return face.getTintIndex();
            }

            for (ModelElement element : block.model().getElements())
                for (ModelFace face : element.getFaces().values())
                    if (face.getTintIndex() >= 0) return face.getTintIndex();

            return -1;
        }

    }

}
