package lib.minecraft.renderer.pipeline.pack.item;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;

/**
 * Reads one {@code tints[]} entry of an item {@code model} node into its {@link LayerTint} variant,
 * dispatching on the (namespace-stripped) {@code type}. Source types this renderer cannot resolve
 * dynamically ({@code grass}, {@code map_color}, {@code custom_model_data}, ...) - and any non-object
 * entry - become a white {@link LayerTint.Constant}, rendered untinted rather than guessing.
 *
 * <p><b>Parity.</b> Registered by a service file and reached only through the contributor that
 * names it, so no constant pool carries an edge to it. It parses the tint a drawn layer takes.
 */
@Parity(subject = {Subject.BLOCK, Subject.ENTITY, Subject.ITEM, Subject.MENU})
public final class LayerTintDeserializer implements JsonDeserializer<LayerTint> {

    @Override
    public @NotNull LayerTint deserialize(@NotNull JsonElement json, @NotNull Type type, @NotNull JsonDeserializationContext context) {
        if (!json.isJsonObject()) return new LayerTint.Constant(0xFFFFFFFF);
        JsonObject tint = json.getAsJsonObject();

        return switch (strip(string(tint, "type"))) {
            case "dye" -> new LayerTint.Dye(argb(tint, "default"));
            case "potion" -> new LayerTint.Potion(argb(tint, "default"));
            case "firework" -> new LayerTint.Firework(argb(tint, "default"));
            case "constant" -> new LayerTint.Constant(argb(tint, "value"));
            default -> new LayerTint.Constant(0xFFFFFFFF);
        };
    }

    /**
     * Reads an RGB tint colour from {@code tint[key]} and forces opaque alpha - masks to 24 bits and
     * OR-s {@code 0xFF000000} so every source normalises to the ARGB the multiply-tint blend expects.
     * Defaults to white when the key is absent, non-primitive, or non-numeric (a pack author writing the
     * colour as a quoted hex string {@code "#ffffff"} degrades to white rather than aborting the load).
     */
    private static int argb(@NotNull JsonObject tint, @NotNull String key) {
        JsonElement value = tint.get(key);
        if (value == null || !value.isJsonPrimitive()) return 0xFFFFFFFF;
        try {
            return 0xFF000000 | (value.getAsInt() & 0xFFFFFF);
        } catch (NumberFormatException ex) {
            return 0xFFFFFFFF;
        }
    }

    /** The string member under {@code key} when it is a JSON primitive, or {@code ""}. */
    private static @NotNull String string(@NotNull JsonObject tint, @NotNull String key) {
        JsonElement value = tint.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /** Strips a leading {@code minecraft:} namespace so type matching accepts both id forms. */
    private static @NotNull String strip(@NotNull String value) {
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(colon + 1);
    }

}
