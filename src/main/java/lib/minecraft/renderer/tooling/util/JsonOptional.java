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
     * Returns the string under {@code key}, or {@code def} when the key is absent or not
     * a JSON primitive convertible to a string.
     */
    public static @NotNull String optString(@Nullable JsonObject obj, @NotNull String key, @NotNull String def) {
        if (obj == null || !obj.has(key)) return def;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : def;
    }

    /**
     * Returns the int under {@code key}, or {@code def} when the key is absent or not a
     * JSON primitive convertible to an int.
     */
    public static int optInt(@Nullable JsonObject obj, @NotNull String key, int def) {
        if (obj == null || !obj.has(key)) return def;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsInt() : def;
    }

    /**
     * Returns the float under {@code key}, or {@code def} when the key is absent or not a
     * JSON primitive convertible to a float.
     */
    public static float optFloat(@Nullable JsonObject obj, @NotNull String key, float def) {
        if (obj == null || !obj.has(key)) return def;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsFloat() : def;
    }

    /**
     * Returns the boolean under {@code key}, or {@code def} when the key is absent or not
     * a JSON primitive convertible to a boolean.
     */
    public static boolean optBool(@Nullable JsonObject obj, @NotNull String key, boolean def) {
        if (obj == null || !obj.has(key)) return def;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsBoolean() : def;
    }

    /**
     * Returns the nested {@link JsonObject} under {@code key}, or {@code null} when the
     * key is absent or the value is not a JSON object.
     */
    public static @Nullable JsonObject optObject(@Nullable JsonObject obj, @NotNull String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement el = obj.get(key);
        return el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    /**
     * Returns the nested {@link JsonArray} under {@code key}, or {@code null} when the key
     * is absent or the value is not a JSON array.
     */
    public static @Nullable JsonArray optArray(@Nullable JsonObject obj, @NotNull String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement el = obj.get(key);
        return el.isJsonArray() ? el.getAsJsonArray() : null;
    }

}
