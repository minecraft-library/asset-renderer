package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
 * <p>Bones are written in name order and channels in the order the vocabulary declares them, so the
 * bytes are a function of the pose rather than of the order the walk happened to build it in.
 */
@UtilityClass
public final class PoseJson {

    /** What a model that could not be walked carries instead of a pose. */
    private static final @NotNull String REFUSED = "refused";

    /**
     * Writes one model's outcome.
     *
     * @param outcome the pose, or why there is not one
     * @return the node to file under the model's name
     */
    public static @NotNull JsonTree of(@NotNull PoseOutcome outcome) {
        if (outcome instanceof PoseOutcome.Refused refused)
            return JsonTree.object().put(REFUSED, refused.reason());

        PoseProgram program = ((PoseOutcome.Extracted) outcome).program();
        JsonTree node = JsonTree.object();
        JsonTree bones = node.child("bones");
        // Sorted, and every channel of a bone written in the vocabulary's own order: a pose holds
        // one expression per channel and says nothing by the order it holds them in, so the only
        // thing an order can do here is make two runs disagree.
        new TreeMap<>(program.bones()).forEach((bone, channels) -> {
            JsonTree written = JsonTree.object();
            for (PoseChannel channel : PoseChannel.values())
                if (channels.containsKey(channel)) written.put(channel.token(), expression(channels.get(channel)));
            bones.put(bone, written);
        });

        if (!program.clipSites().isEmpty()) {
            JsonTree plays = node.childArray("clips");
            for (PoseClipSite site : program.clipSites()) plays.add(clipSite(site));
        }
        return node;
    }

    /**
     * One place the model plays an authored clip, with the timing and amplitude it plays it at.
     *
     * <p>The arguments are the whole reason a play site is written down beside the clip table, which
     * already says which clip and under what drive: how fast the thing moves and how far are the
     * model's own and live nowhere in the clip.
     */
    private static @NotNull JsonTree clipSite(@NotNull PoseClipSite site) {
        JsonTree node = JsonTree.object()
            .put("clip", site.clip())
            .put("gate", site.drive().token());
        if (site.arguments().isEmpty()) return node;

        JsonTree arguments = node.childArray("args");
        for (PoseExpr argument : site.arguments()) arguments.add(expression(argument));
        return node;
    }

    /**
     * One expression.
     *
     * @param expr what to write
     * @return the node
     */
    private static @NotNull JsonTree expression(@NotNull PoseExpr expr) {
        return switch (expr) {
            case PoseExpr.Const literal -> literal(literal);
            case PoseExpr.Input input -> JsonTree.object().put("input", input.field());
            case PoseExpr.InputFn question -> JsonTree.object()
                .put("input_fn", JsonTree.arrayOf(question.receiver(), question.question()));
            case PoseExpr.InputElement element -> JsonTree.object().put("input_element",
                JsonTree.array().add(JsonTree.of(element.receiver())).add(JsonTree.of(element.index())));
            case PoseExpr.BoneRead read -> JsonTree.object()
                .put("bone", JsonTree.arrayOf(read.bone(), read.channel().token()));
            case PoseExpr.Op operation -> JsonTree.object()
                .put(operation.operator().token(), operands(operation.operands()));
            case PoseExpr.Select select -> JsonTree.object().put("select", JsonTree.array()
                .add(predicate(select.condition()))
                .add(expression(select.whenTrue()))
                .add(expression(select.whenFalse())));
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
    private static @NotNull JsonTree predicate(@NotNull PosePredicate predicate) {
        return switch (predicate) {
            case PosePredicate.Constant decided -> JsonTree.object().put("always", decided.value());
            case PosePredicate.Compare compare -> JsonTree.object().put(compare.comparison().token(),
                JsonTree.array().add(expression(compare.left())).add(expression(compare.right())));
            case PosePredicate.EnumEq test -> JsonTree.object()
                .put("is", JsonTree.arrayOf(test.field(), test.constant()));
            case PosePredicate.Not not -> JsonTree.object().put("not", predicate(not.operand()));
        };
    }

    private static @NotNull JsonTree operands(@NotNull List<PoseExpr> operands) {
        List<JsonTree> written = new ArrayList<>(operands.size());
        for (PoseExpr operand : operands) written.add(expression(operand));
        return JsonTree.array().addAll(written);
    }

    /** Every model's outcome, keyed the way the rest of the table keys a model. */
    static @NotNull Map<String, JsonTree> all(@NotNull Map<String, PoseOutcome> outcomes) {
        Map<String, JsonTree> out = new TreeMap<>();
        outcomes.forEach((model, outcome) -> out.put(model, of(outcome)));
        return out;
    }

}
