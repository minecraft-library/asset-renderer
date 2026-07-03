package lib.minecraft.renderer.asset.rule;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A single condition to check against an NBT string value. Matches the four Optifine CIT value
 * prefixes ({@code pattern:}, {@code ipattern:}, {@code regex:}, {@code iregex:}) plus the plain
 * exact-match form that has no prefix.
 */
public sealed interface NbtCondition {

    /**
     * Tests whether the condition matches the given value.
     *
     * @param value the actual NBT string value, or empty string if absent
     * @return true if the condition accepts the value
     */
    boolean test(@NotNull String value);

    /**
     * Parses an Optifine CIT value expression into a condition.
     * <p>
     * Recognizes the {@code pattern:}, {@code ipattern:}, {@code regex:}, {@code iregex:} prefixes
     * and falls back to {@link Exact} for plain strings.
     *
     * @param expression the raw value expression
     * @return the parsed condition
     */
    static @NotNull NbtCondition parse(@NotNull String expression) {
        if (expression.startsWith("pattern:"))
            return new Glob(expression.substring("pattern:".length()), false);
        if (expression.startsWith("ipattern:"))
            return new Glob(expression.substring("ipattern:".length()), true);
        if (expression.startsWith("regex:"))
            return new Regex(Pattern.compile(expression.substring("regex:".length())));
        if (expression.startsWith("iregex:"))
            return new Regex(Pattern.compile(expression.substring("iregex:".length()), Pattern.CASE_INSENSITIVE));
        return new Exact(expression);
    }

    /**
     * Plain case-sensitive string equality against a fixed value.
     *
     * @param value the exact string the NBT value must equal
     */
    record Exact(@NotNull String value) implements NbtCondition {
        /** {@inheritDoc} */
        @Override public boolean test(@NotNull String value) {
            return this.value.equals(value);
        }
    }

    /**
     * Glob matching in the Optifine/MCPatcher shell-style syntax: {@code *} matches zero or more
     * characters and {@code ?} matches a single character; every other character is literal. The
     * glob is compiled to an anchored regex per {@link #globToRegex} on each {@code test}.
     *
     * @param pattern the glob pattern
     * @param ignoreCase whether the match is case-insensitive (the {@code ipattern:} prefix)
     */
    record Glob(@NotNull String pattern, boolean ignoreCase) implements NbtCondition {
        /** {@inheritDoc} */
        @Override public boolean test(@NotNull String value) {
            String regex = globToRegex(this.pattern);
            Pattern compiled = this.ignoreCase
                ? Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
                : Pattern.compile(regex);
            return compiled.matcher(value).matches();
        }

        /**
         * Translates a shell-style glob into an anchored Java regex: {@code *} becomes {@code .*},
         * {@code ?} becomes {@code .}, every other regex metacharacter is backslash-escaped to its
         * literal, and the whole expression is wrapped in {@code ^...$} so {@code test} requires a
         * full-string match.
         *
         * @param glob the glob pattern
         * @return the equivalent anchored regex source
         */
        private static @NotNull String globToRegex(@NotNull String glob) {
            StringBuilder sb = new StringBuilder(glob.length() + 2);
            sb.append('^');
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                switch (c) {
                    case '*' -> sb.append(".*");
                    case '?' -> sb.append('.');
                    case '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> sb.append('\\').append(c);
                    default -> sb.append(c);
                }
            }
            sb.append('$');
            return sb.toString();
        }
    }

    /**
     * Java regular-expression matching, requiring a full-string match via
     * {@link Matcher#matches()}. Case-insensitivity is baked into the compiled pattern at parse
     * time (the {@code iregex:} prefix).
     *
     * @param pattern the compiled regex the NBT value must fully match
     */
    record Regex(@NotNull Pattern pattern) implements NbtCondition {
        /** {@inheritDoc} */
        @Override public boolean test(@NotNull String value) {
            return this.pattern.matcher(value).matches();
        }
    }

}
