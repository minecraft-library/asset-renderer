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
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TestBlock;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * one - {@code beehive}, {@code bee_nest} and {@code test_block} select on the stack's block state
 * and draw their fallback block model for a stack carrying none. A leaf that names an
 * {@code item/} model is a flat sprite in the inventory (doors, wall torches, comparators, levers),
 * a {@code special} leaf is a block entity, and a composite paints every child rather than one block
 * model; for all three the sweep keeps its own 3D render off the blockstate model, there being no
 * vanilla 3D block icon to reproduce. That predicate reads the shipped {@code items/<name>.json}
 * directly, so it is the same test asset-renderer applies to the same file.
 *
 * <p>The quads themselves are the ones vanilla bakes for the leaf its own walk lands on.
 * {@link ItemModelResolver} resolves the stack it is given at {@link ItemDisplayContext#GUI} into a
 * {@link TrackingItemStackRenderState}, which records every model the walk passes through, each
 * node before the branch it delegates to; the last is the leaf, and its private
 * {@link QuadCollection} field is read off it - so the reference carries vanilla's own bake rather
 * than a re-derivation of it, a select's fallback included.
 *
 * <p>The stack is the caller's, so a stack carrying a component a dispatch selects on draws the
 * branch vanilla picks for it. {@link #stateCases} lists the {@code minecraft:block_state}
 * components that steer a block-model icon onto another block model - a full {@code beehive} or
 * {@code bee_nest}, a {@code test_block} in {@code log}, {@code fail} or {@code accept} mode - and
 * a stack carrying one draws that case only when vanilla's own select reads the component; a walk
 * that ignored it would land on the fallback and draw the plain icon.
 */
@Parity(claim = "harness-block-sweep", mode = Mode.DEMOTE)
@UtilityClass
public final class BlockIconGeometry {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * Per-item-model-class cache of the private {@link QuadCollection} field, discovered by walking
     * the class hierarchy and matching by field type rather than by a fixed owner / name. An empty
     * optional records that a given model class holds no quads (special / composite / select /
     * empty models), so those are not re-scanned.
     */
    private static final Map<Class<?>, Optional<Field>> QUADS_FIELD = new ConcurrentHashMap<>();

    /**
     * Per-item-model-identifier cache of the shipped definition's root {@code model} node, keyed by
     * the {@code minecraft:item_model} identifier so the shipped {@code items/<name>.json} is read
     * once per item across the whole sweep. Empty records an item with no readable definition.
     */
    private static final Map<Identifier, Optional<JsonObject>> DEFINITIONS = new ConcurrentHashMap<>();

    private static boolean loggedFailure;

    /**
     * Replaces {@code parts} with the icon geometry vanilla draws for {@code stack} when it draws one
     * from a block model, leaving the collected blockstate parts untouched when it does not. The two
     * are alternative geometries for the same block, so this swaps rather than appends; the particle
     * material rides over from the first collected part, nothing in these paths drawing particles.
     *
     * @param client the active client, source of the model manager and resource manager
     * @param stack the stack the icon is read from - the block's own item, carrying whatever
     *              components the subject gives it
     * @param parts the collected blockstate parts, replaced in place when an icon is found
     * @return whether the parts were replaced by the icon's
     */
    static boolean swapIn(Minecraft client, ItemStack stack, List<BlockStateModelPart> parts) {
        if (parts.isEmpty()) return false;
        Optional<QuadCollection> icon = resolve(client, stack);
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
     * Resolves the quads vanilla's inventory icon for {@code stack} draws, or empty when vanilla has
     * no 3D icon for its block and the sweep should render the blockstate model instead. Any
     * reflective or resource failure is swallowed (logged once) and degrades to empty, so a model
     * layout change can never break the sweep.
     *
     * @param client the active client, source of the model manager and resource manager
     * @param stack the stack whose icon geometry to resolve
     * @return the icon's quads, or empty when vanilla's icon is not a block model
     */
    private static Optional<QuadCollection> resolve(Minecraft client, ItemStack stack) {
        try {
            Identifier modelId = stack.get(DataComponents.ITEM_MODEL);
            if (modelId == null) return Optional.empty();
            if (!isBlockModelIcon(client, modelId)) return Optional.empty();

            Optional<ItemModel> leaf = guiLeaf(client, stack);
            if (leaf.isEmpty()) return Optional.empty();

            Optional<Field> field = quadsField(leaf.get().getClass());
            if (field.isEmpty()) return Optional.empty();
            return Optional.ofNullable((QuadCollection) field.get().get(leaf.get()));
        } catch (ReflectiveOperationException | RuntimeException e) {
            logFailureOnce(stack, e);
            return Optional.empty();
        }
    }

    /**
     * Lists the {@code minecraft:block_state} components a stack of {@code block} carries to steer
     * its icon off the plain branch onto another block model: one per value a case names, for every
     * {@code block_state} select on the definition's neutral path whose case lands on a block model.
     * Each is built the way vanilla builds the component for a picked full hive or
     * {@link TestBlock#setModeOnStack}, through the block's own property, so a value the block cannot
     * hold is logged and left out. Empty for an icon that is not a block model, for a definition with
     * no such select, and on any failure, which is logged.
     *
     * @param client the active client, source of the resource manager
     * @param block the block whose icon cases to list
     * @return the components, in the order the definition declares their cases
     */
    public static List<BlockItemStateProperties> stateCases(Minecraft client, Block block) {
        try {
            Identifier modelId = new ItemStack(block).get(DataComponents.ITEM_MODEL);
            if (modelId == null || !isBlockModelIcon(client, modelId)) return List.of();
            Set<BlockItemStateProperties> cases = new LinkedHashSet<>();
            definition(client, modelId).ifPresent(root -> collectStateCases(root, block, cases));
            return List.copyOf(cases);
        } catch (RuntimeException e) {
            LOG.warn("BlockIconGeometry: block-state cases unreadable for {}; drawing its plain icon alone", block, e);
            return List.of();
        }
    }

    /**
     * Walks the neutral path from {@code node} as {@link #neutralLeaf} does, adding the cases of
     * every {@code block_state} select it passes. A stack carrying one block-state property reaches
     * each case by its value alone wherever no node above the select tests the
     * {@code block_state} component itself, which no vanilla definition does.
     *
     * @param node the definition node
     * @param block the block whose properties the cases name
     * @param out the components collected so far, in declaration order
     */
    private static void collectStateCases(JsonObject node, Block block, Set<BlockItemStateProperties> out) {
        Optional<JsonObject> next = switch (vanillaPath(GsonHelper.getAsString(node, "type", ""))) {
            case "condition" -> child(node, "on_false");
            case "select" -> {
                if (vanillaPath(GsonHelper.getAsString(node, "property", "")).equals("block_state"))
                    addStateCases(node, block, out);
                yield selectedBranch(node);
            }
            case "range_dispatch" -> rangeBranch(node);
            default -> Optional.empty();
        };
        next.ifPresent(branch -> collectStateCases(branch, block, out));
    }

    /**
     * Adds one component per value a {@code block_state} select's cases name, for each case whose
     * branch lands on a block model.
     *
     * @param select the {@code block_state} select
     * @param block the block whose property the select names
     * @param out the components collected so far, in declaration order
     */
    private static void addStateCases(JsonObject select, Block block, Set<BlockItemStateProperties> out) {
        String name = GsonHelper.getAsString(select, "block_state_property", "");
        Property<?> property = block.getStateDefinition().getProperty(name);
        for (JsonElement option : GsonHelper.getAsJsonArray(select, "cases", new JsonArray())) {
            if (!option.isJsonObject()) continue;
            boolean blockModel = child(option.getAsJsonObject(), "model")
                .flatMap(BlockIconGeometry::neutralLeaf)
                .filter(BlockIconGeometry::isBlockModel)
                .isPresent();
            if (!blockModel) continue;
            for (JsonElement value : whenValues(option.getAsJsonObject())) {
                if (!value.isJsonPrimitive()) continue;
                Optional<BlockItemStateProperties> component = property == null
                    ? Optional.empty()
                    : stackState(property, value.getAsString());
                component.ifPresentOrElse(out::add, () -> LOG.warn(
                    "BlockIconGeometry: {} selects on {}={}, a value its block cannot hold; no reference drawn for it",
                    block, name, value.getAsString()));
            }
        }
    }

    /**
     * Builds the {@code minecraft:block_state} component carrying one property at one value, as
     * {@link BlockItemStateProperties#with} builds it for vanilla's own stacks.
     *
     * @param property the block's property
     * @param value the value's serialized name
     * @param <T> the property's value type
     * @return the component, or empty when the property holds no value of that name
     */
    private static <T extends Comparable<T>> Optional<BlockItemStateProperties> stackState(Property<T> property, String value) {
        return property.getValue(value).map(parsed -> BlockItemStateProperties.EMPTY.with(property, parsed));
    }

    /**
     * Resolves the item model vanilla's own walk lands on for a stack in a GUI slot. Every baked node
     * appends itself to a {@link TrackingItemStackRenderState}'s identity before it delegates, and a
     * leaf appends itself before anything it adds of its own, so the last {@link ItemModel} recorded is
     * the leaf - a plain root's own model, or the branch a dispatch selects.
     *
     * @param client the active client, source of the item model resolver and the level
     * @param stack the stack to walk, its components included
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
        return definition(client, modelId)
            .flatMap(BlockIconGeometry::neutralLeaf)
            .filter(BlockIconGeometry::isBlockModel)
            .isPresent();
    }

    /**
     * Reads an item's shipped definition, {@code assets/<ns>/items/<name>.json}, to its root
     * {@code model} node, once per item. An absent file or one with no {@code model} answers empty,
     * and so does an unreadable one, which is logged.
     *
     * @param client the active client, source of the resource manager
     * @param modelId the item's {@code minecraft:item_model} identifier
     * @return the definition's root model node, or empty when none is readable
     */
    private static Optional<JsonObject> definition(Minecraft client, Identifier modelId) {
        return DEFINITIONS.computeIfAbsent(modelId, id -> {
            Identifier path = Identifier.fromNamespaceAndPath(id.getNamespace(), "items/" + id.getPath() + ".json");
            Optional<Resource> resource = client.getResourceManager().getResource(path);
            if (resource.isEmpty()) return Optional.empty();
            try (BufferedReader reader = resource.get().openAsReader()) {
                JsonObject root = GsonHelper.parse(reader);
                if (!root.has("model")) return Optional.empty();
                return Optional.of(GsonHelper.getAsJsonObject(root, "model"));
            } catch (Exception e) {
                LOG.warn("BlockIconGeometry: unreadable item definition {}", path, e);
                return Optional.empty();
            }
        });
    }

    /** Whether a {@code model} leaf's reference names a block model. */
    private static boolean isBlockModel(String ref) {
        return Identifier.parse(ref).getPath().startsWith("block/");
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
                for (JsonElement value : whenValues(option.getAsJsonObject()))
                    if (value.isJsonPrimitive() && caseKey(property, value.getAsString()).equals(key.get()))
                        return child(option.getAsJsonObject(), "model");
            }
        }
        return child(select, "fallback");
    }

    /** The values a select case's {@code when} names: each element of an array, the one value otherwise, none when it is absent. */
    private static List<JsonElement> whenValues(JsonObject option) {
        JsonElement when = option.get("when");
        return when == null ? List.of() : when.isJsonArray() ? when.getAsJsonArray().asList() : List.of(when);
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

    private static void logFailureOnce(ItemStack stack, Throwable e) {
        if (loggedFailure) return;
        loggedFailure = true;
        LOG.warn("BlockIconGeometry: icon geometry lookup failed for {} {}; falling back to the blockstate model", stack, stack.getComponentsPatch(), e);
    }
}
