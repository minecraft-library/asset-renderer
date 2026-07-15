package lib.minecraft.renderer.pipeline.pack.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

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
    public static @NotNull ItemModelNode parse(@NotNull JsonObject model) {
        return parse(model, 0);
    }

    private static @NotNull ItemModelNode parse(@NotNull JsonObject node, int depth) {
        if (depth >= MAX_DEPTH) return ItemModelNode.Empty.INSTANCE;
        String type = strip(string(node, "type", ""));
        return switch (type) {
            case "model" -> new ItemModelNode.Model(string(node, "model", ""), parseTints(node));
            case "condition" -> new ItemModelNode.Condition(
                string(node, "property", ""), string(node, "component", ""),
                child(node, "on_true", depth), child(node, "on_false", depth));
            case "select" -> new ItemModelNode.Select(
                string(node, "property", ""), string(node, "block_state_property", ""),
                parseCases(node, depth), child(node, "fallback", depth));
            case "range_dispatch" -> new ItemModelNode.RangeDispatch(
                string(node, "property", ""), floatValue(node, "scale", 1f), string(node, "target", ""),
                parseEntries(node, depth), child(node, "fallback", depth));
            case "composite" -> new ItemModelNode.Composite(parseModels(node, depth));
            case "special" -> parseSpecial(node);
            case "bundle/selected_item" -> new ItemModelNode.Bundle();
            default -> ItemModelNode.Empty.INSTANCE;
        };
    }

    /** Parses a {@code special} node into a {@link ItemModelNode.Special}, collecting its inline kind fields. */
    private static @NotNull ItemModelNode parseSpecial(@NotNull JsonObject node) {
        JsonObject inner = node.has("model") && node.get("model").isJsonObject() ? node.getAsJsonObject("model") : new JsonObject();
        String kind = string(inner, "type", "");
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : inner.entrySet()) {
            if (entry.getKey().equals("type")) continue;
            if (entry.getValue().isJsonPrimitive()) fields.put(entry.getKey(), entry.getValue().getAsString());
        }
        return new ItemModelNode.Special(kind, string(node, "base", ""), Map.copyOf(fields), parseTransform(node));
    }

    /** Parses a node's {@code transformation}, or {@link SpecialTransform#IDENTITY} when absent / malformed. */
    private static @NotNull SpecialTransform parseTransform(@NotNull JsonObject node) {
        if (!node.has("transformation") || !node.get("transformation").isJsonObject()) return SpecialTransform.IDENTITY;
        JsonObject t = node.getAsJsonObject("transformation");
        return new SpecialTransform(
            floatArray(t, "left_rotation", SpecialTransform.IDENTITY.leftRotation()),
            floatArray(t, "right_rotation", SpecialTransform.IDENTITY.rightRotation()),
            floatArray(t, "scale", SpecialTransform.IDENTITY.scale()),
            floatArray(t, "translation", SpecialTransform.IDENTITY.translation()));
    }

    private static @NotNull List<ItemModelNode.Select.Case> parseCases(@NotNull JsonObject node, int depth) {
        List<ItemModelNode.Select.Case> cases = new ArrayList<>();
        if (node.has("cases") && node.get("cases").isJsonArray()) {
            for (JsonElement element : node.getAsJsonArray("cases")) {
                if (!element.isJsonObject()) continue;
                JsonObject c = element.getAsJsonObject();
                cases.add(new ItemModelNode.Select.Case(parseWhen(c.get("when")), child(c, "model", depth)));
            }
        }
        return List.copyOf(cases);
    }

    private static @NotNull List<ItemModelNode.RangeDispatch.Entry> parseEntries(@NotNull JsonObject node, int depth) {
        List<ItemModelNode.RangeDispatch.Entry> entries = new ArrayList<>();
        if (node.has("entries") && node.get("entries").isJsonArray()) {
            for (JsonElement element : node.getAsJsonArray("entries")) {
                if (!element.isJsonObject()) continue;
                JsonObject e = element.getAsJsonObject();
                entries.add(new ItemModelNode.RangeDispatch.Entry(floatValue(e, "threshold", 0f), child(e, "model", depth)));
            }
        }
        return List.copyOf(entries);
    }

    private static @NotNull List<ItemModelNode> parseModels(@NotNull JsonObject node, int depth) {
        List<ItemModelNode> models = new ArrayList<>();
        if (node.has("models") && node.get("models").isJsonArray()) {
            for (JsonElement element : node.getAsJsonArray("models"))
                if (element.isJsonObject()) models.add(parse(element.getAsJsonObject(), depth + 1));
        }
        return List.copyOf(models);
    }

    /** Parses a {@code when} value: a single string, or an array of strings. */
    private static @NotNull List<String> parseWhen(JsonElement when) {
        if (when == null) return List.of();
        if (when.isJsonArray()) {
            List<String> keys = new ArrayList<>();
            for (JsonElement element : when.getAsJsonArray())
                if (element.isJsonPrimitive()) keys.add(element.getAsString());
            return List.copyOf(keys);
        }
        return when.isJsonPrimitive() ? List.of(when.getAsString()) : List.of();
    }

    /** Parses the {@code tints[]} array of a {@code model} node into ordered per-layer tint rules. */
    private static @NotNull List<LayerTint> parseTints(@NotNull JsonObject model) {
        if (!model.has("tints") || !model.get("tints").isJsonArray()) return List.of();
        List<LayerTint> tints = new ArrayList<>();
        for (JsonElement element : model.getAsJsonArray("tints"))
            if (element.isJsonObject()) tints.add(parseTint(element.getAsJsonObject()));
        return List.copyOf(tints);
    }

    /**
     * Parses one {@code tints[]} entry into its {@link LayerTint} variant. Source types this renderer
     * cannot resolve dynamically ({@code grass}, {@code map_color}, {@code custom_model_data}, ...)
     * become a white {@link LayerTint.Constant} - rendered untinted rather than guessing.
     */
    private static @NotNull LayerTint parseTint(@NotNull JsonObject tint) {
        return switch (strip(string(tint, "type", ""))) {
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
    private static int toArgb(@NotNull JsonObject tint, @NotNull String key) {
        if (!tint.has(key) || !tint.get(key).isJsonPrimitive()) return 0xFFFFFFFF;
        try {
            return 0xFF000000 | (tint.get(key).getAsInt() & 0xFFFFFF);
        } catch (NumberFormatException ex) {
            return 0xFFFFFFFF;
        }
    }

    /** Parses {@code object[key]} as a child node, or {@link ItemModelNode.Empty} when absent / not an object. */
    private static @NotNull ItemModelNode child(@NotNull JsonObject object, @NotNull String key, int depth) {
        return object.has(key) && object.get(key).isJsonObject()
            ? parse(object.getAsJsonObject(key), depth + 1)
            : ItemModelNode.Empty.INSTANCE;
    }

    private static @NotNull String string(@NotNull JsonObject object, @NotNull String key, @NotNull String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    private static float floatValue(@NotNull JsonObject object, @NotNull String key, float fallback) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) return fallback;
        try {
            return object.get(key).getAsFloat();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static float @NotNull [] floatArray(@NotNull JsonObject object, @NotNull String key, float @NotNull [] fallback) {
        if (!object.has(key) || !object.get(key).isJsonArray()) return fallback.clone();
        JsonArray array = object.getAsJsonArray(key);
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

    /** Strips a leading {@code minecraft:} namespace so type / property matching accepts both id forms. */
    private static @NotNull String strip(@NotNull String value) {
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(colon + 1);
    }

}
