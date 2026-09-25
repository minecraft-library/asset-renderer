package lib.minecraft.renderer.content.index;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.port.answer.CitResult;
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
 * The walk from an item id and an evaluation context to the {@link Item} a frame draws - the item
 * model dispatch tree, the CIT override that outranks it, and the timing a time-driven tree derives
 * for itself.
 * <p>
 * It is a lookup rather than a render: every entry answers with an already-indexed item or the
 * timing to ask for one at, and none of them touches a pixel.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class ItemModelDispatch {

    /**
     * Builds the per-render item resolver both render paths draw their frames through: maps an
     * animation tick onto the item to render at it, memoized so a schedule that revisits an evaluation
     * context resolves it once.
     * <p>
     * The item is per-frame because a dispatch tree can branch on world time - a clock resolves a
     * different face on each frame of an animated bake. The memo is per-render and discarded with it:
     * the CIT walk and pack state a resolution depends on are the render's own. Keying on the whole
     * evaluation context needs no assumption about which part of a resolution a tick can move, and
     * bounds the memo either way - one entry for a still, one per distinct sampled instant for a
     * time-driven strip.
     *
     * @param context the renderer context supplying pack / model / texture lookups
     * @param options the item render options
     * @param cit the render's single CIT walk result
     * @param animation the animation this render actually bakes, already derived
     * @param baked the pipeline-baked item the caller resolved, which every frame starts from
     * @return the item to render at an animation tick
     */
    public static @NotNull IntFunction<Item> frameItems(
        @NotNull RendererContext context, @NotNull ItemOptions options, @NotNull CitResult cit,
        @NotNull AnimationOptions animation, @NotNull Item baked
    ) {
        ItemModelContext modelContext = options.getItemModel();
        // Only a game-time schedule moves the world clock between frames; a texture strip indexes a
        // flipbook, which leaves the tree's evaluation context - and so the resolved model - alone.
        boolean worldTime = animation.getSchedule() == AnimationOptions.Schedule.GAME_TIME;
        Map<ItemModelContext, Item> resolved = new ConcurrentHashMap<>();
        // Frames raster in parallel, so the memo is concurrent and its resolver stays pure.
        return tick -> resolved.computeIfAbsent(
            worldTime ? modelContext.atTick(tick) : modelContext,
            at -> resolveRenderItem(context, options, cit, at, baked));
    }

    /**
     * Resolves the animation a render actually bakes. The caller's own timing is used as given, unless
     * it opts into derivation - in which case an item whose model tree branches on world time has its
     * timing derived from that tree: one frame per step the tree's own dispatch table resolves, spread
     * evenly across a day and played back as game time. It lets a caller ask for an item to be animated
     * without knowing which items are time-driven or how many faces they ship.
     * <p>
     * An item with nothing to animate keeps the caller's timing untouched, so requesting derivation on
     * a plain item costs it nothing and leaves it a still.
     *
     * @param context the renderer context supplying the item's dispatch tree
     * @param options the item render options
     * @return the animation timing to bake
     */
    public static @NotNull AnimationOptions itemAnimation(@NotNull RendererContext context, @NotNull ItemOptions options) {
        AnimationOptions animation = options.getAnimation();
        if (!animation.isDeriveTimeline()) return animation;

        OptionalInt steps = context.findItemTree(options.getItemId())
            .map(tree -> tree.root().timeDispatchSteps())
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
     * Resolves the effective item to render, applying an {@link ItemModelContext} and the CIT model
     * override on top of the pipeline-baked item.
     * <p>
     * The neutral {@link ItemModelContext#gui()} context with no CIT model override takes the fast
     * path - the pipeline-baked item verbatim, byte-identical to a pre-tree render. Otherwise the
     * item's dispatch tree is re-walked against the given context, then a
     * present {@code cit.model()} replaces the resolved model id (the OptiFine override-the-final-model
     * join); the resolved model id is materialised back into an {@link Item} by reusing
     * the already-built index entry for that model (its geometry + textures), carrying the walked
     * branch's tints. Falls back to the baked item when the tree is absent or the branch resolves to
     * nothing.
     */
    public static @NotNull Item resolveRenderItem(
        @NotNull RendererContext context, @NotNull ItemOptions options, @NotNull CitResult cit,
        @NotNull ItemModelContext modelContext, @NotNull Item baked
    ) {
        if (modelContext.isNeutral() && cit.model().isEmpty()) return baked;

        ItemModelNode.Resolution resolution = context.findItemTree(options.getItemId())
            .map(tree -> modelContext.resolve(tree))
            .orElse(null);
        // A special leaf maps onto an existing hardcoded / block-entity render path (parse-and-hold);
        // an unknown special kind is diagnosed and dropped. Either way the baked
        // item - already served by its own path - is returned.
        if (resolution != null && resolution.modelId().isEmpty() && resolution.special().isPresent()) {
            resolution.special().get().resolveOrDrop();
            return baked;
        }
        // CIT whole-model override wins over the tree-resolved model.
        boolean fromCit = cit.model().isPresent();
        String modelId = cit.model().map(ResourceId::id)
            .orElseGet(() -> resolution != null ? resolution.modelId().orElse(null) : null);
        if (modelId == null) return baked;

        // Resolve the model id to its ModelData by FULL id (collision-free - a basename collapse would
        // map minecraft:optifine/cit/diamond_sword onto the vanilla diamond_sword). A CIT override that
        // is not a resolvable item model (e.g. an optifine/cit/ path outside models/item/) misses and
        // is diagnosed rather than silently rendering the wrong model.
        Optional<ModelData> model = context.findItemModel(modelId);
        if (model.isEmpty()) {
            if (fromCit)
                System.err.printf("CIT model override '%s' for item '%s' is not a resolvable item model - rendering the base item%n",
                    modelId, options.getItemId());
            return baked;
        }

        ModelData resolved = model.get();
        ConcurrentList<LayerTint> tints = resolution != null ? resolution.tints() : baked.tints();
        ConcurrentMap<String, String> sprites = resolved.getTextures()
            .entrySet()
            .stream()
            .collect(Concurrent.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().sprite()));
        return new Item(baked.id(), resolved, sprites,
            baked.maxDurability(), tints, baked.alwaysGlinted());
    }

}
