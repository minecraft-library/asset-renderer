package lib.minecraft.renderer.pipeline.pack;

import lib.minecraft.renderer.exception.PipelineException;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A normalized resource-pack identity, distinct from a namespace.
 *
 * <p>The value alphabet is lowercase {@code a-z0-9} with single hyphens, digits permitted only in
 * non-leading positions - so every id is letter-led, hyphen-separated, and free of consecutive,
 * leading, or trailing hyphens. This is the pack-addressed key the resolution rule consults when an
 * id's prefix is not a live namespace (see the resolution design); it is derived from a pack's
 * naming inputs by {@link PackIdDeriver}, never chosen by hand for user packs.
 *
 * <p>The reserved constants {@link #VANILLA} and {@link #MINECRAFT} are never produced by
 * {@link #normalize} for a user pack: {@code vanilla} is the base pack's fixed id and {@code minecraft}
 * is the default namespace, so a user source that would normalize to either is diverted to a collision
 * suffix by the deriver.
 *
 * @param value the normalized id string, matching {@link #ALPHABET}
 */
public record PackId(@NotNull String value) {

    /**
     * The id alphabet - letter-led, lowercase {@code a-z0-9}, single hyphens between alphanumeric
     * groups, with no leading, trailing, or consecutive hyphens.
     */
    public static final @NotNull Pattern ALPHABET = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    /** The reserved id of the base pack - the extracted client jar at stack priority zero. */
    public static final @NotNull PackId VANILLA = new PackId("vanilla");

    /** The reserved id colliding with the default namespace; never produced for a user pack. */
    public static final @NotNull PackId MINECRAFT = new PackId("minecraft");

    private static final @NotNull Pattern SECTION_CODE = Pattern.compile("§.", Pattern.DOTALL);
    private static final @NotNull Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final @NotNull Pattern LEADING_TRIM = Pattern.compile("^[0-9-]+");
    private static final @NotNull Pattern TRAILING_HYPHENS = Pattern.compile("-+$");

    /**
     * Validates the value against {@link #ALPHABET}.
     *
     * @throws PipelineException if the value is not a well-formed pack id
     */
    public PackId {
        if (!ALPHABET.matcher(value).matches())
            throw new PipelineException("Pack id '%s' is not letter-led lowercase a-z0-9 with single hyphens", value);
    }

    /**
     * Normalizes a raw naming string into a pack id, or empty when nothing survives the transform.
     *
     * <p>The steps are fixed and deterministic: strip {@code §x} formatting codes; lowercase under
     * {@link Locale#ROOT}; collapse every maximal run of non-{@code [a-z0-9]} characters to one
     * hyphen; then trim the id letter-led by dropping any leading digit/hyphen run and any trailing
     * hyphens. A purely numeric or symbolic input (e.g. {@code "26.1"}) collapses to empty, which the
     * caller reads as this naming source failing.
     *
     * @param raw the raw naming string (filename stem, license line, or description)
     * @return the normalized id, or empty when the transform yields nothing letter-led
     */
    public static @NotNull Optional<PackId> normalize(@NotNull String raw) {
        String s = SECTION_CODE.matcher(raw).replaceAll("").replace("§", "");
        s = s.toLowerCase(Locale.ROOT);
        s = NON_ALNUM.matcher(s).replaceAll("-");
        s = LEADING_TRIM.matcher(s).replaceFirst("");
        s = TRAILING_HYPHENS.matcher(s).replaceFirst("");
        if (s.isEmpty()) return Optional.empty();
        return Optional.of(new PackId(s));
    }

    /**
     * Whether this id is one of the reserved constants.
     *
     * @return {@code true} when this equals {@link #VANILLA} or {@link #MINECRAFT}
     */
    public boolean isReserved() {
        return this.equals(VANILLA) || this.equals(MINECRAFT);
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull String toString() {
        return this.value;
    }

}
