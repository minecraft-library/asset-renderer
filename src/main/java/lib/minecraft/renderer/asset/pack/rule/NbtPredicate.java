package lib.minecraft.renderer.asset.pack.rule;

import lib.minecraft.nbt.tag.NumericalTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.nbt.tag.Tag;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The value test of a CIT {@code nbt.<path>} rule, applied to the leaves an {@link NbtPath} reaches.
 * A rule matches when ANY reached leaf satisfies the predicate (OptiFine's wildcard = "any"), except
 * {@link Exists}, which tests presence of the whole leaf set. The variants are the OptiFine grammar's
 * first-class prefixes ({@code pattern:}/{@code regex:}/{@code range:}/{@code exists:}/{@code raw:}).
 */
public sealed interface NbtPredicate {

    /**
     * Whether the reached leaves satisfy this predicate.
     *
     * @param leaves the leaves an {@link NbtPath} reached (possibly empty)
     * @return {@code true} when satisfied
     */
    boolean matches(@NotNull List<Tag<?>> leaves);

    /**
     * An exact-value predicate over a normalized scalar literal.
     *
     * @param literal the raw rule literal
     * @return the predicate
     */
    static @NotNull NbtPredicate exact(@NotNull String literal) {
        return new Exact(NbtValues.parseLiteral(literal));
    }

    /**
     * A glob predicate ({@code pattern:}/{@code ipattern:}) - {@code *} and {@code ?} wildcards over
     * the stringified value.
     *
     * @param glob the glob text
     * @param caseInsensitive whether to match case-insensitively
     * @return the predicate
     */
    static @NotNull NbtPredicate glob(@NotNull String glob, boolean caseInsensitive) {
        return new Glob(compileGlob(glob, caseInsensitive));
    }

    /**
     * A regex predicate ({@code regex:}/{@code iregex:}) matching the whole stringified value.
     *
     * @param regex the regex text
     * @param caseInsensitive whether to match case-insensitively
     * @return the predicate
     */
    static @NotNull NbtPredicate regex(@NotNull String regex, boolean caseInsensitive) {
        return new Regex(Pattern.compile(regex, caseInsensitive ? Pattern.CASE_INSENSITIVE : 0));
    }

    /**
     * A numeric range predicate ({@code range:}).
     *
     * @param ranges the accepted ranges
     * @return the predicate
     */
    static @NotNull NbtPredicate range(@NotNull IntRanges ranges) {
        return new Range(ranges);
    }

    /**
     * A presence predicate ({@code exists:true|false}).
     *
     * @param expected whether the path is expected to reach a leaf
     * @return the predicate
     */
    static @NotNull NbtPredicate exists(boolean expected) {
        return new Exists(expected);
    }

    /**
     * A {@code raw:} predicate - applies {@code inner} to each reached branch's SNBT form.
     *
     * @param inner the wrapped predicate
     * @return the predicate
     */
    static @NotNull NbtPredicate raw(@NotNull NbtPredicate inner) {
        return new Raw(inner);
    }

    /**
     * An exact-value predicate, comparing numerically across tag types.
     *
     * @param literal the normalized literal tag
     */
    record Exact(@NotNull Tag<?> literal) implements NbtPredicate {
        /** {@inheritDoc} */
        @Override
        public boolean matches(@NotNull List<Tag<?>> leaves) {
            return leaves.stream().anyMatch(leaf -> NbtValues.valueEquals(leaf, this.literal));
        }
    }

    /**
     * A glob predicate over the stringified value.
     *
     * @param pattern the compiled glob-as-regex
     */
    record Glob(@NotNull Pattern pattern) implements NbtPredicate {
        /** {@inheritDoc} */
        @Override
        public boolean matches(@NotNull List<Tag<?>> leaves) {
            return leaves.stream().anyMatch(leaf -> this.pattern.matcher(NbtValues.stringify(leaf)).matches());
        }
    }

    /**
     * A regex predicate over the stringified value.
     *
     * @param pattern the compiled regex
     */
    record Regex(@NotNull Pattern pattern) implements NbtPredicate {
        /** {@inheritDoc} */
        @Override
        public boolean matches(@NotNull List<Tag<?>> leaves) {
            return leaves.stream().anyMatch(leaf -> this.pattern.matcher(NbtValues.stringify(leaf)).matches());
        }
    }

    /**
     * A numeric range predicate over integral leaf values.
     *
     * @param ranges the accepted ranges
     */
    record Range(@NotNull IntRanges ranges) implements NbtPredicate {
        /** {@inheritDoc} */
        @Override
        public boolean matches(@NotNull List<Tag<?>> leaves) {
            return leaves.stream().anyMatch(leaf -> leaf instanceof NumericalTag<?> number && this.ranges.contains(number.intValue()));
        }
    }

    /**
     * A presence predicate over the whole leaf set.
     *
     * @param expected whether a leaf is expected
     */
    record Exists(boolean expected) implements NbtPredicate {
        /** {@inheritDoc} */
        @Override
        public boolean matches(@NotNull List<Tag<?>> leaves) {
            return this.expected != leaves.isEmpty();
        }
    }

    /**
     * A {@code raw:} predicate applying an inner predicate to each leaf's SNBT string.
     *
     * @param inner the wrapped predicate
     */
    record Raw(@NotNull NbtPredicate inner) implements NbtPredicate {
        /** {@inheritDoc} */
        @Override
        public boolean matches(@NotNull List<Tag<?>> leaves) {
            return leaves.stream().anyMatch(leaf -> this.inner.matches(List.of(new StringTag(NbtValues.snbt(leaf)))));
        }
    }

    /**
     * Compiles an OptiFine glob ({@code *}/{@code ?}) to a {@link Pattern}, escaping regex
     * metacharacters. {@link Pattern#DOTALL} makes {@code *} span line terminators so a glob matches a
     * multi-line stringified value (e.g. a {@code raw:} branch's pretty-printed SNBT).
     */
    private static @NotNull Pattern compileGlob(@NotNull String glob, boolean caseInsensitive) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            switch (ch) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> {
                    if ("\\.[]{}()<>+-=!^$|".indexOf(ch) >= 0) regex.append('\\');
                    regex.append(ch);
                }
            }
        }
        int flags = Pattern.DOTALL | (caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);
        return Pattern.compile(regex.toString(), flags);
    }

}
