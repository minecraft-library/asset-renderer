package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.ListTag;
import lib.minecraft.nbt.tag.Tag;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * An OptiFine CIT {@code nbt.<path>} selector - the list/wildcard/count-aware walker nbt-factory's
 * compound-only {@code getPath} cannot express (08-siblings §8). A path is a sequence of steps; the
 * walk fans out over wildcards, so {@link #walk} yields every leaf a path reaches.
 *
 * @param steps the ordered path steps
 */
public record NbtPath(@NotNull ConcurrentList<Step> steps) {

    /** One step in a path. */
    public sealed interface Step permits Key, Index, Wildcard, Count {}

    /**
     * A compound key (also a named list access when the token is numeric).
     *
     * @param name the key name
     */
    public record Key(@NotNull String name) implements Step {}

    /**
     * A numeric element index (also a numeric compound key).
     *
     * @param value the element index
     */
    public record Index(int value) implements Step {}

    /** The {@code *} wildcard - fans out over every list element or compound value. */
    public record Wildcard() implements Step {}

    /** The terminal {@code count} pseudo-key - a list's size (or a real {@code count} compound key). */
    public record Count() implements Step {}

    /**
     * Parses a dotted path. {@code \.} escapes a literal dot in a key; {@code *} is a wildcard;
     * {@code count} is the size pseudo-key; a purely numeric token is an {@link Index}; anything else
     * is a {@link Key}.
     *
     * @param raw the dotted path text
     * @return the parsed path
     */
    public static @NotNull NbtPath parse(@NotNull String raw) {
        List<Step> steps = new ArrayList<>();
        for (String token : splitEscaped(raw)) {
            if (token.equals("*")) steps.add(new Wildcard());
            else if (token.equals("count")) steps.add(new Count());
            else if (isIndex(token)) steps.add(new Index(Integer.parseInt(token)));
            else steps.add(new Key(token));
        }
        return new NbtPath(Concurrent.adoptList(steps).toUnmodifiable());
    }

    /**
     * Walks this path from a root compound, returning every reachable leaf. A mid-path type mismatch
     * (a scalar where a container is expected) yields no leaves, which the predicate reads as "absent".
     *
     * @param root the item's root compound
     * @return the reached leaves; empty when the path resolves to nothing
     */
    public @NotNull Stream<Tag<?>> walk(@NotNull CompoundTag root) {
        List<Tag<?>> current = new ArrayList<>();
        current.add(root);
        for (Step step : this.steps) {
            List<Tag<?>> next = new ArrayList<>();
            for (Tag<?> tag : current) apply(step, tag, next);
            current = next;
            if (current.isEmpty()) break;
        }
        return current.stream();
    }

    /** Applies one step to one tag, appending every resulting tag to {@code out}. */
    private static void apply(@NotNull Step step, @NotNull Tag<?> tag, @NotNull List<Tag<?>> out) {
        switch (step) {
            case Key key -> {
                if (tag instanceof CompoundTag compound) addKey(compound, key.name(), out);
                else if (tag instanceof ListTag<?> list && isIndex(key.name())) addIndex(list, Integer.parseInt(key.name()), out);
            }
            case Index index -> {
                if (tag instanceof ListTag<?> list) addIndex(list, index.value(), out);
                else if (tag instanceof CompoundTag compound) addKey(compound, String.valueOf(index.value()), out);
            }
            case Wildcard ignored -> {
                if (tag instanceof CompoundTag compound) out.addAll(compound.values());
                else if (tag instanceof ListTag<?> list) out.addAll(list);
            }
            case Count ignored -> {
                if (tag instanceof ListTag<?> list) out.add(new IntTag(list.size()));
                else if (tag instanceof CompoundTag compound) addKey(compound, "count", out);
            }
        }
    }

    private static void addKey(@NotNull CompoundTag compound, @NotNull String key, @NotNull List<Tag<?>> out) {
        Tag<?> value = compound.get(key);
        if (value != null) out.add(value);
    }

    private static void addIndex(@NotNull ListTag<?> list, int index, @NotNull List<Tag<?>> out) {
        if (index >= 0 && index < list.size()) out.add(list.get(index));
    }

    /** Splits a path on unescaped dots, unescaping {@code \.} to a literal dot. */
    private static @NotNull List<String> splitEscaped(@NotNull String raw) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '\\' && i + 1 < raw.length() && raw.charAt(i + 1) == '.') {
                current.append('.');
                i++;
            } else if (ch == '.') {
                tokens.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        tokens.add(current.toString());
        return tokens;
    }

    /** Whether a token is a non-negative integer index that fits an {@code int}. */
    private static boolean isIndex(@NotNull String token) {
        if (token.isEmpty() || token.length() > 9) return false;
        return token.chars().allMatch(Character::isDigit);
    }

}
