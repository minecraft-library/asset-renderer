package lib.minecraft.renderer.tooling.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Compact helpers for the {@code obj.has(key) ? obj.get(key).getAsX() : defaultValue}
 * ternary pattern that recurs through the JSON consumer paths in tooling. Each helper
 * folds the existence check, the type-narrowed accessor, and the default into one call
 * site so call sites read as {@code optInt(obj, "textureWidth", 64)} instead of the
 * 4-step ternary.
 *
 * <p>All helpers are null-safe on {@code obj == null} and return the supplied default
 * (or {@code null} for nullable-array / nullable-object variants) when the key is absent
 * or the value's type doesn't match.
 */
@UtilityClass
public final class JsonOptional {

    /**
     * Returns the string under {@code key}, or {@code def} when {@code obj} is {@code null},
     * the key is absent, or the value is not a JSON primitive convertible to a string.
     *
     * @param obj the object to read from, or {@code null}
     * @param key the member name to look up
     * @param def the fallback returned on absent / mismatched key
     * @return the resolved string, or {@code def}
     */
    public static @NotNull String optString(@Nullable JsonObject obj, @NotNull String key, @NotNull String def) {
        if (obj == null || !obj.has(key)) return def;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : def;
    }

    /**
     * Returns the int under {@code key}, or {@code def} when {@code obj} is {@code null}, the
     * key is absent, or the value is not a JSON primitive convertible to an int.
     *
     * @param obj the object to read from, or {@code null}
     * @param key the member name to look up
     * @param def the fallback returned on absent / mismatched key
     * @return the resolved int, or {@code def}
     */
    public static int optInt(@Nullable JsonObject obj, @NotNull String key, int def) {
        if (obj == null || !obj.has(key)) return def;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsInt() : def;
    }

    /**
     * Returns the float under {@code key}, or {@code def} when {@code obj} is {@code null}, the
     * key is absent, or the value is not a JSON primitive convertible to a float.
     *
     * @param obj the object to read from, or {@code null}
     * @param key the member name to look up
     * @param def the fallback returned on absent / mismatched key
     * @return the resolved float, or {@code def}
     */
    public static float optFloat(@Nullable JsonObject obj, @NotNull String key, float def) {
        if (obj == null || !obj.has(key)) return def;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsFloat() : def;
    }

    /**
     * Returns the boolean under {@code key}, or {@code def} when {@code obj} is {@code null},
     * the key is absent, or the value is not a JSON primitive convertible to a boolean.
     *
     * @param obj the object to read from, or {@code null}
     * @param key the member name to look up
     * @param def the fallback returned on absent / mismatched key
     * @return the resolved boolean, or {@code def}
     */
    public static boolean optBool(@Nullable JsonObject obj, @NotNull String key, boolean def) {
        if (obj == null || !obj.has(key)) return def;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsBoolean() : def;
    }

    /**
     * Returns the nested {@link JsonObject} under {@code key}, or {@code null} when {@code obj}
     * is {@code null}, the key is absent, or the value is not a JSON object.
     *
     * @param obj the object to read from, or {@code null}
     * @param key the member name to look up
     * @return the nested object, or {@code null}
     */
    public static @Nullable JsonObject optObject(@Nullable JsonObject obj, @NotNull String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement el = obj.get(key);
        return el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    /**
     * Returns the nested {@link JsonArray} under {@code key}, or {@code null} when {@code obj}
     * is {@code null}, the key is absent, or the value is not a JSON array.
     *
     * @param obj the object to read from, or {@code null}
     * @param key the member name to look up
     * @return the nested array, or {@code null}
     */
    public static @Nullable JsonArray optArray(@Nullable JsonObject obj, @NotNull String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement el = obj.get(key);
        return el.isJsonArray() ? el.getAsJsonArray() : null;
    }

}
