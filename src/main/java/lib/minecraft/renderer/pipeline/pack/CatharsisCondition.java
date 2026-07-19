package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.gson.node.JsonTree;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;

/**
 * One parsed {@code fabric:overlays} activation condition. Catharsis
 * gates most of its content behind overlay entries carrying a {@code catharsis:config} or
 * {@code catharsis:version} condition; a headless renderer evaluates them against the option
 * {@link CatharsisConfig defaults} and its {@link CatharsisTarget}. Any condition whose namespace the
 * renderer does not model (a plain Fabric condition, a future {@code catharsis:*}) parses to
 * {@link Unknown} and evaluates {@code false} - the overlay stays inert and the pack degrades to its
 * root content, never erroring.
 */
public sealed interface CatharsisCondition permits CatharsisCondition.Config, CatharsisCondition.Version, CatharsisCondition.Unknown {

    /**
     * Whether this condition holds - i.e. the overlay it gates should stack over the pack root.
     *
     * @param config the pack's config-option defaults
     * @param target the renderer's version / pack-format target
     * @return {@code true} when the overlay activates
     */
    boolean holds(@NotNull CatharsisConfig config, @NotNull CatharsisTarget target);

    /**
     * Parses one condition object. A missing or non-{@code catharsis:*} {@code condition} field, or any
     * malformed field, yields {@link Unknown} so evaluation degrades to {@code false} rather than
     * throwing during pack acquisition.
     *
     * @param condition the condition JSON node
     * @return the parsed condition
     */
    static @NotNull CatharsisCondition parse(@NotNull JsonTree condition) {
        String kind = string(condition, "condition").orElse("");
        return switch (kind) {
            case "catharsis:config" -> new Config(
                string(condition, "pack").orElse(""),
                string(condition, "id").orElse(""),
                string(condition, "value"));
            case "catharsis:version" -> parseVersion(condition);
            default -> new Unknown(kind);
        };
    }

    /** The condition kinds a {@code catharsis:version} condition tests against. */
    enum VersionKind { MINECRAFT, PACK_FORMAT, UNKNOWN }

    /**
     * A {@code catharsis:config} condition: the overlay activates when the named config option holds
     * the requested value under the declared defaults. The {@code pack} field names the
     * option's owning pack; a headless single-pack evaluation resolves it against the loaded config
     * regardless, so it is retained for fidelity but not gated on.
     *
     * @param pack the config-owning pack id the condition names
     * @param id the config option id
     * @param value the requested value token, absent for the boolean "on when default true" form
     */
    record Config(@NotNull String pack, @NotNull String id, @NotNull Optional<String> value) implements CatharsisCondition {

        /** {@inheritDoc} */
        @Override
        public boolean holds(@NotNull CatharsisConfig config, @NotNull CatharsisTarget target) {
            return config.matches(this.id, this.value);
        }

    }

    /**
     * A {@code catharsis:version} condition tested against the renderer target. A
     * {@link VersionKind#PACK_FORMAT} kind checks the target pack format against an inclusive
     * {@code packFormatRange}; a {@link VersionKind#MINECRAFT} kind checks the target Minecraft version
     * against a comparator predicate ({@code >=}, {@code <=}, {@code >}, {@code <}, {@code =}, or a bare
     * exact version, whitespace/comma separated clauses ANDed).
     *
     * @param kind the version dimension being tested
     * @param minecraftPredicate the Minecraft version predicate, present for {@link VersionKind#MINECRAFT}
     * @param packFormatRange the inclusive pack-format range, present for {@link VersionKind#PACK_FORMAT}
     */
    record Version(
        @NotNull VersionKind kind,
        @NotNull Optional<String> minecraftPredicate,
        @NotNull Optional<PackFormatRange> packFormatRange
    ) implements CatharsisCondition {

        /** {@inheritDoc} */
        @Override
        public boolean holds(@NotNull CatharsisConfig config, @NotNull CatharsisTarget target) {
            return switch (this.kind) {
                case MINECRAFT -> this.minecraftPredicate.map(predicate -> matchesVersion(target.minecraftVersion(), predicate)).orElse(false);
                case PACK_FORMAT -> this.packFormatRange.map(range -> range.contains(target.packFormat())).orElse(false);
                case UNKNOWN -> false;
            };
        }

    }

    /**
     * A condition whose namespace the renderer does not model - it evaluates {@code false} so the
     * overlay stays inert.
     *
     * @param condition the raw {@code condition} field, kept for diagnostics
     */
    record Unknown(@NotNull String condition) implements CatharsisCondition {

        /** {@inheritDoc} */
        @Override
        public boolean holds(@NotNull CatharsisConfig config, @NotNull CatharsisTarget target) {
            return false;
        }

    }

    /**
     * An inclusive pack-format range for a {@code catharsis:version PACK_FORMAT} condition.
     *
     * @param min the inclusive lower pack format
     * @param max the inclusive upper pack format
     */
    record PackFormatRange(int min, int max) {

        /**
         * Whether a pack format falls in this inclusive range.
         *
         * @param packFormat the target pack format
         * @return {@code true} when {@code min <= packFormat <= max}
         */
        public boolean contains(int packFormat) {
            return packFormat >= this.min && packFormat <= this.max;
        }

    }

    private static @NotNull CatharsisCondition parseVersion(@NotNull JsonTree condition) {
        VersionKind kind = switch (string(condition, "type").orElse("").toUpperCase(Locale.ROOT)) {
            case "MINECRAFT" -> VersionKind.MINECRAFT;
            case "PACK_FORMAT" -> VersionKind.PACK_FORMAT;
            default -> VersionKind.UNKNOWN;
        };
        Optional<PackFormatRange> range = Optional.empty();
        Optional<JsonTree> bounds = condition.findObject("packFormatRange");
        if (bounds.isPresent()) {
            Optional<Integer> min = bounds.get().findInt("min_inclusive");
            Optional<Integer> max = bounds.get().findInt("max_inclusive");
            if (min.isPresent() && max.isPresent()) range = Optional.of(new PackFormatRange(min.get(), max.get()));
        }
        return new Version(kind, string(condition, "minecraftPredicate"), range);
    }

    /** Whether {@code actual} satisfies every whitespace/comma-separated comparator clause in {@code predicate}. */
    private static boolean matchesVersion(@NotNull String actual, @NotNull String predicate) {
        String trimmed = predicate.trim();
        if (trimmed.isEmpty()) return false;
        for (String clause : trimmed.split("[,\\s]+"))
            if (!clause.isBlank() && !matchesClause(actual, clause.trim())) return false;
        return true;
    }

    private static boolean matchesClause(@NotNull String actual, @NotNull String clause) {
        String operator = "=";
        String version = clause;
        for (String candidate : new String[] {">=", "<=", "==", ">", "<", "="}) {
            if (clause.startsWith(candidate)) {
                operator = candidate.equals("==") ? "=" : candidate;
                version = clause.substring(candidate.length()).trim();
                break;
            }
        }
        int comparison = compareVersions(actual, version);
        return switch (operator) {
            case ">=" -> comparison >= 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case "<" -> comparison < 0;
            default -> comparison == 0;
        };
    }

    /** Compares two dot-separated versions component-wise, padding missing / non-numeric components with {@code 0}. */
    private static int compareVersions(@NotNull String left, @NotNull String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            int leftValue = index < leftParts.length ? numeric(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? numeric(rightParts[index]) : 0;
            if (leftValue != rightValue) return Integer.compare(leftValue, rightValue);
        }
        return 0;
    }

    private static int numeric(@NotNull String part) {
        try {
            return Integer.parseInt(part.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static @NotNull Optional<String> string(@NotNull JsonTree obj, @NotNull String key) {
        JsonTree element = obj.get(key);
        return element != null && element.isPrimitive() && element.boolValue().isEmpty() && element.intValue().isEmpty()
            ? element.stringValue()
            : Optional.empty();
    }

}
