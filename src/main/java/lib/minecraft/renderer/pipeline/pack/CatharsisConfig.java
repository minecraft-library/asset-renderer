package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Catharsis config-option defaults an overlay's {@code catharsis:config} condition evaluates
 * against. A Catharsis pack declares its options in a root
 * {@code config.catharsis.json} (which replaces any {@code catharsis:pack/v1.config} in the mcmeta
 * wholesale when it declares options) as a menu tree of {@code boolean} / {@code dropdown} /
 * {@code tab} / {@code separator} elements. A headless renderer has no user config store, so each
 * option's declared {@code default} is the baseline.
 *
 * <p>The parse is intentionally structure-tolerant: it walks the whole JSON tree and registers any
 * object carrying a string {@code id}, reading its default from a primitive {@code default} (boolean
 * for a toggle, string for a dropdown) or from the {@code options[]} entry marked {@code default:true}.
 * Unknown container shapes are traversed, not rejected - a config element the renderer does not model
 * simply contributes no default.
 */
@Parity(claim = "catharsis-selection")
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class CatharsisConfig {

    /** The empty config - no option carries a default, so every {@code catharsis:config} condition degrades to false. */
    public static final @NotNull CatharsisConfig EMPTY = new CatharsisConfig(Map.of());

    private final @NotNull Map<String, OptionDefault> options;

    /**
     * Parses a config document - the {@code config.catharsis.json} array (or the mcmeta config
     * sub-element) - into its option defaults.
     *
     * @param root the config JSON root node (array or object)
     * @return the parsed defaults, or {@link #EMPTY} when none are found
     */
    public static @NotNull CatharsisConfig parse(@NotNull JsonTree root) {
        Map<String, OptionDefault> options = new LinkedHashMap<>();
        walk(root, options);
        return options.isEmpty() ? EMPTY : new CatharsisConfig(Map.copyOf(options));
    }

    /**
     * Evaluates a {@code catharsis:config} condition against the declared defaults. An
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

    private static void walk(@NotNull JsonTree element, @NotNull Map<String, OptionDefault> out) {
        if (element.isArray()) {
            for (JsonTree child : element.elements().toList()) walk(child, out);
            return;
        }
        if (!element.isObject()) return;
        if (isStringPrimitive(element.find("id").orElse(null)))
            extractDefault(element).ifPresent(option -> out.putIfAbsent(element.findString("id").orElse(null), option));
        for (Map.Entry<String, JsonTree> entry : element.members().toList()) walk(entry.getValue(), out);
    }

    private static @NotNull Optional<OptionDefault> extractDefault(@NotNull JsonTree obj) {
        JsonTree def = obj.find("default").orElse(null);
        if (def != null && def.isPrimitive()) {
            if (def.asBool().isPresent()) return Optional.of(new OptionDefault.Bool(def.asBool().get()));
            if (isStringPrimitive(def)) return Optional.of(new OptionDefault.Choice(def.asString().orElseThrow()));
        }
        Optional<JsonTree> options = obj.findArray("options");
        if (options.isPresent()) {
            for (JsonTree option : options.get().elements().toList()) {
                if (!option.isObject()) continue;
                if (isTrue(option.find("default").orElse(null)) && isStringPrimitive(option.find("value").orElse(null)))
                    return Optional.of(new OptionDefault.Choice(option.findString("value").orElse(null)));
            }
        }
        return Optional.empty();
    }

    private static boolean isStringPrimitive(@Nullable JsonTree element) {
        return element != null && element.isPrimitive() && element.asBool().isEmpty() && element.asInt().isEmpty();
    }

    private static boolean isTrue(@Nullable JsonTree element) {
        return element != null && element.asBool().orElse(false);
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
