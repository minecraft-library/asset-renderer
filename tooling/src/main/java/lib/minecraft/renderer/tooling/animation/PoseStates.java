package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PosePredicate;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The resting silhouette each state branch of a walked pose places - where the subject stands
 * when one question the body asks of its render state answers the other way.
 *
 * <p>A pose is folded against the frame its subjects rest in, and the branch a resting subject
 * does not take is dropped whole: a wolf's table carries the standing wolf, and the sitting one
 * - the body lowered onto its haunches, the tail and the hind legs placed by hand where that body
 * carries them - is nowhere in it. That placement is evidence the pose table otherwise loses: it
 * says which parts vanilla moves together, and it is the one silhouette of the subject vanilla
 * itself draws in that state. So each such branch is folded once more, against the same frame
 * with that one answer flipped, and what it places away from the resting row is written beside it.
 *
 * <p><b>A state is one answer flipped, never a product of answers.</b> A body that branches on
 * three flags is three states here, each the resting subject with one flag the other way. That is
 * linear in what the body asks rather than exponential, and it is also what the evidence is for:
 * a placement two flags decide between them has no single branch to be read from.
 *
 * <p><b>What is written is the silhouette at REST, over the mesh's own values.</b> Every figure
 * the tick drives is folded at what it rests at, so a channel that in the state is "the stride
 * plus a tuck" is written as the tuck alone, and a channel placed relative to the authored pivot
 * keeps the read of that pivot rather than a number this side cannot know - the mesh it is
 * evaluated against is the reader's. A channel the state leaves exactly where the resting row
 * leaves it is not written at all, so a state carries only what it moves.
 *
 * <p><b>A row two ages reach writes each state once, in a spelling that holds at both.</b> The
 * states are folded once per age; a channel every age places alike is written as it stands, a
 * position each age places exactly at the rest of every mesh drawn at that age is left out - the part
 * then stays where the row leaves it, which is each mesh's own rest - and anything else stops the
 * flow, see {@link #unify}. That proof is the one place a state silhouette is measured against a
 * mesh, and it never writes a number taken from one.
 *
 * <p>Nothing at render reads a silhouette. It is carried for what can be derived from it beside a
 * mesh, which is a question for the side that has the mesh.
 */
public final class PoseStates {

    /** What separates a member from the answer it stands at in a state's key. */
    private static final char AT = '=';

    /** The two spellings a boolean member takes where the fold reads it as a number. */
    private static final @NotNull String TRUE = "true";

    private static final @NotNull String FALSE = "false";

    /** The spelling of a unit figure moved to its completed value. */
    private static final @NotNull String ONE = "1";

    private PoseStates() {}

    /**
     * One state's silhouette - the bones and channels it places away from the resting row.
     *
     * @param bones each placed bone's position and rotation channels, at rest, over the mesh's
     *     own values where the branch placed them relative to it
     */
    record Silhouette(@NotNull Map<String, Map<PoseChannel, PoseExpr>> bones) {}

    /**
     * Derives every state silhouette of one walked pose against the frame its subjects rest in.
     *
     * <p>The states are the questions the body asks of its render state: each boolean it branches
     * on, flipped from what it rests at; each constant of an enum member it tests other than the
     * one it rests holding; and each unit figure it reads that rests at zero, moved to one - the
     * completed stand of an equine's {@code standAnimation}, a branch no comparison spells but a
     * placement all the same. A figure the tick drives is no state - it stays symbolic in the
     * shipped row already - and a branch on anything else this cannot flip is left alone.
     *
     * @param program the walked pose, as the walk left it
     * @param subjectRest which constant each enum member this subject rests holding - the frame the
     *     row is folded against
     * @param restDefaults the same for the model, read where the subject names no constant
     * @param questionDefaults what a question of a reference the state holds rests answering
     * @param inputDefaults what each figure rests at, one keyspace across every model
     * @param free the render-state figures the tick drives, which no state can settle
     * @param derived which further figures this model's renderer rebuilds from one of those
     * @return each state's silhouette keyed by the answer that reaches it, in key order, states
     *     placing nothing omitted
     */
    static @NotNull Map<String, Silhouette> of(
        @NotNull PoseProgram program, @NotNull Map<String, String> subjectRest,
        @NotNull Map<String, String> restDefaults, @NotNull Map<String, Float> questionDefaults,
        @NotNull Map<String, Float> inputDefaults, @NotNull Set<String> free,
        @NotNull Map<String, String> derived) {

        Toggles toggles = Toggles.of(program);
        if (toggles.isEmpty()) return Map.of();

        Map<String, Map<PoseChannel, PoseExpr>> resting =
            resting(program, subjectRest, restDefaults, questionDefaults, inputDefaults);
        Map<String, Silhouette> out = new TreeMap<>();

        // A figure the frame spells as a boolean is one, however the body reads it, and flips as one.
        SortedSet<String> booleans = new TreeSet<>(toggles.booleans());
        for (String figure : toggles.figures())
            if (TRUE.equals(subjectRest.get(figure)) || FALSE.equals(subjectRest.get(figure))) booleans.add(figure);

        for (String member : booleans) {
            if (free.contains(member) || derived.containsKey(member)) continue;
            String flipped = inputAtRest(member, subjectRest, inputDefaults) != 0f ? FALSE : TRUE;
            Map<String, String> frame = new TreeMap<>(subjectRest);
            frame.put(member, flipped);
            place(out, member + AT + flipped, program, frame, resting,
                restDefaults, questionDefaults, inputDefaults);
        }
        for (String figure : toggles.figures()) {
            if (booleans.contains(figure) || free.contains(figure) || derived.containsKey(figure)) continue;
            if (inputAtRest(figure, subjectRest, inputDefaults) != 0f) continue;
            Map<String, Float> moved = new TreeMap<>(inputDefaults);
            moved.put(figure, 1f);
            place(out, figure + AT + ONE, program, subjectRest, resting,
                restDefaults, questionDefaults, moved);
        }
        toggles.enums().forEach((member, constants) -> {
            String held = subjectRest.getOrDefault(member, restDefaults.get(member));
            for (String constant : constants) {
                if (constant.equals(held)) continue;
                Map<String, String> frame = new TreeMap<>(subjectRest);
                frame.put(member, constant);
                place(out, member + AT + constant, program, frame, resting,
                    restDefaults, questionDefaults, inputDefaults);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /**
     * One mesh a row is drawn on at one age, and where its parts rest as a pose's read of them
     * answers.
     *
     * @param coordinate the mesh's geometry coordinate
     * @param rests each part's resting value per position channel, holding only what a read of the
     *     part is known to answer on the mesh that ships
     */
    record Site(@NotNull String coordinate, @NotNull Map<String, Map<PoseChannel, Float>> rests) {}

    /**
     * The states one row writes for every age it is reached at, each channel in the one spelling that
     * holds at every site the row is drawn on.
     *
     * <p>A row two ages reach is ONE row, so each state it carries is read on every site at every age
     * - a statue spelled from it lands on whichever form it is installed on. The states are folded once
     * per age, and per state, bone and position or rotation channel:
     *
     * <ul>
     *   <li><b>every age places the same</b> - nothing at all, or one expression - and that is what is
     *       written, the reference age's own instance;</li>
     *   <li><b>the ages place a position apart, each exactly where every site drawn at that age rests
     *       the part</b>, and the row's own channel is the part's read or nothing - and the channel is
     *       left out, because a state leaves an unwritten channel where the row leaves it, which at
     *       each site is that site's rest, which is where the age drawn there places it;</li>
     *   <li><b>anything else</b> refuses the flow - no spelling the row has is exact at every site,
     *       and one age's value written for all of them is wrong at the others.</li>
     * </ul>
     *
     * <p>The order matters: a channel the age does not move is exact as it stands and is never
     * compared with a mesh, so a literal that merely equals some site's rest is never rewritten. A
     * rest is compared exactly, {@code -0.0} counting as {@code 0.0}, and a rest the site cannot
     * answer - a part its mesh lacks, a mesh flattened at a factor, a pivot a later pass moves - proves
     * nothing.
     *
     * @param model the row's model, for the refusal
     * @param byAge each reached age's silhouettes
     * @param reference the age whose expression an agreeing channel keeps, the one the row is folded at
     * @param row the bones the row ships
     * @param sites the meshes drawn at each reached age
     * @param left filled with each channel left at its sites' rest, spelled {@code state bone.channel}
     * @return each state's silhouette keyed by the answer that reaches it, in key order, states placing
     *     nothing omitted
     * @throws ToolingException if the ages place a channel apart and it is not provably at every
     *     site's rest
     */
    static @NotNull Map<String, Silhouette> unify(
        @NotNull String model, @NotNull SortedMap<Float, Map<String, Silhouette>> byAge, float reference,
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> row, @NotNull SortedMap<Float, List<Site>> sites,
        @NotNull List<String> left) {

        SortedSet<String> keys = new TreeSet<>();
        byAge.values().forEach(placed -> keys.addAll(placed.keySet()));
        Map<String, Silhouette> out = new TreeMap<>();
        for (String key : keys) {
            SortedSet<String> bones = new TreeSet<>();
            byAge.values().forEach(placed -> Optional.ofNullable(placed.get(key))
                .ifPresent(held -> bones.addAll(held.bones().keySet())));

            Map<String, Map<PoseChannel, PoseExpr>> kept = new LinkedHashMap<>();
            for (String bone : bones) {
                Map<PoseChannel, PoseExpr> channels = new LinkedHashMap<>();
                for (PoseChannel channel : PoseChannel.values()) {
                    Map<Float, PoseExpr> placed = new TreeMap<>();
                    byAge.forEach((age, states) -> placed.put(age, Optional.ofNullable(states.get(key))
                        .map(held -> held.bones().getOrDefault(bone, Map.of()).get(channel))
                        .orElse(null)));
                    if (placed.values().stream().allMatch(Objects::isNull)) continue;
                    PoseExpr held = placed.get(reference);
                    if (held != null && placed.values().stream().allMatch(here -> sameShape(here, held))) {
                        channels.put(channel, held);
                        continue;
                    }
                    if (!atEverySitesRest(bone, channel, placed, row, sites))
                        throw new ToolingException(
                            "Model '%s' state '%s' places '%s.%s' apart by age and not at every site's rest, which one row cannot say: %s",
                            model, key, bone, channel.token(), spelled(bone, channel, placed, sites)
                        );
                    left.add(key + ' ' + bone + '.' + channel.token());
                }
                if (!channels.isEmpty()) kept.put(bone, Collections.unmodifiableMap(channels));
            }
            if (!kept.isEmpty()) out.put(key, new Silhouette(Collections.unmodifiableMap(kept)));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Whether leaving a channel out of a state is exact at every site - a position, which the row
     * itself leaves as the part's own read, and which each age places either where the resting row
     * does or at a literal equal to the rest of every site drawn at that age.
     */
    private static boolean atEverySitesRest(
        @NotNull String bone, @NotNull PoseChannel channel, @NotNull Map<Float, PoseExpr> placed,
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> row, @NotNull SortedMap<Float, List<Site>> sites) {

        if (channel.kind() != PoseChannel.Kind.POSITION) return false;
        PoseExpr rowChannel = row.getOrDefault(bone, Map.of()).get(channel);
        if (rowChannel != null && !rowChannel.equals(new PoseExpr.BoneRead(bone, channel))) return false;
        for (Map.Entry<Float, PoseExpr> atAge : placed.entrySet()) {
            PoseExpr value = atAge.getValue();
            if (value == null) continue;
            if (!(value instanceof PoseExpr.Constant literal)) return false;
            List<Site> drawn = sites.getOrDefault(atAge.getKey(), List.of());
            if (drawn.isEmpty()) return false;
            for (Site site : drawn) {
                Float rest = site.rests().getOrDefault(bone, Map.of()).get(channel);
                if (rest == null || literal.value() != rest) return false;
            }
        }
        return true;
    }

    /** What each age places on one channel and where each site drawn there rests it, for a refusal. */
    private static @NotNull String spelled(
        @NotNull String bone, @NotNull PoseChannel channel, @NotNull Map<Float, PoseExpr> placed,
        @NotNull SortedMap<Float, List<Site>> sites) {

        return placed.entrySet()
            .stream()
            .map(atAge -> "age " + atAge.getKey() + " places "
                + (atAge.getValue() == null ? "the resting row's" : atAge.getValue().toString())
                + " over " + sites.getOrDefault(atAge.getKey(), List.of())
                .stream()
                .map(site -> site.coordinate() + " resting it at "
                    + Optional.ofNullable(site.rests().getOrDefault(bone, Map.of()).get(channel))
                    .map(String::valueOf)
                    .orElse("an unknown value"))
                .collect(Collectors.joining(", ", "[", "]")))
            .collect(Collectors.joining("; "));
    }

    /**
     * Where two folds of one row part, or empty where they spell one row - the same container steps,
     * the same bones and channels, the same flags and the same play sites, every expression compared
     * by {@link #sameShape}.
     *
     * @param here one fold
     * @param there the other
     * @return what first differs, spelled for a refusal, or empty where nothing does
     */
    static @NotNull Optional<String> whereApart(@NotNull PoseProgram here, @NotNull PoseProgram there) {
        if (here.container().size() != there.container().size()) return Optional.of("the container's steps");
        for (int step = 0; step < here.container().size(); step++) {
            Optional<PoseChannel> apart = channelApart(here.container().get(step), there.container().get(step));
            if (apart.isPresent()) return Optional.of("container step " + step + " " + apart.get().token());
        }
        if (!here.bones().keySet().equals(there.bones().keySet())) return Optional.of("the bones it writes");
        for (Map.Entry<String, Map<PoseChannel, PoseExpr>> bone : here.bones().entrySet()) {
            Optional<PoseChannel> apart = channelApart(bone.getValue(), there.bones().get(bone.getKey()));
            if (apart.isPresent()) return Optional.of(bone.getKey() + '.' + apart.get().token());
        }
        if (!here.flags().keySet().equals(there.flags().keySet())) return Optional.of("the flags it writes");
        for (Map.Entry<BoneFlag, Map<String, PoseExpr>> flag : here.flags().entrySet()) {
            Map<String, PoseExpr> other = there.flags().get(flag.getKey());
            if (!flag.getValue().keySet().equals(other.keySet())) return Optional.of("the bones '" + flag.getKey().token() + "' reaches");
            for (Map.Entry<String, PoseExpr> written : flag.getValue().entrySet()) {
                if (!sameShape(written.getValue(), other.get(written.getKey())))
                    return Optional.of(written.getKey() + '.' + flag.getKey().token());
            }
        }
        if (here.clipSites().size() != there.clipSites().size()) return Optional.of("the clips it plays");
        for (int at = 0; at < here.clipSites().size(); at++) {
            PoseClipSite site = here.clipSites().get(at);
            PoseClipSite other = there.clipSites().get(at);
            if (!site.clip().equals(other.clip()) || !site.drive().equals(other.drive()) || !site.state().equals(other.state())
                || !sameShape(site.condition(), other.condition())
                || !sameOperands(site.arguments(), other.arguments(), new IdentityHashMap<>()))
                return Optional.of("the play site of '" + site.clip() + "'");
        }
        return Optional.empty();
    }

    /** The first channel two written maps spell apart, or empty where they spell one map. */
    private static @NotNull Optional<PoseChannel> channelApart(
        @NotNull Map<PoseChannel, PoseExpr> here, @NotNull Map<PoseChannel, PoseExpr> there) {

        for (PoseChannel channel : PoseChannel.values()) {
            if (here.containsKey(channel) != there.containsKey(channel)
                || !sameShape(here.get(channel), there.get(channel))) return Optional.of(channel);
        }
        return Optional.empty();
    }

    /**
     * Folds the pose against one state's frame and keeps what it places away from the resting row.
     */
    private static void place(
        @NotNull Map<String, Silhouette> out, @NotNull String key, @NotNull PoseProgram program,
        @NotNull Map<String, String> frame, @NotNull Map<String, Map<PoseChannel, PoseExpr>> resting,
        @NotNull Map<String, String> restDefaults, @NotNull Map<String, Float> questionDefaults,
        @NotNull Map<String, Float> inputDefaults) {

        Map<String, Map<PoseChannel, PoseExpr>> placed =
            resting(program, frame, restDefaults, questionDefaults, inputDefaults);
        Map<String, Map<PoseChannel, PoseExpr>> moved = new LinkedHashMap<>();
        placed.forEach((bone, channels) -> {
            Map<PoseChannel, PoseExpr> atRest = resting.getOrDefault(bone, Map.of());
            Map<PoseChannel, PoseExpr> away = new LinkedHashMap<>();
            for (PoseChannel channel : PoseChannel.values()) {
                boolean places = switch (channel.kind()) {
                    case POSITION, ROTATION -> true;
                    case SCALE -> false;
                };
                if (!places) continue;
                PoseExpr here = channels.get(channel);
                if (here == null || sameShape(here, atRest.get(channel)) || !isPlacement(here)) continue;
                away.put(channel, here);
            }
            if (!away.isEmpty()) moved.put(bone, Collections.unmodifiableMap(away));
        });
        if (!moved.isEmpty()) out.put(key, new Silhouette(Collections.unmodifiableMap(moved)));
    }

    /**
     * Whether two folded channels spell one expression - the same shape node for node, each pair
     * of nodes visited once however many paths reach it.
     *
     * <p>A folded channel is a graph rather than a tree: a term reached down several paths is one
     * instance, and a comparison recursing per path - the record equality an {@code Op} inherits -
     * would visit it per path and not terminate on the shapes the shared table exists to compress.
     * The pairs already answered are memoized by identity on both sides, so a shared term is
     * compared once and answered thereafter.
     */
    static boolean sameShape(@Nullable PoseExpr here, @Nullable PoseExpr there) {
        return sameShape(here, there, new IdentityHashMap<>());
    }

    private static boolean sameShape(
        @Nullable PoseExpr here, @Nullable PoseExpr there,
        @NotNull Map<PoseExpr, Map<PoseExpr, Boolean>> answered) {

        if (here == there) return true;
        if (here == null || there == null) return false;
        Map<PoseExpr, Boolean> against = answered.computeIfAbsent(here, key -> new IdentityHashMap<>());
        Boolean known = against.get(there);
        if (known != null) return known;
        boolean same = switch (here) {
            case PoseExpr.Op op -> there instanceof PoseExpr.Op other
                && op.operator() == other.operator()
                && sameOperands(op.operands(), other.operands(), answered);
            case PoseExpr.Select select -> there instanceof PoseExpr.Select other
                && sameCondition(select.condition(), other.condition(), answered)
                && sameShape(select.whenTrue(), other.whenTrue(), answered)
                && sameShape(select.whenFalse(), other.whenFalse(), answered);
            default -> here.equals(there);
        };
        against.put(there, same);
        return same;
    }

    private static boolean sameOperands(
        @NotNull List<PoseExpr> here, @NotNull List<PoseExpr> there,
        @NotNull Map<PoseExpr, Map<PoseExpr, Boolean>> answered) {

        if (here.size() != there.size()) return false;
        for (int at = 0; at < here.size(); at++)
            if (!sameShape(here.get(at), there.get(at), answered)) return false;
        return true;
    }

    private static boolean sameCondition(
        @NotNull PosePredicate here, @NotNull PosePredicate there,
        @NotNull Map<PoseExpr, Map<PoseExpr, Boolean>> answered) {

        if (here == there) return true;
        return here.comparison() == there.comparison()
            && sameShape(here.left(), there.left(), answered)
            && sameShape(here.right(), there.right(), answered);
    }

    /**
     * Whether a channel folded at rest names a place at all - a literal that is no number is a
     * branch whose arithmetic has no resting value, a charge divided by a duration nothing
     * supplies, and it places nothing.
     */
    private static boolean isPlacement(@NotNull PoseExpr expr) {
        return !(expr instanceof PoseExpr.Constant literal) || Double.isFinite(literal.value());
    }

    /**
     * The pose reduced to what it holds at rest in one frame - every figure at what it rests at,
     * every decided branch taken, every literal operation collapsed, and only a read of the mesh
     * left standing. The same fold the row is emitted through, with nothing left free.
     */
    private static @NotNull Map<String, Map<PoseChannel, PoseExpr>> resting(
        @NotNull PoseProgram program, @NotNull Map<String, String> frame,
        @NotNull Map<String, String> restDefaults, @NotNull Map<String, Float> questionDefaults,
        @NotNull Map<String, Float> inputDefaults) {

        return PoseFold.fold(program, frame, restDefaults, questionDefaults, inputDefaults,
            Set.of(), Set.of(), Map.of()).bones();
    }

    /**
     * What a boolean figure reads as before anything happens to the subject - the subject's own
     * spelling first, then the input table, the same rule the fold reads it by.
     */
    private static float inputAtRest(
        @NotNull String member, @NotNull Map<String, String> subjectRest,
        @NotNull Map<String, Float> inputDefaults) {

        String held = subjectRest.get(member);
        if (TRUE.equals(held)) return 1f;
        if (FALSE.equals(held)) return 0f;
        return inputDefaults.getOrDefault(member, 0f);
    }

    /**
     * The questions a walked pose asks of its render state that one answer can flip - the
     * booleans it compares against zero, the enum members it tests against a constant, and the
     * figures it reads at all.
     *
     * @param booleans the boolean render-state fields the body branches on
     * @param enums each enum member the body tests, with every constant it tests it against
     * @param figures every render-state figure the body reads, however it reads it
     */
    private record Toggles(
        @NotNull SortedSet<String> booleans,
        @NotNull SortedMap<String, SortedSet<String>> enums,
        @NotNull SortedSet<String> figures
    ) {

        /** Whether the body asks nothing a state could flip. */
        boolean isEmpty() {
            return this.booleans.isEmpty() && this.enums.isEmpty() && this.figures.isEmpty();
        }

        /** Every toggle one walked pose names, visiting each node once however many paths reach it. */
        static @NotNull Toggles of(@NotNull PoseProgram program) {
            Toggles toggles = new Toggles(new TreeSet<>(), new TreeMap<>(), new TreeSet<>());
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Map<PoseChannel, PoseExpr> step : program.container())
                for (PoseExpr expr : step.values()) toggles.collect(expr, seen);
            for (Map<PoseChannel, PoseExpr> channels : program.bones().values())
                for (PoseExpr expr : channels.values()) toggles.collect(expr, seen);
            for (Map<String, PoseExpr> written : program.flags().values())
                for (PoseExpr expr : written.values()) toggles.collect(expr, seen);
            return toggles;
        }

        private void collect(@NotNull PoseExpr expr, @NotNull Set<Object> seen) {
            if (!seen.add(expr)) return;
            switch (expr) {
                case PoseExpr.Input input -> this.figures.add(input.field());
                case PoseExpr.Op operation -> {
                    for (PoseExpr operand : operation.operands()) this.collect(operand, seen);
                }
                case PoseExpr.Select select -> {
                    this.collect(select.condition(), seen);
                    this.collect(select.whenTrue(), seen);
                    this.collect(select.whenFalse(), seen);
                }
                case PoseExpr.Answered.EnumMatch test ->
                    this.enums.computeIfAbsent(test.field(), field -> new TreeSet<>()).add(test.constant());
                default -> { }
            }
        }

        private void collect(@NotNull PosePredicate predicate, @NotNull Set<Object> seen) {
            if (!seen.add(predicate)) return;
            String member = booleanTested(predicate);
            if (member != null) this.booleans.add(member);
            this.collect(predicate.left(), seen);
            this.collect(predicate.right(), seen);
        }

        /**
         * The boolean field a comparison tests, or {@code null} for any other comparison.
         *
         * <p>A boolean the render state declares arrives as a number and is compared against
         * zero for equality, which is the one shape a branch on it can take; a figure compared
         * against a threshold, or against zero by order, is a number and no state.
         */
        private static String booleanTested(@NotNull PosePredicate compare) {
            if (compare.comparison() != PosePredicate.Comparison.EQ
                && compare.comparison() != PosePredicate.Comparison.NE) return null;
            if (compare.left() instanceof PoseExpr.Input input && isZero(compare.right())) return input.field();
            if (compare.right() instanceof PoseExpr.Input input && isZero(compare.left())) return input.field();
            return null;
        }

        private static boolean isZero(@NotNull PoseExpr expr) {
            return expr instanceof PoseExpr.Constant literal && literal.value() == 0d;
        }

    }

    /**
     * A silhouette as the program shape the writer spells a row's bones through - bones alone,
     * no container and no clip.
     *
     * @param model the row's model name
     * @param silhouette the silhouette to spell
     * @return the bones-only program
     */
    static @NotNull PoseProgram asProgram(@NotNull String model, @NotNull Silhouette silhouette) {
        return new PoseProgram(model, List.of(), silhouette.bones(), Map.of(), List.of());
    }

}
