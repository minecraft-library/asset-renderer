package lib.minecraft.renderer.tooling.walk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.Function;

/**
 * The per-run stage cascade - one execution model for both fold tiers. Per yielded element:
 * stages run in declaration order and the first that fires claims the element; the commit test
 * sees only unclaimed elements; emission precedes the engine-owned reset; {@code resetAt} never
 * claims and fires after the commit on a shared node; an element no stage consumed is the
 * fall-through event that strict cells clear on.
 */
final class Cascade {

    private final @Nullable Descriptor.Fold fold;
    @Nullable Match<?> commitMatch;
    @Nullable Function<AbstractInsnNode, @Nullable Object> commitOnDecoder;
    private final @NotNull List<Descriptor.Stage> consumes = new ArrayList<>();
    private final @NotNull List<Descriptor.Commit2> commits = new ArrayList<>();
    private final @NotNull List<Cells.Cell<?>> attached = new ArrayList<>();

    private final @NotNull ArrayDeque<Object> buffer = new ArrayDeque<>();
    private @Nullable Object latch;
    private @Nullable Object register;
    private @Nullable List<Object> parked;
    private boolean gateOpen;

    private Drive.Sink sink = new Drive.Sink() {};
    private @NotNull List<Function<Object, Object>> post = List.of();
    private final @Nullable WalkDump dump = WalkDump.armed();

    private Cascade(@Nullable Descriptor.Fold fold) {
        this.fold = fold;
    }

    static @NotNull Cascade tier1(@NotNull Descriptor.Fold fold) {
        return new Cascade(fold);
    }

    static @NotNull Cascade tier2() {
        return new Cascade(null);
    }

    @NotNull Cascade attach(@NotNull Cells.Cell<?> cell) {
        this.attached.add(cell);
        this.consumes.add(new Descriptor.Feed(cell));
        return this;
    }

    @NotNull Cascade consume(@NotNull Descriptor.On on) {
        this.consumes.add(on);
        return this;
    }

    @NotNull Cascade commit(@NotNull Descriptor.Commit2 commit) {
        this.commits.add(commit);
        return this;
    }

    void wire(@NotNull Drive.Sink sink, @NotNull List<Function<Object, Object>> post) {
        this.sink = sink;
        this.post = post;
    }

    @NotNull Drive.Verdict offer(@NotNull AbstractInsnNode node) {
        return this.fold != null ? tier1Offer(node, this.fold) : tier2Offer(node);
    }

    private @NotNull Drive.Verdict tier1Offer(@NotNull AbstractInsnNode node, @NotNull Descriptor.Fold fold) {
        boolean segmented = fold.openAt() != null || fold.sealAt() != null;
        boolean consumed = false;
        if (fold.openAt() != null && fold.openAt().matches(node)) {
            this.gateOpen = true;
            this.buffer.clear();
            consumed = true;
            if (this.dump != null) this.dump.claim(node, "openAt", state());
        } else if (fold.sealAt() != null && fold.sealAt().matches(node) && this.gateOpen) {
            this.parked = List.copyOf(this.buffer);
            this.buffer.clear();
            consumed = true;
            if (this.dump != null) this.dump.claim(node, "sealAt", state());
        }
        if (!consumed && (!segmented || this.gateOpen)) {
            if (fold.gather()) {
                Object value = fold.decoder().apply(node);
                if (value != null) {
                    this.buffer.addLast(value);
                    if (fold.keep() >= 0 && this.buffer.size() > fold.keep()) this.buffer.removeFirst();
                    consumed = true;
                    if (this.dump != null) this.dump.claim(node, "gather", state());
                }
            } else if (!(fold.firstWins() && this.latch != null)) {
                Object value = fold.decoder().apply(node);
                if (value != null) {
                    this.latch = value;
                    this.register = null;
                    consumed = true;
                    if (this.dump != null) this.dump.claim(node, "latch", state());
                }
            }
        }
        if (!consumed && fold.takeAt() != null && fold.takeAt().matches(node) && this.latch != null) {
            this.register = this.latch;
            this.latch = null;
            consumed = true;
            if (this.dump != null) this.dump.claim(node, "takeAt", state());
        }
        Drive.Verdict verdict = Drive.Verdict.CONTINUE;
        if (!consumed && this.commitMatch != null && this.commitMatch.matches(node)) {
            consumed = true;
            verdict = emit(node, fold, segmented);
        } else if (!consumed && this.commitOnDecoder != null && this.latch != null) {
            Object value = this.commitOnDecoder.apply(node);
            if (value != null) {
                Object key = this.latch;
                this.latch = null;
                consumed = true;
                if (this.dump != null) this.dump.commit(node, "commitOn", state());
                verdict = this.sink.pair(key, value);
            }
        }
        for (Match<?> reset : fold.resetAts())
            if (reset.matches(node)) {
                if (fold.gather()) this.buffer.clear();
                else this.latch = null;
                if (this.dump != null) this.dump.reset(node, "resetAt", state());
            }
        if (!consumed) {
            if (fold.strict()) {
                if (fold.gather()) this.buffer.clear();
                else this.latch = null;
            }
            if (this.dump != null) this.dump.fallThrough(node, state());
        }
        return verdict;
    }

    /** Emission, then the unconditional reset; the event snapshots its payload before the clear. */
    private @NotNull Drive.Verdict emit(@NotNull AbstractInsnNode node, @NotNull Descriptor.Fold fold, boolean segmented) {
        if (this.dump != null) this.dump.commit(node, "commitAt", state());
        Object event = null;
        if (fold.gather()) {
            if (!segmented) event = CommitWalk.Commit.gathered(node, List.copyOf(this.buffer));
            else if (this.parked != null) event = CommitWalk.Commit.gathered(node, this.parked);
        } else event = CommitWalk.Commit.latched(node, fold.takeAt() != null ? this.register : this.latch);
        if (!fold.retain()) {
            this.buffer.clear();
            this.latch = null;
            this.register = null;
        }
        this.parked = null;
        this.gateOpen = false;
        if (this.dump != null) this.dump.reset(node, "commit-reset", state());
        if (event == null) return Drive.Verdict.CONTINUE;
        Object value = event;
        for (Function<Object, Object> step : this.post) {
            value = step.apply(value);
            if (value == null) return Drive.Verdict.CONTINUE;
        }
        return this.sink.value(value);
    }

    private @NotNull Drive.Verdict tier2Offer(@NotNull AbstractInsnNode node) {
        boolean consumed = false;
        for (Descriptor.Stage stage : this.consumes) {
            if (stage instanceof Descriptor.Feed feed && feedCell(feed.cell(), node)) {
                consumed = true;
                if (this.dump != null) this.dump.claim(node, "feed", state());
                break;
            }
            if (stage instanceof Descriptor.On on && on.match().matches(node)) {
                on.hook().accept(node);
                consumed = true;
                if (this.dump != null) this.dump.claim(node, "on", state());
                break;
            }
        }
        if (!consumed)
            for (Descriptor.Commit2 commit : this.commits)
                if (commit.match().matches(node)) {
                    commit.action().accept(node);
                    if (this.dump != null) this.dump.commit(node, "commitAt", state());
                    for (Cells.Cell<?> cell : commit.clearing() != null ? commit.clearing() : this.attached)
                        cell.clear();
                    if (this.dump != null) this.dump.reset(node, "commit-reset", state());
                    consumed = true;
                    break;
                }
        if (!consumed && this.dump != null) this.dump.fallThrough(node, state());
        return Drive.Verdict.CONTINUE;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean feedCell(@NotNull Cells.Cell<?> cell, @NotNull AbstractInsnNode node) {
        Object value = cell.decode(node);
        if (value == null) return false;
        ((Cells.Cell) cell).push(value);
        return true;
    }

    /** The installed cells' content, formatted for the state dump. */
    private @NotNull String state() {
        if (this.fold != null)
            return "cells{buffer=" + List.copyOf(this.buffer) + " latch=" + this.latch + " register=" + this.register
                + " parked=" + this.parked + " gate=" + (this.gateOpen ? "OPEN" : "CLOSED") + "}";
        StringJoiner cells = new StringJoiner(" ", "cells{", "}");
        for (Cells.Cell<?> cell : this.attached) cells.add(cell.describe());
        return cells.toString();
    }

}
