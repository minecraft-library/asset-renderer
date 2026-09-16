package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Writes one model's pose into the shipped table.
 *
 * <p><b>An operation is spelled by its own token.</b> A node carries one member, named for what it
 * does, holding what it does it to - so an expression reads as the arithmetic it is rather than as a
 * tagged union, and a diff of the table names the operation that moved. That costs a reader one
 * dispatch on which member is present, against a closed set the operator roster already fixes.
 *
 * <p><b>Width rides the token for a literal too.</b> A single-precision {@code 0.4} and a
 * double-precision one are the same digits and different values, and the arithmetic around them
 * already distinguishes {@code add} from {@code dadd}; a literal that left its width to be inferred
 * from whether Gson wrote a decimal point would be the one place the rule lapses.
 *
 * <p><b>A sub-expression written more than once is written once and named.</b> A pose is a graph
 * rather than a tree: a walk that follows both arms of everything it cannot decide reaches the same
 * arithmetic down many paths, and the arms it merges hold the SAME sub-expression rather than equal
 * copies of one. Spelling that out path by path is not merely large, it is exponentially large - a
 * humanoid's arms come to twenty-two million nodes standing for nine hundred distinct ones, and the
 * one before this could not be written at all. So every sub-expression reached from more than one
 * place goes in {@code shared} and is used as {@code {"ref": n}}.
 *
 * <p>Leaves stay inline however often they are reached. A literal or an input costs about what a
 * reference to one costs, and naming them would put the whole of a small model's arithmetic behind
 * indirection for nothing - the point of the table is that a pose can be read.
 *
 * <p>Entries are ordered so that everything a node names is already declared above it, which is what
 * lets a reader resolve one in a single pass and makes a cycle unwritable rather than merely absent.
 *
 * <p>Bones are written in name order and channels in the order the vocabulary declares them, so the
 * bytes are a function of the pose rather than of the order the walk happened to build it in.
 */
@UtilityClass
public final class PoseJson {

    /** What a model that could not be walked carries instead of a pose. */
    private static final @NotNull String REFUSED = "refused";

    /** The member holding the sub-expressions this model reaches from more than one place. */
    private static final @NotNull String SHARED = "shared";

    /** The member one of those is used through. */
    private static final @NotNull String REF = "ref";

    /** The member holding what this model does to the container its mesh flattened away. */
    private static final @NotNull String CONTAINER = "container";

    /** The member holding the resting silhouette of each state branch the model poses. */
    private static final @NotNull String STATES = "states";

    /**
     * Writes one model's outcome, carrying no state silhouettes.
     *
     * @param outcome the pose, or why there is not one
     * @return the node to file under the model's name
     */
    public static @NotNull JsonTree of(@NotNull PoseOutcome outcome) {
        return of(outcome, Map.of());
    }

    /**
     * Writes one model's outcome and the silhouette of each state branch it poses.
     *
     * <p>A silhouette is spelled exactly as a row's bones are - the same vocabulary order, and a
     * {@code shared} table of its own where it reaches a sub-expression twice - under the key of
     * the answer that reaches it. It sits after everything the runtime reads, and a row placing
     * no state spells no member, so a table carrying none is byte-for-byte the table before it.
     *
     * @param outcome the pose, or why there is not one
     * @param states each state's silhouette keyed by the answer that reaches it, in key order
     * @return the node to file under the model's name
     */
    public static @NotNull JsonTree of(
        @NotNull PoseOutcome outcome, @NotNull Map<String, PoseStates.Silhouette> states) {

        if (outcome instanceof PoseOutcome.Refused refused)
            return JsonTree.object().put(REFUSED, refused.reason());

        PoseProgram program = ((PoseOutcome.Extracted) outcome).program();
        Map<String, Map<PoseSink, PoseExpr>> bones = new TreeMap<>(program.bones());
        // A play site ships its clip, its drive and its arguments and NEVER its condition, the fold
        // being the only thing that reads one - it drops a site it proves unreachable and settles the
        // rest to ALWAYS. So a site still carrying a guard here would ship as unconditional, and a
        // model would play every clip it can reach at once rather than the one it is gated to.
        for (PoseClipSite site : program.clipSites())
            if (!PoseClipSite.ALWAYS.equals(site.condition())) throw new ToolingException(
                "'%s' plays '%s' behind a condition nothing settled, and a play site ships no condition",
                program.model(), site.clip());

        Shared shared = Shared.of(program.container(), bones, program.clipSites());
        shared.refuseUnsettled(program.model());

        JsonTree node = JsonTree.object();
        if (!shared.table().isEmpty()) {
            JsonTree declared = node.childArray(SHARED);
            for (JsonTree entry : shared.table()) declared.add(entry);
        }

        // Above the bones, which is where it sits: the container is the parent transform every bone
        // the mesh names at top level hangs off, and the mesh names it nowhere. An ARRAY, because it
        // is a sequence: each element is a part pose, written outermost first, and within one the
        // channel order is the vocabulary's own the way a bone's is.
        container(node, program.container(), shared);

        JsonTree written = node.child("bones");
        // Sorted, and every channel of a bone written in the vocabulary's own order: a pose holds
        // one expression per channel and says nothing by the order it holds them in, so the only
        // thing an order can do here is make two runs disagree. A flag channel is not written at
        // all: every one folds to a literal at generation and the model table's undrawn lists are
        // the read copy, so nothing at render reads one.
        bones.forEach((bone, channels) -> {
            JsonTree posed = JsonTree.object();
            for (PoseSink channel : PoseSink.values())
                if (!channel.isFlag() && channels.containsKey(channel))
                    posed.put(channel.token(), shared.use(channels.get(channel)));
            written.put(bone, posed);
        });

        if (!program.clipSites().isEmpty()) {
            JsonTree plays = node.childArray("clips");
            for (PoseClipSite site : program.clipSites()) plays.add(clipSite(site, shared));
        }

        if (!states.isEmpty()) {
            JsonTree placed = node.child(STATES);
            states.forEach((key, silhouette) -> placed.put(key,
                of(new PoseOutcome.Extracted(PoseStates.asProgram(program.model(), silhouette)))));
        }
        return node;
    }

    /** The step sequence, each step's channels in the vocabulary's own order, or nothing for none. */
    private static void container(
        @NotNull JsonTree node, @NotNull List<Map<PoseSink, PoseExpr>> steps, @NotNull Shared shared) {

        if (steps.isEmpty()) return;
        JsonTree written = node.childArray(CONTAINER);
        for (Map<PoseSink, PoseExpr> step : steps) {
            JsonTree held = JsonTree.object();
            for (PoseSink channel : PoseSink.values())
                if (step.containsKey(channel)) held.put(channel.token(), shared.use(step.get(channel)));
            written.add(held);
        }
    }

    /**
     * One place the model plays an authored clip, with the timing and amplitude it plays it at.
     *
     * <p>The arguments are the whole reason a play site is written down beside the clip table, which
     * already says which clip and under what drive: how fast the thing moves and how far are the
     * model's own and live nowhere in the clip.
     *
     * <p>A select-driven site carries the render-state {@code field} its gate reads, which is what
     * says WHICH of a model's several clips a caller is choosing between. It is written only where
     * there is one, so the other two drives spell no empty member.
     */
    private static @NotNull JsonTree clipSite(@NotNull PoseClipSite site, @NotNull Shared shared) {
        JsonTree node = JsonTree.object()
            .put("clip", site.clip())
            .put("drive", site.drive().token());
        if (!site.state().isEmpty()) node.put("field", site.state());
        if (site.arguments().isEmpty()) return node;

        JsonTree arguments = node.childArray("args");
        for (PoseExpr argument : site.arguments()) arguments.add(shared.use(argument));
        return node;
    }

    /**
     * The sub-expressions one model reaches from more than one place, declared once and numbered.
     *
     * <p><b>Every node is interned to a number first, and the graph is read in those numbers
     * afterwards.</b> The nodes are records, so asking a map about one hashes it by walking
     * everything below it - which is the very tree this exists to avoid writing, and the pass that
     * was meant to find the sharing would be the pass that could not afford to. Interning bottom-up
     * against a key built from the numbers already given to a node's children costs one small string
     * per node instead, and two nodes get one number exactly when they are equal.
     *
     * <p>A number is always higher than the numbers it is built from, so ascending order is deepest
     * first - which is the order the table is declared in and the order a reader can resolve it in.
     */
    private static final class Shared {

        /** What a node's own leaf value and operator are read from, by number. */
        private final @NotNull List<Object> canonical = new ArrayList<>();

        /** What each node is built from, by number. */
        private final @NotNull List<List<Integer>> operands = new ArrayList<>();

        /** How many places each node is reached from, roots included. */
        private final @NotNull List<Integer> uses = new ArrayList<>();

        /** The number a node's shape already has, so two equal nodes never get two. */
        private final @NotNull Map<String, Integer> byShape = new LinkedHashMap<>();

        /** The number each node object was given, asked by identity because that is free. */
        private final @NotNull Map<Object, Integer> byNode = new IdentityHashMap<>();

        /** Where each declared node sits in the table. */
        private final @NotNull Map<Integer, Integer> declared = new LinkedHashMap<>();

        private final @NotNull List<JsonTree> table = new ArrayList<>();

        static @NotNull Shared of(
            @NotNull List<Map<PoseSink, PoseExpr>> container,
            @NotNull Map<String, Map<PoseSink, PoseExpr>> bones, @NotNull List<PoseClipSite> sites) {

            Shared shared = new Shared();
            forEachRoot(container, bones, sites, root -> shared.reached(shared.intern(root)));
            for (List<Integer> below : List.copyOf(shared.operands))
                for (int operand : below) shared.reached(operand);
            // Ascending order is deepest first, so everything a node names is in the table above it.
            // A leaf is never declared however often it is reached: a reference to a literal costs
            // what the literal costs and reads worse.
            for (int node = 0; node < shared.canonical.size(); node++) {
                if (shared.operands.get(node).isEmpty() || shared.uses.get(node) <= 1) continue;
                shared.declared.put(node, shared.table.size());
                shared.table.add(shared.body(node));
            }
            return shared;
        }

        /**
         * Every expression the model writes, in the order the file writes them.
         *
         * <p>One order serves the interning pass and the writing pass, which is what makes a
         * number a function of the pose rather than of the traversal that found it.
         */
        private static void forEachRoot(
            @NotNull List<Map<PoseSink, PoseExpr>> container,
            @NotNull Map<String, Map<PoseSink, PoseExpr>> bones, @NotNull List<PoseClipSite> sites,
            @NotNull Consumer<PoseExpr> root) {

            for (Map<PoseSink, PoseExpr> step : container)
                for (PoseSink channel : PoseSink.values())
                    if (step.containsKey(channel)) root.accept(step.get(channel));
            // A flag channel's expression is never written, so it is never a root: an entry only
            // flag channels reach would otherwise be declared under `shared` for nothing to name.
            bones.forEach((bone, channels) -> {
                for (PoseSink channel : PoseSink.values())
                    if (!channel.isFlag() && channels.containsKey(channel)) root.accept(channels.get(channel));
            });
            for (PoseClipSite site : sites)
                for (PoseExpr argument : site.arguments()) root.accept(argument);
        }

        @NotNull List<JsonTree> table() {
            return this.table;
        }

        /**
         * Refuses a node the fold settles that reached the writer unsettled.
         *
         * <p>Seven node kinds have a token this writer can spell and the renderer's reader refuses -
         * the three figures a resting subject answers, and the four conditions a frame decides. The
         * fold erases every one of them, so one arriving here means a program reached the writer
         * without being folded against a frame, and the table it would write stops the pipeline at
         * load for every entity rather than for the row that caused it.
         *
         * <p>Asked over the interned nodes rather than over the graph. {@code byNode} already holds
         * every node the program reaches, keyed by identity and deduplicated by the pass that filled
         * it, so this asks each distinct node once - where walking the graph would ask a humanoid's
         * arms twenty-two million times.
         *
         * @param model the row being written
         * @throws ToolingException if a node the fold settles reached the writer
         */
        void refuseUnsettled(@NotNull String model) {
            for (Object node : this.byNode.keySet())
                unsettled(node).ifPresent(token -> {
                    throw new ToolingException(
                        "'%s' writes '%s', which the fold settles and the renderer refuses at load",
                        model, token);
                });
        }

        /**
         * The token a node would be written with, where the renderer has no case for it.
         *
         * @param node the node
         * @return the refused token, or empty for a node the reader reads
         */
        private static @NotNull Optional<String> unsettled(@NotNull Object node) {
            return Optional.ofNullable(switch (node) {
                case PoseExpr.Carried ignored -> "carried";
                case PoseExpr.InputFn ignored -> "input_fn";
                case PoseExpr.InputElement ignored -> "input_element";
                case PosePredicate.Constant ignored -> "always";
                case PosePredicate.EnumEq ignored -> "is";
                case PosePredicate.Has ignored -> "has";
                case PosePredicate.Not ignored -> "not";
                default -> null;
            });
        }

        /** Records one more place a node is reached from. */
        private void reached(int node) {
            this.uses.set(node, this.uses.get(node) + 1);
        }

        /** One place an expression is used - a reference when it is declared, and itself when not. */
        @NotNull JsonTree use(@NotNull PoseExpr expr) {
            return written(this.byNode.get(expr));
        }

        /** One place a condition is used, which is the same question a step lower. */
        @NotNull JsonTree use(@NotNull PosePredicate condition) {
            return written(this.byNode.get(condition));
        }

        private @NotNull JsonTree written(int node) {
            Integer at = this.declared.get(node);
            return at != null ? JsonTree.object().putInt(REF, at) : body(node);
        }

        /** A node spelled out, with everything it names written the same way one step down. */
        private @NotNull JsonTree body(int node) {
            Object held = this.canonical.get(node);
            return held instanceof PoseExpr expr ? expression(expr, this) : predicate((PosePredicate) held, this);
        }

        /** The number this node has, giving it one - and everything below it - if it has none. */
        private int intern(@NotNull Object node) {
            Integer known = this.byNode.get(node);
            if (known != null) return known;

            List<Integer> below = new ArrayList<>();
            for (Object operand : operandsOf(node)) below.add(intern(operand));

            String shape = shapeOf(node, below);
            Integer number = this.byShape.get(shape);
            if (number == null) {
                number = this.canonical.size();
                this.canonical.add(node);
                this.operands.add(List.copyOf(below));
                this.uses.add(0);
                this.byShape.put(shape, number);
            }
            this.byNode.put(node, number);
            return number;
        }

        /** What a node is built from, in the order it is written. */
        private static @NotNull List<Object> operandsOf(@NotNull Object node) {
            return switch (node) {
                case PoseExpr.Op operation -> List.copyOf(operation.operands());
                case PoseExpr.Select select ->
                    List.of(select.condition(), select.whenTrue(), select.whenFalse());
                case PosePredicate.Compare compare -> List.of(compare.left(), compare.right());
                case PosePredicate.Not not -> List.of(not.operand());
                default -> List.of();
            };
        }

        /**
         * What makes two nodes the same node - their kind, whatever they carry themselves, and the
         * numbers of what they are built from.
         *
         * <p>A literal is keyed on its BITS rather than its value, because two literals that compare
         * equal are not always the same one: the corpus carries a negative zero, and a table that
         * folded it into a positive one would move a pose by a sign it cannot see.
         *
         * <p>The separator is a NUL, written as the escape {@code \0} so the source stays text to a
         * tool that reads it. Nothing a key joins can carry one - not a Java identifier, not an enum
         * constant, not a list of numbers - so two nodes differing only in where one field ends and
         * the next begins cannot key alike. A printable separator is a character some field could
         * hold, and the collision that allows renumbers the shared table with nothing failing to
         * compile and no test going red.
         */
        private static @NotNull String shapeOf(@NotNull Object node, @NotNull List<Integer> below) {
            String separated = below.toString();
            return switch (node) {
                case PoseExpr.Const literal ->
                    "const\0" + literal.width() + '\0' + Double.doubleToRawLongBits(literal.value());
                case PoseExpr.Input input -> "input\0" + input.field();
                case PoseExpr.Carried carried -> "carried\0" + carried.field();
                case PoseExpr.InputFn question ->
                    "input_fn\0" + question.receiver() + '\0' + question.question();
                case PoseExpr.InputElement element ->
                    "input_element\0" + element.receiver() + '\0' + element.index();
                case PoseExpr.BoneRead read -> "bone\0" + read.bone() + '\0' + read.channel();
                case PoseExpr.Op operation -> "op\0" + operation.operator() + '\0' + separated;
                case PoseExpr.Select ignored -> "select\0" + separated;
                case PosePredicate.Constant decided -> "always\0" + decided.value();
                case PosePredicate.Compare compare -> "cmp\0" + compare.comparison() + '\0' + separated;
                case PosePredicate.EnumEq test -> "is\0" + test.field() + '\0' + test.constant();
                case PosePredicate.Has present -> "has\0" + present.member();
                case PosePredicate.Not ignored -> "not\0" + separated;
                default -> throw new IllegalStateException("a pose node this writer does not know: " + node);
            };
        }

    }

    /**
     * One expression.
     *
     * @param expr what to write
     * @return the node
     */
    private static @NotNull JsonTree expression(@NotNull PoseExpr expr, @NotNull Shared shared) {
        return switch (expr) {
            case PoseExpr.Const literal -> literal(literal);
            case PoseExpr.Input input -> JsonTree.object().put("input", input.field());
            case PoseExpr.Carried carried -> JsonTree.object().put("carried", carried.field());
            case PoseExpr.InputFn question -> JsonTree.object()
                .put("input_fn", JsonTree.arrayOf(question.receiver(), question.question()));
            case PoseExpr.InputElement element -> JsonTree.object().put("input_element",
                JsonTree.array().add(JsonTree.of(element.receiver())).add(JsonTree.of(element.index())));
            case PoseExpr.BoneRead read -> JsonTree.object()
                .put("bone", JsonTree.arrayOf(read.bone(), read.channel().token()));
            case PoseExpr.Op operation -> JsonTree.object()
                .put(operation.operator().token(), operands(operation.operands(), shared));
            case PoseExpr.Select select -> JsonTree.object().put("select", JsonTree.array()
                .add(shared.use(select.condition()))
                .add(shared.use(select.whenTrue()))
                .add(shared.use(select.whenFalse())));
        };
    }

    /** A literal, at the width it was pushed rather than at the width its digits suggest. */
    private static @NotNull JsonTree literal(@NotNull PoseExpr.Const held) {
        return switch (held.width()) {
            case FLOAT -> JsonTree.object().put("const", (float) held.value());
            case DOUBLE -> JsonTree.object().putDouble("dconst", held.value());
            case INT -> JsonTree.object().putInt("iconst", (int) held.value());
        };
    }

    /** One condition. */
    private static @NotNull JsonTree predicate(@NotNull PosePredicate predicate, @NotNull Shared shared) {
        return switch (predicate) {
            case PosePredicate.Constant decided -> JsonTree.object().put("always", decided.value());
            case PosePredicate.Compare compare -> JsonTree.object().put(compare.comparison().token(),
                JsonTree.array().add(shared.use(compare.left())).add(shared.use(compare.right())));
            case PosePredicate.EnumEq test -> JsonTree.object()
                .put("is", JsonTree.arrayOf(test.field(), test.constant()));
            case PosePredicate.Has present -> JsonTree.object().put("has", present.member());
            case PosePredicate.Not not -> JsonTree.object().put("not", shared.use(not.operand()));
        };
    }

    private static @NotNull JsonTree operands(@NotNull List<PoseExpr> operands, @NotNull Shared shared) {
        List<JsonTree> written = operands.stream().map(shared::use).collect(Collectors.toList());
        return JsonTree.array().addAll(written);
    }

    /**
     * Every model's outcome, keyed the way the rest of the table keys a model, each carrying the
     * state silhouettes derived for its row.
     */
    static @NotNull Map<String, JsonTree> all(
        @NotNull Map<String, PoseOutcome> outcomes,
        @NotNull Map<String, Map<String, PoseStates.Silhouette>> states) {

        return outcomes.entrySet()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                entry -> of(entry.getValue(), states.getOrDefault(entry.getKey(), Map.of())),
                (a, b) -> b, TreeMap::new));
    }

}
