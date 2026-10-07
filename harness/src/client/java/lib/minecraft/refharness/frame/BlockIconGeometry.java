package lib.minecraft.refharness.frame;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.CompositeModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolver for the geometry a block's inventory icon is drawn from, shared by
 * {@link BlockFrameRenderer} and by the static block half of {@link BlockEntityFrameRenderer} - a
 * block entity does not stop a block from having an icon vanilla bakes from a block model.
 *
 * <p>Vanilla draws a block-item icon from the item model named by the stack's
 * {@code minecraft:item_model} component, baked at {@code BlockModelRotation.IDENTITY} - it never
 * consults a {@code BlockStateModel}, so no blockstate variant's rotation and no multipart assembly
 * reaches an icon. That is a different object from the one {@code BlockStateModelSet.get(state)}
 * hands back: for {@code oak_stairs} the blockstate model carries the default state's
 * {@code y: 270}, and for {@code oak_fence} it assembles a post and four arms where the item model
 * is {@code block/oak_fence_inventory}'s post and two.
 *
 * <p>An item qualifies when its definition, walked as asset-renderer walks it at its neutral gui
 * context, lands on a {@code minecraft:model} leaf naming a block model without passing through a
 * {@code composite}. A plain root naming one does, and so does a dispatch whose neutral branch names
 * one - {@code beehive}, {@code bee_nest} and {@code test_block} select on a block state and draw
 * their fallback block model in a slot. A leaf that names an {@code item/} model is a flat sprite in
 * the inventory (doors, wall torches, comparators, levers), a {@code special} leaf is a block entity,
 * and a composite paints every child rather than one block model; for all three the sweep keeps its
 * own 3D render off the blockstate model, there being no vanilla 3D block icon to reproduce. That
 * predicate reads the shipped {@code items/<name>.json} directly, so it is the same test
 * asset-renderer applies to the same file.
 *
 * <p>The quads themselves are the ones vanilla bakes for the leaf its own walk lands on.
 * {@link ItemModelResolver} resolves the item's stack at {@link ItemDisplayContext#GUI} into a
 * {@link TrackingItemStackRenderState}, which records every model the walk passes through, each
 * node before the branch it delegates to; the last is the leaf, and its private
 * {@link QuadCollection} field is read off it - so the reference carries vanilla's own bake rather
 * than a re-derivation of it, a select's fallback included.
 */
@Parity(claim = "harness-block-sweep", mode = Mode.DEMOTE)
@UtilityClass
final class BlockIconGeometry {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * Per-item-model-class cache of the private {@link QuadCollection} field, discovered by walking
     * the class hierarchy and matching by field type rather than by a fixed owner / name. An empty
     * optional records that a given model class holds no quads (special / composite / select /
     * empty models), so those are not re-scanned.
     */
    private static final Map<Class<?>, Optional<Field>> QUADS_FIELD = new ConcurrentHashMap<>();

    /**
     * Per-item-model-identifier cache of the block-model predicate, keyed by the
     * {@code minecraft:item_model} identifier so the shipped {@code items/<name>.json} is read once
     * per item across the whole sweep.
     */
    private static final Map<Identifier, Boolean> MODEL_ICON = new ConcurrentHashMap<>();

    private static boolean loggedFailure;

    /**
     * Replaces {@code parts} with the block's icon geometry when vanilla draws one from a block
     * model, leaving the collected blockstate parts untouched when it does not. The two are
     * alternative geometries for the same block, so this swaps rather than appends; the particle
     * material rides over from the first collected part, nothing in these paths drawing particles.
     *
     * @param client the active client, source of the model manager and resource manager
     * @param state the block state whose geometry is being submitted
     * @param parts the collected blockstate parts, replaced in place when an icon is found
     * @return whether the parts were replaced by the icon's
     */
    static boolean swapIn(Minecraft client, BlockState state, List<BlockStateModelPart> parts) {
        if (parts.isEmpty()) return false;
        Optional<QuadCollection> icon = resolve(client, state);
        if (icon.isEmpty()) return false;
        Material.Baked particle = parts.getFirst().particleMaterial();
        parts.clear();
        parts.add(new IconPart(icon.get(), particle));
        return true;
    }

    /**
     * The baked icon quads of a block whose inventory icon vanilla draws from a block model,
     * presented as the single {@link BlockStateModelPart} {@code submitBlockModel} consumes. Ambient
     * occlusion is off: it is a chunk-lighting term, and these paths submit at full-bright.
     *
     * @param quads the item model's baked quads, at the identity model state
     * @param particle the particle material carried over from the block's own blockstate part
     */
    private record IconPart(QuadCollection quads, Material.Baked particle) implements BlockStateModelPart {
        @Override
        public List<BakedQuad> getQuads(Direction face) { return quads.getQuads(face); }

        @Override
        public boolean useAmbientOcclusion() { return false; }

        @Override
        public Material.Baked particleMaterial() { return particle; }

        @Override
        public int materialFlags() { return quads.materialFlags(); }
    }

    /**
     * Resolves the quads vanilla's inventory icon for {@code state}'s block draws, or empty when
     * vanilla has no 3D icon for it and the sweep should render the blockstate model instead. Any
     * reflective or resource failure is swallowed (logged once) and degrades to empty, so a model
     * layout change can never break the sweep.
     *
     * @param client the active client, source of the model manager and resource manager
     * @param state the block state whose icon geometry to resolve
     * @return the icon's quads, or empty when vanilla's icon is not a block model
     */
    private static Optional<QuadCollection> resolve(Minecraft client, BlockState state) {
        try {
            ItemStack stack = new ItemStack(state.getBlock());
            Identifier modelId = stack.get(DataComponents.ITEM_MODEL);
            if (modelId == null) return Optional.empty();
            if (!isBlockModelIcon(client, modelId)) return Optional.empty();

            Optional<ItemModel> leaf = guiLeaf(client, stack);
            if (leaf.isEmpty()) return Optional.empty();

            Optional<Field> field = quadsField(leaf.get().getClass());
            if (field.isEmpty()) return Optional.empty();
            return Optional.ofNullable((QuadCollection) field.get().get(leaf.get()));
        } catch (ReflectiveOperationException | RuntimeException e) {
            logFailureOnce(state, e);
            return Optional.empty();
        }
    }

    /**
     * Resolves the item model vanilla's own walk lands on for a stack in a GUI slot. Every baked node
     * appends itself to a {@link TrackingItemStackRenderState}'s identity before it delegates, and a
     * leaf appends itself before anything it adds of its own, so the last {@link ItemModel} recorded is
     * the leaf - a plain root's own model, or the branch a dispatch selects.
     *
     * @param client the active client, source of the item model resolver and the level
     * @param stack the block's item stack
     * @return the leaf, or empty when the walk passes through a {@link CompositeModel} or records no model
     */
    private static Optional<ItemModel> guiLeaf(Minecraft client, ItemStack stack) {
        TrackingItemStackRenderState rendered = new TrackingItemStackRenderState();
        client.getItemModelResolver().updateForTopItem(rendered, stack, ItemDisplayContext.GUI, client.level, /*owner*/ null, /*seed*/ 0);
        ItemModel leaf = null;
        for (Object element : (List<?>) rendered.getModelIdentity()) {
            if (element instanceof CompositeModel) return Optional.empty();
            if (element instanceof ItemModel model) leaf = model;
        }
        return Optional.ofNullable(leaf);
    }

    /**
     * Reports whether an item's shipped definition walks to a block model alone - the population
     * whose inventory icon vanilla bakes from a block model. Reads
     * {@code assets/<ns>/items/<name>.json}; an absent or unreadable file answers {@code false}.
     *
     * @param client the active client, source of the resource manager
     * @param modelId the item's {@code minecraft:item_model} identifier
     * @return whether the item's icon is a block model
     */
    private static boolean isBlockModelIcon(Minecraft client, Identifier modelId) {
        return MODEL_ICON.computeIfAbsent(modelId, id -> {
            Identifier path = Identifier.fromNamespaceAndPath(id.getNamespace(), "items/" + id.getPath() + ".json");
            Optional<Resource> resource = client.getResourceManager().getResource(path);
            if (resource.isEmpty()) return false;
            try (BufferedReader reader = resource.get().openAsReader()) {
                JsonObject root = GsonHelper.parse(reader);
                if (!root.has("model")) return false;
                return neutralLeaf(GsonHelper.getAsJsonObject(root, "model"))
                    .map(ref -> Identifier.parse(ref).getPath().startsWith("block/"))
                    .orElse(false);
            } catch (Exception e) {
                LOG.warn("BlockIconGeometry: unreadable item definition {}", path, e);
                return false;
            }
        });
    }

    /**
     * Walks one item-definition node as asset-renderer's neutral gui context walks it, to the model
     * leaf it lands on. A {@code condition} takes {@code on_false}, which is where that context sends
     * every condition vanilla ships; a {@code select} takes its {@code display_context} {@code gui}
     * case or its {@code context_dimension} overworld case where it keys on one, and its
     * {@code fallback} otherwise; a {@code range_dispatch} takes the highest entry whose threshold is
     * at or below {@code 0}, else its {@code fallback}. A {@code composite} refuses, and a
     * {@code special}, {@code empty} or {@code bundle/selected_item} node lands on no model.
     *
     * @param node the definition node
     * @return the model reference the walk lands on, or empty when it lands on none
     */
    private static Optional<String> neutralLeaf(JsonObject node) {
        return switch (vanillaPath(GsonHelper.getAsString(node, "type", ""))) {
            case "model" -> Optional.of(GsonHelper.getAsString(node, "model", ""));
            case "condition" -> child(node, "on_false").flatMap(BlockIconGeometry::neutralLeaf);
            case "select" -> selectedBranch(node).flatMap(BlockIconGeometry::neutralLeaf);
            case "range_dispatch" -> rangeBranch(node).flatMap(BlockIconGeometry::neutralLeaf);
            default -> Optional.empty();
        };
    }

    /**
     * The branch a {@code select} takes at the neutral gui context: the case naming {@code gui} for a
     * {@code display_context} select, the case naming the overworld for a {@code context_dimension}
     * one, and the fallback for every other property or where no case matches.
     */
    private static Optional<JsonObject> selectedBranch(JsonObject select) {
        String property = vanillaPath(GsonHelper.getAsString(select, "property", ""));
        Optional<String> key = switch (property) {
            case "display_context" -> Optional.of("gui");
            case "context_dimension" -> Optional.of(Level.OVERWORLD.identifier().toString());
            default -> Optional.empty();
        };
        if (key.isPresent()) {
            for (JsonElement option : GsonHelper.getAsJsonArray(select, "cases", new JsonArray())) {
                if (!option.isJsonObject()) continue;
                JsonElement when = option.getAsJsonObject().get("when");
                List<JsonElement> values = when == null ? List.of() : when.isJsonArray() ? when.getAsJsonArray().asList() : List.of(when);
                for (JsonElement value : values)
                    if (value.isJsonPrimitive() && caseKey(property, value.getAsString()).equals(key.get()))
                        return child(option.getAsJsonObject(), "model");
            }
        }
        return child(select, "fallback");
    }

    /** The branch a {@code range_dispatch} takes at value {@code 0}: the first entry of the highest threshold at or below it, else the fallback. */
    private static Optional<JsonObject> rangeBranch(JsonObject range) {
        JsonObject best = null;
        float bestThreshold = 0f;
        for (JsonElement entry : GsonHelper.getAsJsonArray(range, "entries", new JsonArray())) {
            if (!entry.isJsonObject()) continue;
            float threshold = GsonHelper.getAsFloat(entry.getAsJsonObject(), "threshold", Float.NaN);
            if (threshold <= 0f && (best == null || threshold > bestThreshold)) {
                best = entry.getAsJsonObject();
                bestThreshold = threshold;
            }
        }
        return best != null ? child(best, "model") : child(range, "fallback");
    }

    /** A select case value as the neutral walk compares it: a dimension qualified to {@code minecraft:} when bare, any other value as written. */
    private static String caseKey(String property, String value) {
        return property.equals("context_dimension") ? Identifier.parse(value).toString() : value;
    }

    /** A node's object-valued member, or empty when it has none. */
    private static Optional<JsonObject> child(JsonObject node, String key) {
        JsonElement member = node.get(key);
        return member != null && member.isJsonObject() ? Optional.of(member.getAsJsonObject()) : Optional.empty();
    }

    /** A node type or dispatch property's path when it is in vanilla's namespace, a bare id included, else the empty string, which names nothing. */
    private static String vanillaPath(String id) {
        Identifier parsed = Identifier.tryParse(id);
        return parsed != null && parsed.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) ? parsed.getPath() : "";
    }

    private static Optional<Field> quadsField(Class<?> modelClass) {
        return QUADS_FIELD.computeIfAbsent(modelClass, BlockIconGeometry::findQuadsField);
    }

    private static Optional<Field> findQuadsField(Class<?> modelClass) {
        for (Class<?> c = modelClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == QuadCollection.class) {
                    f.setAccessible(true);
                    return Optional.of(f);
                }
            }
        }
        return Optional.empty();
    }

    private static void logFailureOnce(BlockState state, Throwable e) {
        if (loggedFailure) return;
        loggedFailure = true;
        LOG.warn("BlockIconGeometry: icon geometry lookup failed for {}; falling back to the blockstate model", state, e);
    }
}
