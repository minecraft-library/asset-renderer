package lib.minecraft.renderer.tooling.walk;

import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The one drive loop in the package. Every walk is this loop under a different stage stack: a
 * find is the drive with early exit, the latch-and-commit fold is declared cells plus a commit
 * whose resets the engine owns, a trace is the drive with a cursor-returning advance hook. The
 * loop's test order is itself contract: the identity sentinel binds at the cursor on the raw
 * chain, the recognizer sentinel and the budget bind on yields with the sentinel tested first,
 * and the budget only answers {@link Exit#BUDGET} when it refused a yield that arrived.
 */
final class Drive {

    private Drive() {}

    /** Whether the stage stack asks the loop to stop. */
    enum Verdict { CONTINUE, HALT }

    /** Where survivors and commit events land - terminals implement what they consume. */
    interface Sink {

        /**
         * Accepts a narrow-chain survivor or a post-stage commit event.
         *
         * @param value the flowing value
         * @return whether the walk continues
         */
        default @NotNull Verdict value(@NotNull Object value) {
            return Verdict.CONTINUE;
        }

        /**
         * Accepts an inverted-commit pair.
         *
         * @param key the latched key
         * @param value the decoded value
         * @return whether the walk continues
         */
        default @NotNull Verdict pair(@NotNull Object key, @NotNull Object value) {
            return Verdict.CONTINUE;
        }

    }

    /**
     * Runs a descriptor to its exit. Per-run state - cells, budgets, the visited set - is
     * minted here, so a descriptor is a reusable value and two runs yield identical sequences.
     *
     * @param descriptor the walk to run
     * @param tracer the advance hook, or {@code null} for a linear walk
     * @param sink where survivors and events land
     * @return how the walk ended
     */
    static @NotNull Exit run(@NotNull Descriptor descriptor, @Nullable Tracer tracer, @NotNull Sink sink) {
        Pipeline pipeline = new Pipeline(descriptor, sink);
        if (descriptor.source.missing() != null) return Exit.MISSING;
        Set<AbstractInsnNode> visited = tracer != null ? Collections.newSetFromMap(new IdentityHashMap<>()) : null;
        AbstractInsnNode cursor = descriptor.source.firstNode();
        int budget = descriptor.limit;
        while (true) {
            if (cursor == null) return Exit.END;
            if (cursor == descriptor.sentinelNode) return Exit.SENTINEL;
            if (visited != null && !visited.add(cursor)) return Exit.CYCLE;
            if (!descriptor.realOnly || cursor.getOpcode() >= 0) {
                if (descriptor.sentinelMatch != null && descriptor.sentinelMatch.matches(cursor)) return Exit.SENTINEL;
                if (budget == 0) return Exit.BUDGET;
                if (budget > 0) budget--;
                if (pipeline.offer(cursor) == Verdict.HALT) return Exit.HALTED;
            }
            if (tracer != null) {
                AbstractInsnNode next = tracer.advance(cursor);
                if (next == null) return Exit.HALTED;
                cursor = next;
            } else cursor = descriptor.source.backward() ? cursor.getPrevious() : cursor.getNext();
        }
    }

    /** The compiled stage stack of one run - validation, per-run cells, and the offer path. */
    private static final class Pipeline {

        private final @NotNull List<Function<Object, Object>> narrows = new ArrayList<>();
        private final @NotNull List<Interp<?>> drives = new ArrayList<>();
        private final @Nullable Cascade cascade;
        private final @NotNull Sink sink;

        Pipeline(@NotNull Descriptor descriptor, @NotNull Sink sink) {
            this.sink = sink;
            validate(descriptor);
            Cascade built = null;
            List<Function<Object, Object>> post = null;
            for (Descriptor.Stage stage : descriptor.stages) {
                switch (stage) {
                    case Descriptor.Narrow narrow -> (post != null ? post : this.narrows).add(narrow.step());
                    case Descriptor.DriveStage drive -> this.drives.add(drive.machine());
                    case Descriptor.Fold fold -> {
                        built = Cascade.tier1(fold);
                        post = new ArrayList<>();
                    }
                    case Descriptor.CommitAt commit -> requireCascade(built).commitMatch = commit.match();
                    case Descriptor.CommitOn commit -> requireCascade(built).commitOnDecoder = commit.valueDecoder();
                    case Descriptor.Feed feed -> built = tier2(built).attach(feed.cell());
                    case Descriptor.On on -> built = tier2(built).consume(on);
                    case Descriptor.Commit2 commit -> built = tier2(built).commit(commit);
                }
            }
            if (built != null) built.wire(sink, post == null ? List.of() : post);
            this.cascade = built;
        }

        private static @NotNull Cascade requireCascade(@Nullable Cascade cascade) {
            if (cascade == null) throw new ToolingException("A commit requires a preceding fold stage");
            return cascade;
        }

        private static @NotNull Cascade tier2(@Nullable Cascade cascade) {
            return cascade == null ? Cascade.tier2() : cascade;
        }

        /** The build-time rejections: what the types cannot reach is refused here. */
        private static void validate(@NotNull Descriptor descriptor) {
            boolean hasDrive = descriptor.stages.stream().anyMatch(s -> s instanceof Descriptor.DriveStage);
            boolean hasGeometry = descriptor.realOnly || descriptor.limit >= 0
                || descriptor.sentinelNode != null || descriptor.sentinelMatch != null;
            if (hasDrive && hasGeometry)
                throw new ToolingException("Geometry does not compose with drive - a bound mid-expression would leave the machine in an unspecified state");
            boolean narrowed = false;
            for (Descriptor.Stage stage : descriptor.stages) {
                if (stage instanceof Descriptor.Narrow) narrowed = true;
                boolean keepWindow = stage instanceof Descriptor.Fold fold && fold.keep() >= 0
                    || stage instanceof Descriptor.Feed feed && feed.cell() instanceof Cells.Window;
                if (narrowed && keepWindow)
                    throw new ToolingException("A keep window attaches only to the bare source - a narrowing stage precedes it");
            }
        }

        @NotNull Verdict offer(@NotNull AbstractInsnNode node) {
            for (Interp<?> machine : this.drives) machine.step(node);
            if (this.cascade != null) return this.cascade.offer(node);
            Object value = node;
            for (Function<Object, Object> step : this.narrows) {
                value = step.apply(value);
                if (value == null) return Verdict.CONTINUE;
            }
            return this.sink.value(value);
        }

    }

}
