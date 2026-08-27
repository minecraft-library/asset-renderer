package lib.minecraft.renderer.pipeline.pack.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.pack.item.ItemModelNode;
import lib.minecraft.renderer.asset.pack.item.SpecialTransform;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * Reads the {@code model} object of an {@code items/*.json} definition into an immutable
 * {@link ItemModelNode} tree, dispatching on the (namespace-stripped) {@code type} discriminator and
 * recursing through the deserialization context. Unknown node types and absent object branches become
 * {@link ItemModelNode.Empty#INSTANCE}, so the walker never dereferences a missing child.
 * <p>
 * Registered globally so both the top-level {@code GSON.fromJson(model, }{@link ItemModelNode}{@code
 * .class)} read and every recursive {@code context.deserialize} child resolve through this one adapter.
 * The tree depth is bounded by the loader's own JSON parse (which rejects pathologically nested files
 * before this runs), so no explicit depth cap is carried here.
 *
 * <p><b>Parity.</b> Registered by a service file and reached only through the contributor that
 * names it, so no constant pool carries an edge to it. It parses the item model tree, which a block
 * icon is baked from as much as an item is drawn from.
 */
@Parity(subject = {Subject.BLOCK, Subject.ENTITY, Subject.ITEM, Subject.MENU})
public final class ItemModelNodeDeserializer implements JsonDeserializer<ItemModelNode> {

    @Override
    public @NotNull ItemModelNode deserialize(@NotNull JsonElement json, @NotNull Type type, @NotNull JsonDeserializationContext context) {
        if (!json.isJsonObject()) return ItemModelNode.Empty.INSTANCE;
        JsonObject node = json.getAsJsonObject();

        return switch (strip(string(node, "type"))) {
            case "model" -> new ItemModelNode.Model(string(node, "model"), tints(node, context));
            case "condition" -> new ItemModelNode.Condition(
                string(node, "property"), string(node, "component"),
                child(node, "on_true", context), child(node, "on_false", context));
            case "select" -> new ItemModelNode.Select(
                string(node, "property"), string(node, "block_state_property"),
                cases(node, context), child(node, "fallback", context));
            case "range_dispatch" -> new ItemModelNode.RangeDispatch(
                string(node, "property"), floatValue(node, "scale", 1f), string(node, "target"),
                intValue(node, "index", 0), entries(node, context), child(node, "fallback", context));
            case "composite" -> new ItemModelNode.Composite(models(node, context));
            case "special" -> special(node);
            case "bundle/selected_item" -> new ItemModelNode.Bundle();
            default -> ItemModelNode.Empty.INSTANCE;
        };
    }

    /** Deserialises a {@code special} node, collecting its inline kind fields off the inner {@code model}. */
    private static @NotNull ItemModelNode special(@NotNull JsonObject node) {
        JsonElement innerElement = node.get("model");
        JsonObject inner = innerElement != null && innerElement.isJsonObject() ? innerElement.getAsJsonObject() : new JsonObject();
        String kind = string(inner, "type");
        ConcurrentMap<String, String> fields = inner.entrySet()
            .stream()
            .filter(entry -> !entry.getKey().equals("type"))
            .filter(entry -> entry.getValue().isJsonPrimitive())
            .collect(Concurrent.toUnmodifiableLinkedMap(Map.Entry::getKey, entry -> entry.getValue().getAsString()));
        return new ItemModelNode.Special(kind, string(node, "base"), fields, transform(node));
    }

    /** Deserialises a node's {@code transformation}, or {@link SpecialTransform#IDENTITY} when absent / not an object. */
    private static @NotNull SpecialTransform transform(@NotNull JsonObject node) {
        JsonElement value = node.get("transformation");
        if (value == null || !value.isJsonObject()) return SpecialTransform.IDENTITY;
        JsonObject transformation = value.getAsJsonObject();
        return new SpecialTransform(
            floatArray(transformation, "left_rotation", SpecialTransform.IDENTITY.leftRotation()),
            floatArray(transformation, "right_rotation", SpecialTransform.IDENTITY.rightRotation()),
            floatArray(transformation, "scale", SpecialTransform.IDENTITY.scale()),
            floatArray(transformation, "translation", SpecialTransform.IDENTITY.translation()));
    }

    /** Deserialises the {@code cases[]} array of a {@code select} node, skipping non-object entries. */
    private static @NotNull ConcurrentList<ItemModelNode.Select.Case> cases(@NotNull JsonObject node, @NotNull JsonDeserializationContext context) {
        return array(node, "cases").asList()
            .stream()
            .filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .map(element -> new ItemModelNode.Select.Case(when(element.get("when")), child(element, "model", context)))
            .collect(Concurrent.toUnmodifiableList());
    }

    /** Deserialises the {@code entries[]} array of a {@code range_dispatch} node, skipping non-object entries. */
    private static @NotNull ConcurrentList<ItemModelNode.RangeDispatch.Entry> entries(@NotNull JsonObject node, @NotNull JsonDeserializationContext context) {
        return array(node, "entries").asList()
            .stream()
            .filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .map(element -> new ItemModelNode.RangeDispatch.Entry(floatValue(element, "threshold", 0f), child(element, "model", context)))
            .collect(Concurrent.toUnmodifiableList());
    }

    /** Deserialises the {@code models[]} array of a {@code composite} node, skipping non-object children. */
    private static @NotNull ConcurrentList<ItemModelNode> models(@NotNull JsonObject node, @NotNull JsonDeserializationContext context) {
        return array(node, "models").asList()
            .stream()
            .filter(JsonElement::isJsonObject)
            .map(element -> context.<ItemModelNode>deserialize(element, ItemModelNode.class))
            .collect(Concurrent.toUnmodifiableList());
    }

    /** Reads a {@code when} value: a single string, or an array of strings; non-primitive entries drop. */
    private static @NotNull ConcurrentList<String> when(JsonElement when) {
        if (when == null) return Concurrent.newUnmodifiableList();
        if (when.isJsonArray())
            return when.getAsJsonArray().asList()
                .stream()
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsString)
                .collect(Concurrent.toUnmodifiableList());
        return when.isJsonPrimitive() ? Concurrent.newUnmodifiableList(when.getAsString()) : Concurrent.newUnmodifiableList();
    }

    /** Deserialises the {@code tints[]} array of a {@code model} node into ordered per-layer tint rules. */
    private static @NotNull ConcurrentList<LayerTint> tints(@NotNull JsonObject node, @NotNull JsonDeserializationContext context) {
        return array(node, "tints").asList()
            .stream()
            .filter(JsonElement::isJsonObject)
            .map(element -> context.<LayerTint>deserialize(element, LayerTint.class))
            .collect(Concurrent.toUnmodifiableList());
    }

    /** Reads {@code node[key]} as a child node, or {@link ItemModelNode.Empty#INSTANCE} when absent / not an object. */
    private static @NotNull ItemModelNode child(@NotNull JsonObject node, @NotNull String key, @NotNull JsonDeserializationContext context) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonObject() ? context.deserialize(value, ItemModelNode.class) : ItemModelNode.Empty.INSTANCE;
    }

    /** The array member under {@code key}, or an empty array when absent / not a JSON array. */
    private static @NotNull JsonArray array(@NotNull JsonObject node, @NotNull String key) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    /** The string member under {@code key} when it is a JSON primitive, or {@code ""} - matching {@code getString(key, "")}. */
    private static @NotNull String string(@NotNull JsonObject node, @NotNull String key) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /**
     * Reads {@code node[key]} as a float, or {@code fallback} when it is absent, a non-primitive, or a
     * non-numeric primitive (a quoted {@code "min"} threshold degrades rather than aborting the load).
     */
    private static float floatValue(@NotNull JsonObject node, @NotNull String key, float fallback) {
        JsonElement value = node.get(key);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsFloat();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** Reads {@code node[key]} as an int, or {@code fallback} when it is absent, a non-primitive, or a non-numeric primitive. */
    private static int intValue(@NotNull JsonObject node, @NotNull String key, int fallback) {
        JsonElement value = node.get(key);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsInt();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * Reads {@code node[key]} as a float array, or a copy of {@code fallback} when it is absent, not an
     * array, or carries a non-numeric / nested element (matching the former per-array degrade).
     */
    private static float @NotNull [] floatArray(@NotNull JsonObject node, @NotNull String key, float @NotNull [] fallback) {
        JsonElement value = node.get(key);
        if (value == null || !value.isJsonArray()) return fallback.clone();
        JsonArray array = value.getAsJsonArray();
        float[] out = new float[array.size()];
        try {
            for (int i = 0; i < array.size(); i++) out[i] = array.get(i).getAsFloat();
        } catch (RuntimeException ex) {
            // A non-numeric or nested element (getAsFloat throws NumberFormatException /
            // UnsupportedOperationException / IllegalStateException): fall back to the default array.
            return fallback.clone();
        }
        return out;
    }

    /** Strips a leading {@code minecraft:} namespace so type matching accepts both id forms. */
    private static @NotNull String strip(@NotNull String value) {
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(colon + 1);
    }

}
