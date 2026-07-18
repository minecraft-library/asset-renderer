package lib.minecraft.renderer.pipeline.pack.item;

import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.json.JsonNode;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the {@code model} object of an {@code items/*.json} definition into an immutable
 * {@link ItemModelNode} tree. Depth-capped against pathological pack nesting;
 * unknown node types and absent branches become {@link ItemModelNode.Empty}, so the walker never
 * dereferences a missing child.
 *
 * <p>Tint parsing maps {@code dye} / {@code potion} / {@code firework} / {@code constant} to their
 * {@link LayerTint} variants, and every dynamic source this renderer cannot resolve ({@code grass},
 * {@code map_color}, {@code custom_model_data}, ...) becomes a white {@link LayerTint.Constant} so
 * it renders untinted.
 */
@UtilityClass
public class ItemModelParser {

    /**
     * Maximum node-tree depth. Covers every vanilla tree (max height ~5) with wide margin while
     * bounding recursion against a pathological pack; a deeper branch is truncated to
     * {@link ItemModelNode.Empty}. (The loader's Gson parse already rejects the truly degenerate
     * files - Hypixel+ 255-level {@code player_head.json} - before this runs.)
     */
    private static final int MAX_DEPTH = 256;

    /**
     * Parses an item-definition {@code model} object into a node tree.
     *
     * @param model the {@code model} object from an {@code items/*.json} file
     * @return the parsed root node, or {@link ItemModelNode.Empty} for an unknown or too-deep node
     */
    public static @NotNull ItemModelNode parse(@NotNull JsonNode model) {
        return parse(model, 0);
    }

    private static @NotNull ItemModelNode parse(@NotNull JsonNode node, int depth) {
        if (depth >= MAX_DEPTH) return ItemModelNode.Empty.INSTANCE;
        String type = strip(node.getString("type", ""));
        return switch (type) {
            case "model" -> new ItemModelNode.Model(node.getString("model", ""), parseTints(node));
            case "condition" -> new ItemModelNode.Condition(
                node.getString("property", ""), node.getString("component", ""),
                child(node, "on_true", depth), child(node, "on_false", depth));
            case "select" -> new ItemModelNode.Select(
                node.getString("property", ""), node.getString("block_state_property", ""),
                parseCases(node, depth), child(node, "fallback", depth));
            case "range_dispatch" -> new ItemModelNode.RangeDispatch(
                node.getString("property", ""), floatValue(node, "scale", 1f), node.getString("target", ""),
                parseEntries(node, depth), child(node, "fallback", depth));
            case "composite" -> new ItemModelNode.Composite(parseModels(node, depth));
            case "special" -> parseSpecial(node);
            case "bundle/selected_item" -> new ItemModelNode.Bundle();
            default -> ItemModelNode.Empty.INSTANCE;
        };
    }

    /** Parses a {@code special} node into a {@link ItemModelNode.Special}, collecting its inline kind fields. */
    private static @NotNull ItemModelNode parseSpecial(@NotNull JsonNode node) {
        JsonNode inner = node.findObject("model").orElseGet(JsonNode::object);
        String kind = inner.getString("type", "");
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : inner.members()) {
            if (entry.getKey().equals("type")) continue;
            JsonNode value = entry.getValue();
            if (value.isPrimitive()) fields.put(entry.getKey(), value.stringValue().orElseThrow());
        }
        return new ItemModelNode.Special(kind, node.getString("base", ""), Map.copyOf(fields), parseTransform(node));
    }

    /** Parses a node's {@code transformation}, or {@link SpecialTransform#IDENTITY} when absent / malformed. */
    private static @NotNull SpecialTransform parseTransform(@NotNull JsonNode node) {
        JsonNode t = node.get("transformation");
        if (t == null || !t.isObject()) return SpecialTransform.IDENTITY;
        return new SpecialTransform(
            floatArray(t, "left_rotation", SpecialTransform.IDENTITY.leftRotation()),
            floatArray(t, "right_rotation", SpecialTransform.IDENTITY.rightRotation()),
            floatArray(t, "scale", SpecialTransform.IDENTITY.scale()),
            floatArray(t, "translation", SpecialTransform.IDENTITY.translation()));
    }

    private static @NotNull List<ItemModelNode.Select.Case> parseCases(@NotNull JsonNode node, int depth) {
        List<ItemModelNode.Select.Case> cases = new ArrayList<>();
        JsonNode array = node.get("cases");
        if (array != null && array.isArray())
            for (JsonNode element : array.elements()) {
                if (!element.isObject()) continue;
                cases.add(new ItemModelNode.Select.Case(parseWhen(element.get("when")), child(element, "model", depth)));
            }
        return List.copyOf(cases);
    }

    private static @NotNull List<ItemModelNode.RangeDispatch.Entry> parseEntries(@NotNull JsonNode node, int depth) {
        List<ItemModelNode.RangeDispatch.Entry> entries = new ArrayList<>();
        JsonNode array = node.get("entries");
        if (array != null && array.isArray())
            for (JsonNode element : array.elements()) {
                if (!element.isObject()) continue;
                entries.add(new ItemModelNode.RangeDispatch.Entry(floatValue(element, "threshold", 0f), child(element, "model", depth)));
            }
        return List.copyOf(entries);
    }

    private static @NotNull List<ItemModelNode> parseModels(@NotNull JsonNode node, int depth) {
        List<ItemModelNode> models = new ArrayList<>();
        JsonNode array = node.get("models");
        if (array != null && array.isArray())
            for (JsonNode element : array.elements())
                if (element.isObject()) models.add(parse(element, depth + 1));
        return List.copyOf(models);
    }

    /** Parses a {@code when} value: a single string, or an array of strings. */
    private static @NotNull List<String> parseWhen(@Nullable JsonNode when) {
        if (when == null) return List.of();
        if (when.isArray()) {
            List<String> keys = new ArrayList<>();
            for (JsonNode element : when.elements())
                if (element.isPrimitive()) keys.add(element.stringValue().orElseThrow());
            return List.copyOf(keys);
        }
        return when.isPrimitive() ? List.of(when.stringValue().orElseThrow()) : List.of();
    }

    /** Parses the {@code tints[]} array of a {@code model} node into ordered per-layer tint rules. */
    private static @NotNull List<LayerTint> parseTints(@NotNull JsonNode model) {
        JsonNode array = model.get("tints");
        if (array == null || !array.isArray()) return List.of();
        List<LayerTint> tints = new ArrayList<>();
        for (JsonNode element : array.elements())
            if (element.isObject()) tints.add(parseTint(element));
        return List.copyOf(tints);
    }

    /**
     * Parses one {@code tints[]} entry into its {@link LayerTint} variant. Source types this renderer
     * cannot resolve dynamically ({@code grass}, {@code map_color}, {@code custom_model_data}, ...)
     * become a white {@link LayerTint.Constant} - rendered untinted rather than guessing.
     */
    private static @NotNull LayerTint parseTint(@NotNull JsonNode tint) {
        return switch (strip(tint.getString("type", ""))) {
            case "dye" -> new LayerTint.Dye(toArgb(tint, "default"));
            case "potion" -> new LayerTint.Potion(toArgb(tint, "default"));
            case "firework" -> new LayerTint.Firework(toArgb(tint, "default"));
            case "constant" -> new LayerTint.Constant(toArgb(tint, "value"));
            default -> new LayerTint.Constant(0xFFFFFFFF);
        };
    }

    /**
     * Reads an RGB tint colour from {@code tint[key]} and forces opaque alpha. Masks to 24 bits and
     * OR-s {@code 0xFF000000} so every source normalises to the ARGB the multiply-tint blend expects.
     * Defaults to white when the key is absent or non-numeric (a pack author writing the colour as a
     * quoted hex string {@code "#ffffff"} degrades to white rather than aborting the whole load).
     */
    private static int toArgb(@NotNull JsonNode tint, @NotNull String key) {
        JsonNode value = tint.get(key);
        if (value == null || !value.isPrimitive()) return 0xFFFFFFFF;
        try {
            return 0xFF000000 | (value.toGson().getAsInt() & 0xFFFFFF);
        } catch (NumberFormatException ex) {
            return 0xFFFFFFFF;
        }
    }

    /** Parses {@code object[key]} as a child node, or {@link ItemModelNode.Empty} when absent / not an object. */
    private static @NotNull ItemModelNode child(@NotNull JsonNode object, @NotNull String key, int depth) {
        return object.findObject(key).map(child -> parse(child, depth + 1)).orElse(ItemModelNode.Empty.INSTANCE);
    }

    /**
     * Reads {@code node[key]} as a float, or {@code fallback} when it is absent, a non-primitive, or a
     * non-numeric primitive (a quoted {@code "min"} threshold degrades rather than aborting the load).
     */
    private static float floatValue(@NotNull JsonNode node, @NotNull String key, float fallback) {
        JsonNode value = node.get(key);
        if (value == null || !value.isPrimitive()) return fallback;
        try {
            return value.toGson().getAsFloat();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static float @NotNull [] floatArray(@NotNull JsonNode node, @NotNull String key, float @NotNull [] fallback) {
        JsonNode array = node.get(key);
        if (array == null || !array.isArray()) return fallback.clone();
        float[] out = new float[array.size()];
        try {
            for (int i = 0; i < array.size(); i++) out[i] = array.at(i).toGson().getAsFloat();
        } catch (RuntimeException ex) {
            // A non-numeric or nested element (getAsFloat throws NumberFormatException /
            // UnsupportedOperationException / IllegalStateException): fall back to the default array.
            return fallback.clone();
        }
        return out;
    }

    /** Strips a leading {@code minecraft:} namespace so type / property matching accepts both id forms. */
    private static @NotNull String strip(@NotNull String value) {
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(colon + 1);
    }

}
