package lib.minecraft.renderer.tooling.walk;

import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An immutable walk descriptor - the source, the geometry and the stage list as a persistent
 * value. Every stage call returns a new descriptor sharing its prefix; per-run state (cells,
 * budgets, visited sets) is minted by the terminal, so one descriptor walked twice yields the
 * identical sequence.
 */
final class Descriptor {

    /** Where a walk starts and which way it steps. */
    record Source(
        @Nullable MethodNode method,
        @Nullable AbstractInsnNode anchor,
        boolean anchorInclusive,
        boolean backward,
        @Nullable Missing missing
    ) {

        static @NotNull Source over(@NotNull MethodNode method) {
            return new Source(method, null, false, false, null);
        }

        static @NotNull Source missing(@NotNull Missing missing) {
            return new Source(null, null, false, false, missing);
        }

        static @NotNull Source from(@Nullable AbstractInsnNode anchor) {
            return new Source(null, anchor, true, false, null);
        }

        static @NotNull Source after(@NotNull AbstractInsnNode anchor) {
            return new Source(null, anchor, false, false, null);
        }

        static @NotNull Source before(@NotNull AbstractInsnNode anchor) {
            return new Source(null, anchor, false, true, null);
        }

        /** The first cursor position, or {@code null} for an empty walk. */
        @Nullable AbstractInsnNode firstNode() {
            if (this.method != null) return this.method.instructions.getFirst();
            if (this.anchor == null) return null;
            if (this.anchorInclusive) return this.anchor;
            return this.backward ? this.anchor.getPrevious() : this.anchor.getNext();
        }

    }

    /** One declared stage. Declaration order is the cascade's claiming order. */
    sealed interface Stage permits Narrow, Fold, CommitAt, CommitOn, Feed, On, Commit2, DriveStage {}

    /** A narrowing step: transform the flowing value, {@code null} drops the element. */
    record Narrow(@NotNull Function<Object, @Nullable Object> step) implements Stage {}

    /** The single fold cell of a linear relay, with its declared disciplines. */
    record Fold(
        boolean gather,
        @NotNull Function<AbstractInsnNode, @Nullable Object> decoder,
        int keep,
        boolean strict,
        boolean firstWins,
        boolean retain,
        @Nullable Match<?> openAt,
        @Nullable Match<?> sealAt,
        @Nullable Match<?> takeAt,
        @NotNull List<Match<?>> resetAts
    ) implements Stage {

        static @NotNull Fold of(boolean gather, @NotNull Function<AbstractInsnNode, @Nullable Object> decoder) {
            return new Fold(gather, decoder, -1, false, false, false, null, null, null, List.of());
        }

        @NotNull Fold withKeep(int n) {
            return new Fold(this.gather, this.decoder, n, this.strict, this.firstWins, this.retain, this.openAt, this.sealAt, this.takeAt, this.resetAts);
        }

        @NotNull Fold withStrict() {
            return new Fold(this.gather, this.decoder, this.keep, true, this.firstWins, this.retain, this.openAt, this.sealAt, this.takeAt, this.resetAts);
        }

        @NotNull Fold withFirstWins() {
            return new Fold(this.gather, this.decoder, this.keep, this.strict, true, this.retain, this.openAt, this.sealAt, this.takeAt, this.resetAts);
        }

        @NotNull Fold withRetain() {
            return new Fold(this.gather, this.decoder, this.keep, this.strict, this.firstWins, true, this.openAt, this.sealAt, this.takeAt, this.resetAts);
        }

        @NotNull Fold withOpenAt(@NotNull Match<?> match) {
            return new Fold(this.gather, this.decoder, this.keep, this.strict, this.firstWins, this.retain, match, this.sealAt, this.takeAt, this.resetAts);
        }

        @NotNull Fold withSealAt(@NotNull Match<?> match) {
            return new Fold(this.gather, this.decoder, this.keep, this.strict, this.firstWins, this.retain, this.openAt, match, this.takeAt, this.resetAts);
        }

        @NotNull Fold withTakeAt(@NotNull Match<?> match) {
            return new Fold(this.gather, this.decoder, this.keep, this.strict, this.firstWins, this.retain, this.openAt, this.sealAt, match, this.resetAts);
        }

        @NotNull Fold withResetAt(@NotNull Match<?> match) {
            List<Match<?>> resets = new ArrayList<>(this.resetAts);
            resets.add(match);
            return new Fold(this.gather, this.decoder, this.keep, this.strict, this.firstWins, this.retain, this.openAt, this.sealAt, this.takeAt, List.copyOf(resets));
        }

    }

    /** The commit recognizer of a linear relay - emission plus the engine-owned reset. */
    record CommitAt(@NotNull Match<?> match) implements Stage {}

    /** The inverted commit - key latched earlier, value decoded at the commit. */
    record CommitOn(@NotNull Function<AbstractInsnNode, @Nullable Object> valueDecoder) implements Stage {}

    /** A declared cell's decode-consume attachment. */
    record Feed(@NotNull Cells.Cell<?> cell) implements Stage {}

    /** A recognizer hook: consume-on-hit; diagnostics and cross-cell couplings live here. */
    record On(@NotNull Match<?> match, @NotNull Consumer<Object> hook) implements Stage {}

    /** A declared-cell commit: the action, then the engine clears the attached cells (or the named subset). */
    record Commit2(@NotNull Match<?> match, @NotNull Consumer<Object> action, @Nullable List<Cells.Cell<?>> clearing) implements Stage {}

    /** A linear interpreter riding the walk - every yield is stepped through the machine. */
    record DriveStage(@NotNull Interp<?> machine) implements Stage {}

    final @NotNull Source source;
    final boolean realOnly;
    final @Nullable AbstractInsnNode sentinelNode;
    final @Nullable Match<?> sentinelMatch;
    final int limit;
    final @NotNull List<Stage> stages;

    Descriptor(@NotNull Source source) {
        this(source, false, null, null, -1, List.of());
    }

    private Descriptor(@NotNull Source source, boolean realOnly, @Nullable AbstractInsnNode sentinelNode,
        @Nullable Match<?> sentinelMatch, int limit, @NotNull List<Stage> stages) {
        this.source = source;
        this.realOnly = realOnly;
        this.sentinelNode = sentinelNode;
        this.sentinelMatch = sentinelMatch;
        this.limit = limit;
        this.stages = stages;
    }

    @NotNull Descriptor real() {
        return new Descriptor(this.source, true, this.sentinelNode, this.sentinelMatch, this.limit, this.stages);
    }

    @NotNull Descriptor untilNode(@Nullable AbstractInsnNode sentinel) {
        return new Descriptor(this.source, this.realOnly, sentinel, this.sentinelMatch, this.limit, this.stages);
    }

    @NotNull Descriptor untilMatch(@NotNull Match<?> stop) {
        return new Descriptor(this.source, this.realOnly, this.sentinelNode, stop, this.limit, this.stages);
    }

    @NotNull Descriptor limit(int maxYields) {
        return new Descriptor(this.source, this.realOnly, this.sentinelNode, this.sentinelMatch, maxYields, this.stages);
    }

    @NotNull Descriptor with(@NotNull Stage stage) {
        List<Stage> next = new ArrayList<>(this.stages);
        next.add(stage);
        return new Descriptor(this.source, this.realOnly, this.sentinelNode, this.sentinelMatch, this.limit, List.copyOf(next));
    }

    /** Replaces the single fold declaration - the modifier tokens rebuild it in place. */
    @NotNull Descriptor withFold(@NotNull Fold fold) {
        List<Stage> next = new ArrayList<>(this.stages);
        for (int i = next.size() - 1; i >= 0; i--)
            if (next.get(i) instanceof Fold) {
                next.set(i, fold);
                return new Descriptor(this.source, this.realOnly, this.sentinelNode, this.sentinelMatch, this.limit, List.copyOf(next));
            }
        throw new ToolingException("No fold stage to modify");
    }

    /** The single fold declaration, or {@code null} on a narrow chain. */
    @Nullable Fold fold() {
        for (Stage stage : this.stages)
            if (stage instanceof Fold fold) return fold;
        return null;
    }

    /** Narrows the last declared-cell commit to the named subset. */
    @NotNull Descriptor clearingLastCommit(@NotNull List<Cells.Cell<?>> subset) {
        List<Stage> next = new ArrayList<>(this.stages);
        for (int i = next.size() - 1; i >= 0; i--)
            if (next.get(i) instanceof Commit2 commit) {
                next.set(i, new Commit2(commit.match(), commit.action(), List.copyOf(subset)));
                return new Descriptor(this.source, this.realOnly, this.sentinelNode, this.sentinelMatch, this.limit, List.copyOf(next));
            }
        throw new ToolingException("clearing requires a preceding commitAt");
    }

}
