package lib.minecraft.renderer.pipeline.pack.rule;

import lib.minecraft.nbt.NbtFactory;
import lib.minecraft.nbt.tag.ByteTag;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.NumericalTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.nbt.tag.Tag;
import org.jetbrains.annotations.NotNull;

/**
 * Value normalization + typed comparison for NBT rule literals - the three nbt-factory gaps
 * (08-siblings §8-10): scalar-literal parsing, SNBT boolean coercion, and cross-type numeric
 * equality. All rule-side; item-side tags come from binary NBT where these are already resolved.
 */
final class NbtValues {

    private NbtValues() {}

    /**
     * Parses a rule-side scalar literal into a {@link Tag}. nbt-factory's {@code fromSnbt} accepts
     * compound roots only, so the literal wraps as {@code {v:<literal>}} and unwraps; a {@code #hex}
     * int pre-converts; a bare {@code true}/{@code false} (which SNBT parses as {@code StringTag})
     * normalizes to {@code ByteTag(1|0)} to match vanilla booleans. An unparseable literal falls back
     * to an exact {@link StringTag} so string matches still work.
     *
     * @param literal the raw literal text
     * @return the normalized literal tag
     */
    static @NotNull Tag<?> parseLiteral(@NotNull String literal) {
        String value = literal.trim();
        if (value.startsWith("#")) {
            try {
                value = Integer.toString((int) Long.parseLong(value.substring(1), 16));
            } catch (NumberFormatException ex) {
                return new StringTag(literal);
            }
        }
        try {
            Tag<?> tag = NbtFactory.fromSnbt("{v:" + value + "}").get("v");
            if (tag instanceof StringTag string) {
                if (string.getValue().equals("true")) return new ByteTag((byte) 1);
                if (string.getValue().equals("false")) return new ByteTag((byte) 0);
            }
            return tag == null ? new StringTag(literal) : tag;
        } catch (RuntimeException ex) {
            return new StringTag(literal);
        }
    }

    /**
     * Compares a reached leaf against a rule literal. Two numeric tags compare by value across types
     * ({@code 1} equals {@code 1b}) - integral pairs by {@code longValue}, otherwise by
     * {@code doubleValue}; everything else falls back to nbt-factory's id-gated {@code Tag.equals}.
     *
     * @param leaf the reached item-side tag
     * @param literal the normalized rule literal
     * @return {@code true} when they compare equal
     */
    static boolean valueEquals(@NotNull Tag<?> leaf, @NotNull Tag<?> literal) {
        if (leaf instanceof NumericalTag<?> a && literal instanceof NumericalTag<?> b) {
            if (isIntegral(a) && isIntegral(b)) return a.longValue() == b.longValue();
            return a.doubleValue() == b.doubleValue();
        }
        return leaf.equals(literal);
    }

    /** Whether a numeric tag holds an integral value (byte/short/int/long) rather than float/double. */
    static boolean isIntegral(@NotNull NumericalTag<?> tag) {
        Object value = tag.getValue();
        return !(value instanceof Float) && !(value instanceof Double);
    }

    /** The string form of a leaf's value, for pattern/regex matching. */
    static @NotNull String stringify(@NotNull Tag<?> tag) {
        Object value = tag.getValue();
        return value == null ? "" : value.toString();
    }

    /** The SNBT form of a reached branch, for the {@code raw:} predicate; scalars unwrap from a probe compound. */
    static @NotNull String snbt(@NotNull Tag<?> tag) {
        if (tag instanceof CompoundTag compound) return NbtFactory.toSnbt(compound);
        CompoundTag wrap = new CompoundTag();
        wrap.put("v", tag);
        String serialized = NbtFactory.toSnbt(wrap);
        int colon = serialized.indexOf(':');
        int brace = serialized.lastIndexOf('}');
        return colon >= 0 && brace > colon ? serialized.substring(colon + 1, brace).trim() : serialized;
    }

}
