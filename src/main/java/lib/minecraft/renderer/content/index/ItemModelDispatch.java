package lib.minecraft.renderer.content.index;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.request.AnimationOptions;
import lib.minecraft.renderer.request.ItemModelContext;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.vanilla.SunAngle;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * The walk from an item id and an evaluation context to what a frame draws - the item model dispatch
 * tree, the CIT override that outranks it, and the timing a time-driven tree derives for itself.
 * <p>
 * It is a lookup rather than a render: every entry answers with a {@link FrameItem} built over an
 * already-indexed item, or the timing to ask for one at, and none of them touches a pixel. What a frame
 * draws is what the item definition names, as in vanilla: the model its leaf names, the missing model
 * where no pack ships that model, vanilla's missing item model for a definition the loader refused or a
 * select that falls back to nothing it declares, nothing at all for an empty branch, and every one of
 * those a {@code composite}'s children land on, one over another in order.
 * <p>
 * A stack steers a walk only where its components, or the item id its default item model is read
 * from, choose the branch. Where the walk at a context reaches the branch it reaches at the same
 * context without its stack, the frame resolves at the context without it, the pipeline-baked fast
 * path included, so a caller can hand a stack to every render and the walk of an item it does not
 * steer proceeds as though it had handed none.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class ItemModelDispatch {

    /**
     * Builds the per-render frame resolver both render paths draw their frames through: maps an
     * animation tick onto what the frame at it draws, memoized so a schedule that revisits an
     * evaluation context resolves it once.
     * <p>
     * The frame is per-tick because a dispatch tree can branch on world time - a clock resolves a
     * different face on each frame of an animated bake. The memo is per-render and discarded with it:
     * the CIT walk and pack state a resolution depends on are the render's own. Keying on the whole
     * evaluation context needs no assumption about which part of a resolution a tick can move, and
     * bounds the memo either way - one entry for a still, one per distinct sampled instant for a
     * time-driven strip. The key is the context the walk proceeds at, so a stack that chooses no branch
     * at an instant keys that instant without it.
     *
     * @param context the renderer context supplying pack / model / texture lookups
     * @param options the item render options
     * @param modelContext the evaluation context the render resolves its item tree at, already resolved for the drawing type
     * @param cit the render's single CIT walk result
     * @param animation the animation this render actually bakes, already derived
     * @param baked the pipeline-baked item the caller resolved, which every frame starts from
     * @return what the frame at an animation tick draws
     */
    public static @NotNull IntFunction<FrameItem> frameItems(
        @NotNull RendererContext context, @NotNull ItemOptions options, @NotNull ItemModelContext modelContext,
        @NotNull CitResult cit, @NotNull AnimationOptions animation, @NotNull Item baked
    ) {
        // Only a game-time schedule moves the world clock between frames; a texture strip indexes a
        // flipbook, which leaves the tree's evaluation context - and so the resolved model - alone.
        boolean worldTime = animation.getSchedule() == AnimationOptions.Schedule.GAME_TIME;
        Optional<ItemModelTree> tree = context.findItemTree(options.getItemId());
        Map<ItemModelContext, FrameItem> resolved = new ConcurrentHashMap<>();
        // Frames raster in parallel, so the memo is concurrent and its resolver stays pure.
        return tick -> resolved.computeIfAbsent(
            walkedAt(tree, worldTime ? modelContext.atTick(tick) : modelContext),
            at -> resolveRenderItem(context, options, cit, at, baked));
    }

    /**
     * Resolves the animation a render actually bakes. The caller's own timing is used as given, unless
     * it opts into derivation - in which case an item whose model tree branches on world time has its
     * timing derived from that tree: one frame per step the time table on the branch its frames are
     * drawn from resolves, spread evenly across a day and played back as game time. It lets a caller ask
     * for an item to be animated without knowing which items are time-driven or how many faces they
     * ship.
     * <p>
     * The table is the one {@link ItemModelContext#timeDispatchSteps(ItemModelNode)} finds at the
     * render's own evaluation context, so a stack whose components pick a branch animates by that
     * branch's table, and a branch that holds none renders a still.
     * <p>
     * An item with nothing to animate keeps the caller's timing untouched, so requesting derivation on
     * a plain item costs it nothing and leaves it a still.
     *
     * @param context the renderer context supplying the item's dispatch tree
     * @param options the item render options
     * @param modelContext the evaluation context the render resolves its item tree at, already resolved for the drawing type
     * @return the animation timing to bake
     */
    public static @NotNull AnimationOptions itemAnimation(
        @NotNull RendererContext context, @NotNull ItemOptions options, @NotNull ItemModelContext modelContext
    ) {
        AnimationOptions animation = options.getAnimation();
        if (!animation.isDeriveTimeline()) return animation;

        OptionalInt steps = context.findItemTree(options.getItemId())
            .map(tree -> modelContext.timeDispatchSteps(tree.root()))
            .orElseGet(OptionalInt::empty);
        if (steps.isEmpty()) return animation;

        // A day divided among the tree's steps. Floored at one tick so a table finer than the day is
        // long still advances rather than sampling the same instant every frame.
        int frames = steps.getAsInt();
        return animation.mutate()
            .frameCount(frames)
            .ticksPerFrame(Math.max(1, SunAngle.TICKS_PER_DAY / frames))
            .schedule(AnimationOptions.Schedule.GAME_TIME)
            .build();
    }

    /**
     * Resolves what a frame draws, applying an {@link ItemModelContext} and the CIT model override on
     * top of the pipeline-baked item.
     * <ul>
     * <li>A definition the loader refused draws vanilla's missing item model whatever the context, so
     * it answers ahead of the fast path; only a CIT model override outranks it, as it outranks every
     * tree.</li>
     * <li>The neutral {@link ItemModelContext#gui()} context with no CIT model override takes the fast
     * path - the pipeline-baked item verbatim - where the item has no definition, or its walk lands on
     * the baked item's own model through no {@code composite}. The baked item is built from one
     * {@code models/item} file, which a definition may point past to another model, the missing model,
     * vanilla's missing item model or nothing, and a composite draws every child. A context whose stack
     * chooses no branch walks as the context without it, so it takes the fast path where that context
     * is neutral.</li>
     * <li>Otherwise the item's dispatch tree is walked against the context. A special leaf, in any layer
     * the walk lands on, keeps the baked item, which its own path serves. A present {@code cit.model()}
     * replaces the resolved model id (the OptiFine override-the-final-model join), and one that names no
     * model renders the base item.</li>
     * <li>A leaf's model id is materialised back into an {@link Item} by reusing the already-built model
     * for that id (its geometry + textures), carrying the walked branch's tints; an id no pack ships
     * draws the missing model, an absent fallback vanilla's missing item model, and an empty or bundle
     * branch nothing. A walk landing on several layers draws a {@link FrameItem.Composite} of them, each
     * materialised alike with its own tints. An item with no definition keeps the baked item.</li>
     * </ul>
     *
     * @param context the renderer context supplying the tree and the models
     * @param options the item render options, supplying the id
     * @param cit the render's single CIT walk result
     * @param modelContext the evaluation context the frame resolves at
     * @param baked the pipeline-baked item every frame starts from
     * @return what the frame draws
     */
    public static @NotNull FrameItem resolveRenderItem(
        @NotNull RendererContext context, @NotNull ItemOptions options, @NotNull CitResult cit,
        @NotNull ItemModelContext modelContext, @NotNull Item baked
    ) {
        Optional<ItemModelTree> tree = context.findItemTree(options.getItemId());
        boolean fromCit = cit.model().isPresent();
        if (!fromCit && tree.map(ItemModelTree::isRejected).orElse(false)) return new FrameItem.MissingItemModel(baked);

        ItemModelContext walked = walkedAt(tree, modelContext);
        Optional<ItemModelNode.Resolution> resolution = tree.map(walked::resolve);
        boolean landsOnBaked = resolution.map(branch -> landsOn(context, branch, baked)).orElse(true);
        if (walked.isNeutral() && !fromCit && landsOnBaked) return FrameItem.Drawn.baked(baked);

        // A special leaf maps onto an existing hardcoded / block-entity render path (parse-and-hold);
        // an unknown special kind is diagnosed and dropped. Either way the baked
        // item - already served by its own path - is returned.
        if (resolution.isPresent() && drawsSpecial(resolution.get())) {
            resolution.get().layers().forEach(layer -> layer.special().ifPresent(ItemModelNode.Special::resolveOrDrop));
            return FrameItem.Drawn.baked(baked);
        }

        // CIT whole-model override wins over the tree-resolved model. Resolve it by FULL id
        // (collision-free - a basename collapse would map minecraft:optifine/cit/diamond_sword onto the
        // vanilla diamond_sword). An override that is not a resolvable model (e.g. an optifine/cit/ path
        // outside models/) misses and is diagnosed rather than silently rendering the wrong model.
        if (fromCit) {
            String modelId = cit.model().get().id();
            Optional<ModelData> model = context.findItemModel(modelId);
            if (model.isEmpty()) {
                System.err.printf("CIT model override '%s' for item '%s' is not a resolvable item model - rendering the base item%n",
                    modelId, options.getItemId());
                return FrameItem.Drawn.baked(baked);
            }
            return drawn(baked, model.get(), resolution.map(ItemModelNode.Resolution::tints).orElse(baked.tints()), modelId);
        }

        if (resolution.isEmpty()) return FrameItem.Drawn.baked(baked);
        return leafItem(context, resolution.get(), baked);
    }

    /**
     * Whether a resolved branch lands on a special leaf in any of the layers it draws. The path serving
     * a special kind draws the whole item, so a branch holding one keeps the item that path draws.
     *
     * @param resolution the branch the walk resolved
     * @return whether any layer is a special leaf
     */
    private static boolean drawsSpecial(@NotNull ItemModelNode.Resolution resolution) {
        return resolution.layers().stream().anyMatch(layer -> layer.special().isPresent());
    }

    /**
     * Whether a resolved branch lands on an item's own model: one leaf, reached through no
     * {@code composite}, naming a model equal to the one the item draws. The model lookup and the item
     * index share their model instances, so a branch naming the file the item was built from passes.
     *
     * @param context the renderer context supplying the models
     * @param resolution the branch the walk resolved
     * @param item the item the item index holds for the id
     * @return whether the branch draws the item's own model
     */
    private static boolean landsOn(
        @NotNull RendererContext context, @NotNull ItemModelNode.Resolution resolution, @NotNull Item item
    ) {
        return !resolution.composed() && resolution.modelId()
            .flatMap(context::findItemModel)
            .filter(item.model()::equals)
            .isPresent();
    }

    /**
     * Materialises the frame a resolved branch draws: the frame its one layer draws, or a
     * {@link FrameItem.Composite} of the frame each layer a {@code composite} lands on draws, in paint
     * order.
     *
     * @param context the renderer context supplying the models
     * @param resolution the branch the walk resolved, no layer of it a special leaf
     * @param baked the item the frame carries the decorations of
     * @return what the frame draws
     */
    private static @NotNull FrameItem leafItem(
        @NotNull RendererContext context, @NotNull ItemModelNode.Resolution resolution, @NotNull Item baked
    ) {
        if (resolution.later().isEmpty()) return layerItem(context, resolution, baked);

        return new FrameItem.Composite(baked, resolution.layers()
            .stream()
            .map(layer -> layerItem(context, layer, baked))
            .collect(Concurrent.toUnmodifiableList()));
    }

    /**
     * Materialises the frame one layer draws: the model its leaf names, the missing model where no pack
     * ships it, vanilla's missing item model for an absent fallback, and nothing for an empty branch.
     *
     * @param context the renderer context supplying the models
     * @param resolution the layer, not a special leaf
     * @param baked the item the frame carries the decorations of
     * @return what the layer draws
     */
    private static @NotNull FrameItem layerItem(
        @NotNull RendererContext context, @NotNull ItemModelNode.Resolution resolution, @NotNull Item baked
    ) {
        if (resolution.missing()) return new FrameItem.MissingItemModel(baked);
        if (resolution.modelId().isEmpty()) return new FrameItem.Nothing(baked);

        String modelId = resolution.modelId().get();
        return context.findItemModel(modelId)
            .<FrameItem>map(model -> drawn(baked, model, resolution.tints(), modelId))
            .orElseGet(() -> new FrameItem.MissingModel(baked, modelId));
    }

    /**
     * Materialises a model into the item a frame draws, keeping the baked item's id, durability and
     * intrinsic foil.
     *
     * @param baked the item the drawn one stands for
     * @param model the model the walk or the CIT override named
     * @param tints the tints the frame carries
     * @param modelId the model's id
     * @return the drawn frame
     */
    private static @NotNull FrameItem.Drawn drawn(
        @NotNull Item baked, @NotNull ModelData model, @NotNull ConcurrentList<LayerTint> tints, @NotNull String modelId
    ) {
        ConcurrentMap<String, String> sprites = model.getTextures()
            .entrySet()
            .stream()
            .collect(Concurrent.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().sprite()));
        return new FrameItem.Drawn(
            new Item(baked.id(), model, sprites, baked.maxDurability(), tints, baked.alwaysGlinted()), Optional.of(modelId));
    }

    /**
     * Resolves the frame an item definition chooses for an id whose icon or held model the block draws,
     * where the definition rather than the block decides it: a definition the loader refused, one
     * whose branch the stack chooses, one whose walk passes through a {@code composite}, which draws
     * every child where one block model cannot stand for them all, and, for an id the item index
     * carries, one whose walk lands anywhere but the indexed item's own model. That is a block-backed
     * id the item index does not carry, or carries with a model whose shape is its elements.
     * <p>
     * Every other definition answers empty, and the id routes as the block's own icon and held model
     * do - so absent a stack, and for a stack that chooses nothing, only a composite and an indexed
     * id's walk off its own model route differently. A special leaf in any layer answers empty as
     * well, its kind being drawn by the path that serves the block. The choice is resolved once, at the
     * render's own context, rather than per frame.
     * <p>
     * The item the frame carries is the indexed one where the index holds the id, else one built for
     * the id: the chosen model with the leaf's tints, no durability and no intrinsic foil, no block item
     * being foil of itself.
     *
     * @param context the renderer context supplying the tree, the models and the item index
     * @param options the item render options, supplying the id
     * @param modelContext the evaluation context the render resolves its item tree at, already resolved for the drawing type
     * @return the frame the definition chooses, empty where the block's own route draws the id
     */
    public static @NotNull Optional<FrameItem> definitionItem(
        @NotNull RendererContext context, @NotNull ItemOptions options, @NotNull ItemModelContext modelContext
    ) {
        String itemId = options.getItemId();
        Optional<ItemModelTree> tree = context.findItemTree(itemId);
        if (tree.isEmpty()) return Optional.empty();

        Optional<Item> indexed = context.findItem(itemId);
        Item carried = indexed.orElseGet(() -> blank(itemId));
        if (tree.get().isRejected()) return Optional.of(new FrameItem.MissingItemModel(carried));

        ItemModelNode.Resolution resolution = modelContext.resolve(tree.get());
        if (drawsSpecial(resolution)) return Optional.empty();

        boolean blockRoute = indexed.map(item -> landsOn(context, resolution, item)).orElse(!resolution.composed());
        if (blockRoute && !steers(tree.get(), modelContext)) return Optional.empty();
        return Optional.of(leafItem(context, resolution, carried));
    }

    /**
     * Builds the item a frame carries for an id the item index does not hold - no model of its own, no
     * durability, no tint and no intrinsic foil.
     *
     * @param itemId the item id
     * @return the blank item
     */
    private static @NotNull Item blank(@NotNull String itemId) {
        return new Item(ResourceId.parse(itemId), new ModelData(), Concurrent.newUnmodifiableMap(), 0,
            Concurrent.newUnmodifiableList(), false);
    }

    /**
     * Answers the context a walk proceeds at: the given one where its stack chooses the tree's branch,
     * else the same context without it, which the walk answers alike.
     *
     * @param tree the item's dispatch tree, empty when the item has no definition
     * @param at the evaluation context the frame samples
     * @return the context the frame resolves at
     */
    private static @NotNull ItemModelContext walkedAt(@NotNull Optional<ItemModelTree> tree, @NotNull ItemModelContext at) {
        return tree.isPresent() && steers(tree.get(), at) ? at : at.withoutComponents();
    }

    /**
     * Whether a context's stack - its components and the item id its default item model is read from -
     * chooses a tree's branch: whether the walk at the context resolves other than the walk at the same
     * context without them.
     *
     * @param tree the item's dispatch tree
     * @param at the evaluation context
     * @return whether the stack steers the walk
     */
    private static boolean steers(@NotNull ItemModelTree tree, @NotNull ItemModelContext at) {
        ItemModelContext bare = at.withoutComponents();
        return !bare.equals(at) && !at.resolve(tree).equals(bare.resolve(tree));
    }

    /**
     * What one frame of an item render draws - the dispatch walk's answer at one evaluation context.
     * <p>
     * Every answer carries the item whose decorations the frame keeps: the damage bar reads its
     * maximum durability and the glint its intrinsic foil, since vanilla draws a slot's decorations
     * whatever model the definition picks. Whether the glint draws at all is the answer's own, as
     * {@link #glints()} says.
     */
    public sealed interface FrameItem
        permits FrameItem.Drawn, FrameItem.MissingModel, FrameItem.MissingItemModel, FrameItem.Nothing, FrameItem.Composite {

        /**
         * The item this frame draws, or the one it stands in for.
         *
         * @return the item
         */
        @NotNull Item item();

        /**
         * Whether vanilla draws the stack's glint over this frame. A drawn model takes it, and so does
         * the missing model a leaf names, which vanilla wraps as it wraps any leaf's model; its missing
         * item model and an empty branch set no foil.
         *
         * @return whether the frame glints where the stack does
         */
        boolean glints();

        /**
         * A model drawn - the pipeline-baked item, or one materialised from the leaf the walk landed on
         * or the CIT override named.
         *
         * @param item the item drawn
         * @param modelId the model id the walk's leaf or the CIT override named, empty for the pipeline-baked item
         */
        record Drawn(@NotNull Item item, @NotNull Optional<String> modelId) implements FrameItem {

            /**
             * Builds the frame that draws the pipeline-baked item as it stands.
             *
             * @param baked the pipeline-baked item
             * @return the drawn frame, naming no walked model
             */
            public static @NotNull Drawn baked(@NotNull Item baked) {
                return new Drawn(baked, Optional.empty());
            }

            /** {@inheritDoc} */
            @Override
            public boolean glints() {
                return true;
            }

        }

        /**
         * The missing model a leaf naming a model no pack ships draws - one full cube wearing the
         * missing sprite, at no display transform.
         *
         * @param item the item the missing model stands in for
         * @param modelId the model id the leaf names
         */
        record MissingModel(@NotNull Item item, @NotNull String modelId) implements FrameItem {

            /** {@inheritDoc} */
            @Override
            public boolean glints() {
                return true;
            }

        }

        /**
         * Vanilla's missing item model - what a definition the loader refused draws, and so does a
         * {@code select} or {@code range_dispatch} that matches nothing and declares no fallback. It is
         * the missing model's picture with no glint, since vanilla's missing item model sets no foil.
         *
         * @param item the item the missing item model stands in for
         */
        record MissingItemModel(@NotNull Item item) implements FrameItem {

            /** {@inheritDoc} */
            @Override
            public boolean glints() {
                return false;
            }

        }

        /**
         * Nothing - an {@code empty} or {@code bundle/selected_item} branch, which draws no model and no
         * glint while the slot's decorations still draw.
         *
         * @param item the item whose decorations the frame keeps
         */
        record Nothing(@NotNull Item item) implements FrameItem {

            /** {@inheritDoc} */
            @Override
            public boolean glints() {
                return false;
            }

        }

        /**
         * Every layer a {@code composite} draws, one over another in paint order, as vanilla's composite
         * model draws its children - each layer a frame of its own, with its own model, tints and pose.
         * It glints where any of its layers does, over the whole frame as a single model's glint covers
         * all of it.
         *
         * @param item the item whose decorations the frame keeps
         * @param layers the layers in paint order, two or more and none of them a composite
         */
        record Composite(@NotNull Item item, @NotNull ConcurrentList<FrameItem> layers) implements FrameItem {

            /** {@inheritDoc} */
            @Override
            public boolean glints() {
                return this.layers.stream().anyMatch(FrameItem::glints);
            }

        }

    }

}
