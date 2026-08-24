package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

/**
 * Resolves a walked pose against the frame an offline subject stands in, leaving only what the tick
 * can still move.
 *
 * <p>A {@code setupAnim} walk follows both arms of everything it cannot decide, so what it produces
 * is an expression language over the whole render state. But an offline subject answers every one of
 * those questions at rest, out of four tables this same build wrote - so every branch in the walked
 * program was already decided three altitudes upstream, and evaluating it per frame re-derives an
 * answer the generator knew. This is where that answer is taken once.
 *
 * <p><b>Only the tick is free.</b> Every leaf naming one of the driven figures stays symbolic and
 * everything else becomes the literal it rests at, so a condition over the rest resolves and the arm
 * it does not take is dropped whole. The dropped arm is the bulk of it: most of the walked graph sits
 * below a condition an ancestor already decided and no render has ever evaluated it.
 *
 * <p><b>What rests at what is read from the runtime's own rule, not from a second one.</b> A figure
 * is the subject's own {@code true}/{@code false} then the input table; an enum member is the
 * subject's own constant then the model's; a question is the model's answer, and an emptiness nobody
 * supplied is empty rather than absent. A reference nobody modelled and an array nobody filled both
 * answer nothing. Divergence here does not fail - it poses a subject somewhere vanilla never puts it
 * and renders as though it were deliberate.
 *
 * <p><b>An operation every operand of which is a literal collapses onto its result</b>, through the
 * same builder the walk folds with - so what is written is what that operation would have answered
 * had the walk been able to resolve it, operand for operand and narrowing at each operator's own
 * width. Evaluating the chain in double and narrowing once at the end is the thing that would part
 * from it, and it is not what happens here.
 *
 * <p><b>A bone read is never folded</b>, though not one of them is live. A channel written back to
 * what the mesh already held keeps the mesh's own degrees, and a literal standing in for that read
 * would put authored degrees through a decimal and walk every bone by an ulp a render.
 *
 * <p><b>The result is a graph and stays one.</b> Every node is folded once, memoized by identity, so
 * a sub-expression reached down six paths yields one residual reached down six paths. That is safe
 * rather than merely careful: a row has exactly one frame, so a shared node has one binding and there
 * is nothing to specialize it to. Folding per path would compute the same answer and write a tree.
 */
final class PoseFold {

    /** What {@code isEmpty} answers of a stack nobody put anything in. */
    private static final @NotNull String IS_EMPTY = "isEmpty";

    /** The two spellings a boolean member takes where a figure reads it as a number. */
    private static final @NotNull String TRUE = "true";

    private static final @NotNull String FALSE = "false";

    private final @NotNull Map<String, String> subjectRest;

    private final @NotNull Map<String, String> restDefaults;

    private final @NotNull Map<String, Float> questionDefaults;

    private final @NotNull Map<String, Float> inputDefaults;

    private final @NotNull Set<String> free;

    private final @NotNull Map<PoseExpr, PoseExpr> foldedExpr = new IdentityHashMap<>();

    private final @NotNull Map<PoseExpr, OptionalDouble> exprValue = new IdentityHashMap<>();

    private final @NotNull Map<PosePredicate, PosePredicate> foldedCondition = new IdentityHashMap<>();

    private PoseFold(
        @NotNull Map<String, String> subjectRest, @NotNull Map<String, String> restDefaults,
        @NotNull Map<String, Float> questionDefaults, @NotNull Map<String, Float> inputDefaults,
        @NotNull Set<String> free) {

        this.subjectRest = subjectRest;
        this.restDefaults = restDefaults;
        this.questionDefaults = questionDefaults;
        this.inputDefaults = inputDefaults;
        this.free = free;
    }

    /**
     * Resolves one model's pose against the frame a subject reaching it stands in.
     *
     * <p>The frame arrives as its four tables rather than as one value, because bagging them would
     * be a type whose whole job is to carry four maps one call deep - and the two halves are not
     * interchangeable in any case: a figure consults the subject alone where an enum member consults
     * the subject and then falls back to the model, so a merged map would answer a member the
     * subject is silent on out of the wrong table.
     *
     * @param program the walked pose
     * @param subjectRest which constant each enum member this subject rests holding
     * @param restDefaults the same for the model, read where the subject names no constant
     * @param questionDefaults what a question of a reference the state holds rests answering, keyed
     *     {@code receiver.question}
     * @param inputDefaults what each figure rests at, one keyspace across every model
     * @param free the render-state figures the tick drives, which stay symbolic
     * @return the residual - the same pose with every decided branch taken and every resting leaf
     *     replaced by what it rests at
     */
    static @NotNull PoseProgram fold(
        @NotNull PoseProgram program, @NotNull Map<String, String> subjectRest,
        @NotNull Map<String, String> restDefaults, @NotNull Map<String, Float> questionDefaults,
        @NotNull Map<String, Float> inputDefaults, @NotNull Set<String> free) {

        PoseFold fold =
            new PoseFold(subjectRest, restDefaults, questionDefaults, inputDefaults, free);

        List<Map<PoseChannel, PoseExpr>> container = new ArrayList<>(program.container().size());
        for (Map<PoseChannel, PoseExpr> step : program.container()) container.add(fold.channels(step));

        // In the mesh's own bone order, which is the tied-depth priority a coplanar pair is decided
        // by - a rebuild that re-ordered it would re-decide which face survives.
        Map<String, Map<PoseChannel, PoseExpr>> bones = new LinkedHashMap<>();
        program.bones().forEach((bone, channels) -> bones.put(bone, fold.channels(channels)));

        List<PoseClipSite> clips = new ArrayList<>(program.clipSites().size());
        for (PoseClipSite site : program.clipSites()) {
            List<PoseExpr> arguments = new ArrayList<>(site.arguments().size());
            for (PoseExpr argument : site.arguments()) arguments.add(fold.expression(argument));
            clips.add(new PoseClipSite(site.clip(), site.drive(), List.copyOf(arguments)));
        }

        return new PoseProgram(program.model(), List.copyOf(container),
            Map.copyOf(bones), List.copyOf(clips));
    }

    /**
     * What a subject's resting state answers, as far as one pose can tell.
     *
     * <p>A pose reads that state through two questions - which constant a member holds, and what
     * number it reads as - and asks them only of the members it names. So two subjects stand in the
     * same frame for a row whenever this answers the same for both, however far apart the rest of
     * their resting states are, and folding against either of them lands on one residual. Comparing
     * the raw maps instead over-counts by a wide margin: eleven of the thirteen humanoids name no
     * constant at all, and the two that do name one their model's pose never reads.
     *
     * <p>A member the subject is silent on answers what its model rests at, which is the fallback the
     * constant question already takes. The numeric question does not take that fallback - it honours
     * the subject's own two boolean spellings and otherwise reads the input table - and the two agree
     * here because a model's resting answer is a constant of the member's own declared enum type,
     * which {@code true} and {@code false} are not.
     *
     * @param program the walked pose, read for the members it names
     * @param subjectRest which constant each enum member this subject rests holding
     * @param restDefaults the same for the model, read where the subject names no constant
     * @return each named member's resting answer, sorted, omitting the members nothing answers
     */
    static @NotNull Map<String, String> frameOf(
        @NotNull PoseProgram program, @NotNull Map<String, String> subjectRest,
        @NotNull Map<String, String> restDefaults) {

        Map<String, String> out = new TreeMap<>();
        for (String member : InputDefaultResolver.membersNamedBy(program)) {
            String held = subjectRest.get(member);
            String answer = held == null ? restDefaults.get(member) : held;
            if (answer != null) out.put(member, answer);
        }
        return out;
    }

    /** One channel map with every expression in it folded, in the vocabulary's own order. */
    private @NotNull Map<PoseChannel, PoseExpr> channels(@NotNull Map<PoseChannel, PoseExpr> written) {
        Map<PoseChannel, PoseExpr> out = new LinkedHashMap<>();
        written.forEach((channel, expr) -> out.put(channel, expression(expr)));
        return Map.copyOf(out);
    }

    /**
     * One expression with its decided branches taken and its resting leaves replaced.
     *
     * <p>Memoized by identity, which is what keeps the graph a graph.
     */
    private @NotNull PoseExpr expression(@NotNull PoseExpr expr) {
        PoseExpr known = this.foldedExpr.get(expr);
        if (known != null) return known;
        PoseExpr out = rewrite(expr);
        this.foldedExpr.put(expr, out);
        return out;
    }

    private @NotNull PoseExpr rewrite(@NotNull PoseExpr expr) {
        return switch (expr) {
            // A literal is already what it rests at, and a bone read is never folded at all.
            case PoseExpr.Const literal -> literal;
            case PoseExpr.BoneRead read -> read;
            case PoseExpr.Input input -> this.free.contains(input.field())
                ? input : PoseExpr.Const.of(inputAtRest(input.field()));
            case PoseExpr.Carried ignored -> PoseExpr.Const.of(0f);
            case PoseExpr.InputElement ignored -> PoseExpr.Const.of(0f);
            case PoseExpr.InputFn question ->
                PoseExpr.Const.of(questionAtRest(question.receiver(), question.question()));
            // Collapsed where every operand is a literal, through the SAME builder the walk itself
            // folds with - so an operation resolved here answers the bits it would have answered had
            // the walk been able to resolve it, rather than the bits some algebraically equal
            // shortcut lands on. Each operator narrows at its own width on the way through, which is
            // the whole of why this is done operand by operand and not by evaluating the chain in
            // double and narrowing once at the end.
            case PoseExpr.Op operation -> {
                List<PoseExpr> operands = new ArrayList<>(operation.operands().size());
                for (PoseExpr operand : operation.operands()) operands.add(expression(operand));
                yield PoseExpr.Op.of(operation.operator(), List.copyOf(operands));
            }
            case PoseExpr.Select select -> {
                PosePredicate condition = condition(select.condition());
                if (condition instanceof PosePredicate.Constant decided)
                    yield expression(decided.value() ? select.whenTrue() : select.whenFalse());
                yield new PoseExpr.Select(condition,
                    expression(select.whenTrue()), expression(select.whenFalse()));
            }
        };
    }

    /** One condition, decided where the frame answers it and rebuilt where it does not. */
    private @NotNull PosePredicate condition(@NotNull PosePredicate predicate) {
        PosePredicate known = this.foldedCondition.get(predicate);
        if (known != null) return known;
        PosePredicate out = decide(predicate);
        this.foldedCondition.put(predicate, out);
        return out;
    }

    private @NotNull PosePredicate decide(@NotNull PosePredicate predicate) {
        return switch (predicate) {
            case PosePredicate.Constant decided -> decided;
            // The subject's own constant, then the model's, and a member neither names is in no
            // state any constant matches - which is the runtime's own answer rather than a guess.
            case PosePredicate.EnumEq test -> new PosePredicate.Constant(
                test.constant().equals(constantAtRest(test.field())));
            // A reference nobody supplied is not there.
            case PosePredicate.Has ignored -> new PosePredicate.Constant(false);
            case PosePredicate.Not negated -> condition(negated.operand()).negate();
            case PosePredicate.Compare compare -> {
                OptionalDouble left = value(compare.left());
                OptionalDouble right = value(compare.right());
                if (left.isPresent() && right.isPresent())
                    yield new PosePredicate.Constant(
                        compare.comparison().test(left.getAsDouble(), right.getAsDouble()));
                yield new PosePredicate.Compare(compare.comparison(),
                    expression(compare.left()), expression(compare.right()));
            }
        };
    }

    /**
     * What an expression evaluates to at rest, or nothing where the tick still reaches it.
     *
     * <p>Read for a DECISION alone. The operator is applied through the same method the renderer
     * would apply it through, on the same operands, so a branch decided here is the branch the
     * runtime takes - and no value this produces is written into the residual.
     */
    private @NotNull OptionalDouble value(@NotNull PoseExpr expr) {
        OptionalDouble known = this.exprValue.get(expr);
        if (known != null) return known;
        OptionalDouble out = evaluate(expr);
        this.exprValue.put(expr, out);
        return out;
    }

    private @NotNull OptionalDouble evaluate(@NotNull PoseExpr expr) {
        return switch (expr) {
            case PoseExpr.Const literal -> OptionalDouble.of(literal.value());
            case PoseExpr.BoneRead ignored -> OptionalDouble.empty();
            case PoseExpr.Input input -> this.free.contains(input.field())
                ? OptionalDouble.empty() : OptionalDouble.of(inputAtRest(input.field()));
            case PoseExpr.Carried ignored -> OptionalDouble.of(0f);
            case PoseExpr.InputElement ignored -> OptionalDouble.of(0f);
            case PoseExpr.InputFn question ->
                OptionalDouble.of(questionAtRest(question.receiver(), question.question()));
            case PoseExpr.Op operation -> {
                double[] operands = new double[operation.operands().size()];
                for (int index = 0; index < operands.length; index++) {
                    OptionalDouble operand = value(operation.operands().get(index));
                    if (operand.isEmpty()) yield OptionalDouble.empty();
                    operands[index] = operand.getAsDouble();
                }
                yield OptionalDouble.of(operation.operator().apply(operands));
            }
            case PoseExpr.Select select -> {
                PosePredicate condition = condition(select.condition());
                if (condition instanceof PosePredicate.Constant decided)
                    yield value(decided.value() ? select.whenTrue() : select.whenFalse());
                yield OptionalDouble.empty();
            }
        };
    }

    /**
     * What a figure reads as before anything happens to the subject.
     *
     * <p>The subject's own map answers first and only in the two boolean spellings, because one
     * keyspace carries both flags and enum constant names - a constant sitting under a name a pose
     * inputs falls through to the table rather than answering a confident zero.
     */
    private float inputAtRest(@NotNull String field) {
        String held = this.subjectRest.get(field);
        if (TRUE.equals(held)) return 1f;
        if (FALSE.equals(held)) return 0f;
        return this.inputDefaults.getOrDefault(field, 0f);
    }

    /** Which constant an enum member rests holding - the subject's own, then the model's. */
    private String constantAtRest(@NotNull String member) {
        String held = this.subjectRest.get(member);
        return held == null ? this.restDefaults.get(member) : held;
    }

    /** What a question rests answering, an emptiness nobody supplied being empty rather than absent. */
    private float questionAtRest(@NotNull String receiver, @NotNull String question) {
        Float held = this.questionDefaults.get(receiver + '.' + question);
        if (held != null) return held;
        return IS_EMPTY.equals(question) ? 1f : 0f;
    }

}
