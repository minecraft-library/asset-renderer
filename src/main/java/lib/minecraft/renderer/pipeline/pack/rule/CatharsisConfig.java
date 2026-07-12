package lib.minecraft.renderer.pipeline.pack.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Catharsis config-option defaults an overlay's {@code catharsis:config} condition evaluates
 * against (03-rules §6.1, 06-catharsis §6). A Catharsis pack declares its options in a root
 * {@code config.catharsis.json} (which fully overrides any {@code catharsis:pack/v1.config} in the
 * mcmeta) as a menu tree of {@code boolean} / {@code dropdown} / {@code tab} / {@code separator}
 * elements. A headless renderer has no user config store, so each option's declared {@code default} is
 * the baseline (06-catharsis §6).
 *
 * <p>The parse is intentionally structure-tolerant: it walks the whole JSON tree and registers any
 * object carrying a string {@code id}, reading its default from a primitive {@code default} (boolean
 * for a toggle, string for a dropdown) or from the {@code options[]} entry marked {@code default:true}.
 * Unknown container shapes are traversed, not rejected - a config element the renderer does not model
 * simply contributes no default.
 */
public final class CatharsisConfig {

    /** The empty config - no option carries a default, so every {@code catharsis:config} condition degrades to false. */
    public static final @NotNull CatharsisConfig EMPTY = new CatharsisConfig(Map.of());

    private final @NotNull Map<String, OptionDefault> options;

    private CatharsisConfig(@NotNull Map<String, OptionDefault> options) {
        this.options = options;
    }

    /**
     * Parses a config document - the {@code config.catharsis.json} array (or the mcmeta config
     * sub-element) - into its option defaults.
     *
     * @param root the config JSON root (array or object)
     * @return the parsed defaults, or {@link #EMPTY} when none are found
     */
    public static @NotNull CatharsisConfig parse(@NotNull JsonElement root) {
        Map<String, OptionDefault> options = new LinkedHashMap<>();
        walk(root, options);
        return options.isEmpty() ? EMPTY : new CatharsisConfig(Map.copyOf(options));
    }

    /**
     * Evaluates a {@code catharsis:config} condition against the declared defaults (03-rules §6.1). An
     * option the config never declared yields {@code false} (degrade to root, never error).
     *
     * <ul>
     * <li>Boolean option, absent {@code value}: true when the default is {@code true} ("on when default true").</li>
     * <li>Boolean option, present {@code value}: true when the on/off value matches the boolean default.</li>
     * <li>Dropdown option, present {@code value}: true on string-equality with the default value.</li>
     * <li>Dropdown option, absent {@code value}: false (nothing to compare).</li>
     * </ul>
     *
     * @param id the config option id
     * @param value the condition's optional value token
     * @return whether the condition holds under the defaults
     */
    public boolean matches(@NotNull String id, @NotNull Optional<String> value) {
        OptionDefault option = this.options.get(id);
        if (option == null) return false;
        return switch (option) {
            case OptionDefault.Bool bool -> value.isEmpty()
                ? bool.value()
                : parseBoolean(value.get()).map(parsed -> parsed == bool.value()).orElse(false);
            case OptionDefault.Choice choice -> value.isPresent() && value.get().equals(choice.value());
        };
    }

    /**
     * Whether this config declared any option defaults.
     *
     * @return {@code true} when no option carries a default
     */
    public boolean isEmpty() {
        return this.options.isEmpty();
    }

    /**
     * The number of declared option defaults.
     *
     * @return the option-default count
     */
    public int size() {
        return this.options.size();
    }

    private static void walk(@NotNull JsonElement element, @NotNull Map<String, OptionDefault> out) {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) walk(child, out);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject obj = element.getAsJsonObject();
        if (isStringPrimitive(obj.get("id")))
            extractDefault(obj).ifPresent(option -> out.putIfAbsent(obj.get("id").getAsString(), option));
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) walk(entry.getValue(), out);
    }

    private static @NotNull Optional<OptionDefault> extractDefault(@NotNull JsonObject obj) {
        if (obj.has("default") && obj.get("default").isJsonPrimitive()) {
            JsonPrimitive primitive = obj.get("default").getAsJsonPrimitive();
            if (primitive.isBoolean()) return Optional.of(new OptionDefault.Bool(primitive.getAsBoolean()));
            if (primitive.isString()) return Optional.of(new OptionDefault.Choice(primitive.getAsString()));
        }
        if (obj.has("options") && obj.get("options").isJsonArray()) {
            for (JsonElement optionElement : obj.getAsJsonArray("options")) {
                if (!optionElement.isJsonObject()) continue;
                JsonObject option = optionElement.getAsJsonObject();
                if (isTrue(option.get("default")) && isStringPrimitive(option.get("value")))
                    return Optional.of(new OptionDefault.Choice(option.get("value").getAsString()));
            }
        }
        return Optional.empty();
    }

    private static boolean isStringPrimitive(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static boolean isTrue(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
    }

    private static @NotNull Optional<Boolean> parseBoolean(@NotNull String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "enabled" -> Optional.of(true);
            case "off", "false", "no", "disabled" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /** One config option's default value - a boolean toggle or a dropdown choice string. */
    private sealed interface OptionDefault permits OptionDefault.Bool, OptionDefault.Choice {

        record Bool(boolean value) implements OptionDefault {}

        record Choice(@NotNull String value) implements OptionDefault {}

    }

}
