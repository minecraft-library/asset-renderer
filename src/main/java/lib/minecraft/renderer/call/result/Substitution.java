package lib.minecraft.renderer.call.result;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.gson.JsonTree;
import dev.simplified.util.Possible;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * A stand-in a render drew in place of what was named.
 * <p>
 * Two substitutions are equal when all four components are, so one render that misses an id on many
 * faces or frames records it once. They order by kind, then id, then state, then the item that named
 * the id. An item-definition kind always names that item and the other kinds never do.
 *
 * @param kind what was asked for and what stood in for it
 * @param state absent where no pack ships the id, empty where one ships it and it yields nothing
 *     drawable
 * @param id the id the render asked for - a texture id, a raw {@code #variable} reference, a subject id,
 *     a model id or a special kind
 * @param namedBy the item whose definition named the id, present for the three item-definition kinds
 *     and for no other
 */
public record Substitution(
    @NotNull Kind kind,
    @NotNull Possible.State state,
    @NotNull String id,
    @NotNull Optional<String> namedBy
) implements Comparable<Substitution> {

    /** The natural order: kind, id, state, then the naming item. */
    private static final @NotNull Comparator<Substitution> ORDER = Comparator.comparing(Substitution::kind)
        .thenComparing(Substitution::id)
        .thenComparing(Substitution::state)
        .thenComparing(substitution -> substitution.namedBy().orElse(""));

    /**
     * Constructs a new {@code Substitution}, refusing a present state and a naming item that does not
     * match the kind.
     *
     * @throws IllegalArgumentException if the state is present, which nothing stands in for, or if an
     *     item-definition kind names no item or another kind names one
     */
    public Substitution {
        if (state == Possible.State.PRESENT)
            throw new IllegalArgumentException(String.format("A substitution stands in for an id that is not present, not for '%s'", id));

        boolean itemNamed = switch (kind) {
            case TEXTURE, SUBJECT -> false;
            case LEAF_MODEL, CIT_MODEL, SPECIAL -> true;
        };
        if (itemNamed && namedBy.isEmpty())
            throw new IllegalArgumentException(String.format("A '%s' substitution for '%s' must name the item whose definition named it", kind.jsonName(), id));
        if (!itemNamed && namedBy.isPresent())
            throw new IllegalArgumentException(String.format("A '%s' substitution for '%s' names no item, not '%s'", kind.jsonName(), id, namedBy.get()));
    }

    /**
     * Records a texture drawn as the checkerboard: one no pack serves, one served that yields no
     * pixels, or a face reference left unresolved, under its raw {@code #variable} spelling.
     *
     * @param textureId the texture id or raw reference the render asked for
     * @param state absent where nothing serves the id, empty where it is served and yields no pixels
     * @return the substitution
     */
    public static @NotNull Substitution texture(@NotNull String textureId, @NotNull Possible.State state) {
        return new Substitution(Kind.TEXTURE, state, textureId, Optional.empty());
    }

    /**
     * Records a block or item id no index draws, drawn as the missing model.
     *
     * @param subjectId the block or item id
     * @return the substitution
     */
    public static @NotNull Substitution subject(@NotNull String subjectId) {
        return new Substitution(Kind.SUBJECT, Possible.State.ABSENT, subjectId, Optional.empty());
    }

    /**
     * Records a model an item definition's leaf names and no pack ships, drawn as the missing model.
     *
     * @param modelId the model id the leaf names
     * @param itemId the item whose definition named it
     * @return the substitution
     */
    public static @NotNull Substitution leafModel(@NotNull String modelId, @NotNull String itemId) {
        return new Substitution(Kind.LEAF_MODEL, Possible.State.ABSENT, modelId, Optional.of(itemId));
    }

    /**
     * Records a model a CIT override names that is no resolvable item model, the base item drawn
     * instead.
     *
     * @param modelId the model id the override names
     * @param itemId the item the override applied to
     * @return the substitution
     */
    public static @NotNull Substitution citModel(@NotNull String modelId, @NotNull String itemId) {
        return new Substitution(Kind.CIT_MODEL, Possible.State.ABSENT, modelId, Optional.of(itemId));
    }

    /**
     * Records a special model kind no renderer knows, the leaf dropped and the base item drawn instead.
     *
     * @param specialKind the special kind the leaf names
     * @param itemId the item whose definition named it
     * @return the substitution
     */
    public static @NotNull Substitution special(@NotNull String specialKind, @NotNull String itemId) {
        return new Substitution(Kind.SPECIAL, Possible.State.ABSENT, specialKind, Optional.of(itemId));
    }

    /**
     * Reads a substitution from its JSON row, resolving its lowercase kind and state tokens back to their
     * constants.
     *
     * @param row the substitution object
     * @return the typed substitution
     * @throws IllegalArgumentException if the kind or state token names no constant, the state is
     *     present, or the naming item does not match the kind
     */
    public static @NotNull Substitution parse(@NotNull JsonTree row) {
        String kindToken = row.getString("kind", "");
        String stateToken = row.getString("state", "");
        Kind kind = Kind.findByJsonName(kindToken)
            .orElseThrow(() -> unknownToken("Substitution.Kind", kindToken));
        Possible.State state = Arrays.stream(Possible.State.values())
            .filter(candidate -> token(candidate).equals(stateToken))
            .findFirst()
            .orElseThrow(() -> unknownToken("Possible.State", stateToken));

        return new Substitution(kind, state, row.getString("id", ""), row.findString("namedBy"));
    }

    /**
     * Builds the failure for a token no constant of the named enum answers to.
     *
     * @param enumName the enum the token was resolved against
     * @param token the unrecognised token
     * @return the failure to throw
     */
    private static @NotNull IllegalArgumentException unknownToken(@NotNull String enumName, @NotNull String token) {
        return new IllegalArgumentException(String.format("Unknown %s token '%s'", enumName, token));
    }

    /**
     * Spells a state as its JSON token, the constant's name in lowercase.
     *
     * @param state the state
     * @return the lowercase token
     */
    private static @NotNull String token(@NotNull Possible.State state) {
        return state.name().toLowerCase(Locale.ROOT);
    }

    /** {@inheritDoc} */
    @Override
    public int compareTo(@NotNull Substitution other) {
        return ORDER.compare(this, other);
    }

    /**
     * Serialises this substitution to its JSON row: the kind, the state and the id, then the naming item
     * where there is one.
     *
     * @return the substitution object
     */
    public @NotNull JsonTree toJson() {
        return JsonTree.object()
            .put("kind", this.kind.jsonName())
            .put("state", token(this.state))
            .put("id", this.id)
            .putIf("namedBy", this.namedBy);
    }

    /**
     * What was asked for and what stood in for it. Serialised through {@link #jsonName}, so the JSON
     * token is the constant's name in lowercase.
     */
    @EnumLookup
    @Getter(style = NamingStyle.FLUENT)
    public enum Kind {

        /**
         * A texture drawn as the checkerboard - one no pack serves, one served that yields no pixels, or
         * a face reference left unresolved.
         */
        TEXTURE,
        /**
         * A block or item id no index draws, drawn as the missing model.
         */
        SUBJECT,
        /**
         * A model an item definition's leaf names and no pack ships, drawn as the missing model.
         */
        LEAF_MODEL,
        /**
         * A model a CIT override names that is no resolvable item model, the base item drawn instead.
         */
        CIT_MODEL,
        /**
         * A special model kind no renderer knows, the leaf dropped and the base item drawn instead.
         */
        SPECIAL;

        /**
         * Lowercase kind name used in the JSON row, derived once at class-load time from {@link #name()}.
         */
        @KeyField
        private final @NotNull String jsonName = this.name().toLowerCase(Locale.ROOT);

    }

}
