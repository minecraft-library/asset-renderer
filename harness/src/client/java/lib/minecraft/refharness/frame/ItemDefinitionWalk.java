package lib.minecraft.refharness.frame;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TestBlock;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The neutral walk of an item's shipped definition, {@code assets/<ns>/items/<name>.json}, as
 * asset-renderer walks it at its neutral gui context - the file read once per item, and the two
 * questions the block sweep asks of it answered off that one read.
 *
 * <p>The walk takes a {@code condition} to {@code on_false}, a {@code select} to its {@code gui}
 * display-context case, its overworld dimension case or its fallback, and a {@code range_dispatch} to
 * the entry it holds at value {@code 0}, and it refuses a {@code composite}. Where it lands on a
 * {@code minecraft:model} leaf naming a block model, {@link #isBlockModelIcon} answers that vanilla
 * bakes the item's inventory icon from a block model, which is the population whose quads
 * {@link BlockIconGeometry} reads off vanilla's own walk. The predicate reads the shipped file rather
 * than a runtime proxy, so it is the same test asset-renderer applies to the same file.
 *
 * <p>{@link #stateCases} lists the {@code minecraft:block_state} components that steer a block-model
 * icon onto another block model - a full {@code beehive} or {@code bee_nest}, a {@code test_block} in
 * {@code log}, {@code fail} or {@code accept} mode - which the block sweep draws once each after the
 * plain icons.
 */
@Parity(claim = "harness-block-sweep", mode = Mode.DEMOTE)
@UtilityClass
public final class ItemDefinitionWalk {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * Per-item-model-identifier cache of the shipped definition's root {@code model} node, keyed by
     * the {@code minecraft:item_model} identifier so the shipped {@code items/<name>.json} is read
     * once per item across the whole sweep. Empty records an item with no readable definition.
     */
    private static final Map<Identifier, Optional<JsonObject>> DEFINITIONS = new ConcurrentHashMap<>();

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
            LOG.warn("ItemDefinitionWalk: block-state cases unreadable for {}; drawing its plain icon alone", block, e);
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
                .flatMap(ItemDefinitionWalk::neutralLeaf)
                .filter(ItemDefinitionWalk::isBlockModel)
                .isPresent();
            if (!blockModel) continue;
            for (JsonElement value : whenValues(option.getAsJsonObject())) {
                if (!value.isJsonPrimitive()) continue;
                Optional<BlockItemStateProperties> component = property == null
                    ? Optional.empty()
                    : stackState(property, value.getAsString());
                component.ifPresentOrElse(out::add, () -> LOG.warn(
                    "ItemDefinitionWalk: {} selects on {}={}, a value its block cannot hold; no reference drawn for it",
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
     * Reports whether an item's shipped definition walks to a block model alone - the population
     * whose inventory icon vanilla bakes from a block model. Reads
     * {@code assets/<ns>/items/<name>.json}; an absent or unreadable file answers {@code false}.
     *
     * @param client the active client, source of the resource manager
     * @param modelId the item's {@code minecraft:item_model} identifier
     * @return whether the item's icon is a block model
     */
    static boolean isBlockModelIcon(Minecraft client, Identifier modelId) {
        return definition(client, modelId)
            .flatMap(ItemDefinitionWalk::neutralLeaf)
            .filter(ItemDefinitionWalk::isBlockModel)
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
                LOG.warn("ItemDefinitionWalk: unreadable item definition {}", path, e);
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
            case "condition" -> child(node, "on_false").flatMap(ItemDefinitionWalk::neutralLeaf);
            case "select" -> selectedBranch(node).flatMap(ItemDefinitionWalk::neutralLeaf);
            case "range_dispatch" -> rangeBranch(node).flatMap(ItemDefinitionWalk::neutralLeaf);
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
}
