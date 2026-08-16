package lib.minecraft.renderer.parity;

import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import lib.minecraft.renderer.option.EntityAppearance;
import lib.minecraft.renderer.option.spec.ArmorOptions;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * One reference render, named and resolved: the entity to draw, the coat to draw it in, and the
 * appearance and armour selections the rest of its name carried.
 *
 * @param entityId the entity to render, namespaced
 * @param variant the coat option to select, empty for a model with no variant axis
 * @param appearance the appearance the tokens selected
 * @param armor the armour the {@code armor} and {@code armor_dye} tokens selected, empty when neither
 *     appeared
 */
public record AppearanceKey(@NotNull String entityId,
                            @NotNull Optional<String> variant,
                            @NotNull EntityAppearance appearance,
                            @NotNull Optional<ArmorOptions> armor) {

    /**
     * The outcome of parsing a name - either a resolved key or the reason it could not be one.
     *
     * <p>Sealed and explicit rather than an {@code Optional}, because a name this parser cannot read
     * must stop the sweep with its reason rather than vanish into a count. A reference that is
     * emitted before the parser learns to read it would otherwise look exactly like a reference that
     * was never emitted.
     */
    public sealed interface Result {

        /**
         * A name that parsed.
         *
         * @param key what it named
         */
        record Parsed(@NotNull AppearanceKey key) implements Result {}

        /**
         * A name that did not parse.
         *
         * @param name the offending file name
         * @param reason what is wrong with it, in terms a reader can act on
         */
        record Malformed(@NotNull String name, @NotNull String reason) implements Result {}
    }

    /** The appearance axes a key may carry, and everything about how each one is spelled. */
    public enum Axis {

        /** Whether the subject is a baby; adults are the default and are never named. */
        AGE("age"),
        /** The behavioural state, validated against the model's own options. */
        STATE("state"),
        /** The size option, validated against the model's own options. */
        SIZE("size"),
        /** The carried block, or the sentinel that drops the model's fixed overlays. */
        CARRIED("carried"),
        /** The tropical-fish pattern, which also selects the body shape. */
        PATTERN("pattern"),
        /** The horse marking overlay. */
        MARKINGS("markings"),
        /** The iron golem's crack stage. */
        CRACKINESS("crackiness"),
        /** The copper golem's weathering stage. */
        WEATHERING("weathering"),
        /** The villager's biome type. */
        VILLAGER_TYPE("villager_type"),
        /** The villager's profession. */
        VILLAGER_PROFESSION("villager_profession"),
        /** The villager's badge level. */
        VILLAGER_LEVEL("villager_level"),
        /** Whether a sheep is sheared. */
        SHEARED("sheared"),
        /** Whether a creeper is charged. */
        CHARGED("charged"),
        /** Whether the subject wears an elytra. */
        ELYTRA("elytra"),
        /** A named bone toggle. Repeatable - one token per toggle. */
        TOGGLE("toggle"),
        /** An equipment slot, optionally with its material. Repeatable - one token per slot. */
        EQUIP("equip"),
        /** The body's base tint. */
        BASE_COLOR("base_color"),
        /** A pattern overlay's tint. */
        PATTERN_COLOR("pattern_color"),
        /** A wool overlay's tint. */
        WOOL_COLOR("wool_color"),
        /** A collar overlay's tint. */
        COLLAR_COLOR("collar_color"),
        /** The wearer's equipment dye. */
        EQUIPMENT_COLOR("equipment_color"),
        /** The worn armour material, applied to all four slots. */
        ARMOR("armor"),
        /** The leather armour dye, as six hex digits. Only meaningful alongside leather armour. */
        ARMOR_DYE("armor_dye");

        /** The token name this axis is spelled with. */
        @Getter(style = NamingStyle.FLUENT)
        private final @NotNull String token;

        Axis(@NotNull String token) {
            this.token = token;
        }

        /**
         * Returns whether a key may carry more than one token of this axis.
         *
         * @return whether the axis repeats
         */
        public boolean repeatable() {
            return this == TOGGLE || this == EQUIP;
        }

        /**
         * Resolves a token name to its axis.
         *
         * @param token the text before a token's first {@code =}
         * @return the axis, or empty when no axis is spelled that way
         */
        public static @NotNull Optional<Axis> ofToken(@NotNull String token) {
            for (Axis axis : values())
                if (axis.token.equals(token)) return Optional.of(axis);
            return Optional.empty();
        }
    }
}
