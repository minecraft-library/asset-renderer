package lib.minecraft.renderer.pipeline.index;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import lib.minecraft.renderer.asset.pose.PosePredicate;
import lib.minecraft.renderer.exception.PipelineException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The raw form of {@code entity_poses.json}'s {@code poses} member - one {@link EntityPose} per
 * model class simple name, which is what a geometry coordinate is headed with.
 *
 * <p>The file's other two members are not declared and Gson drops them: {@code clips} is the
 * authored keyframe tables, which are read by whatever plays one rather than by whatever selects a
 * pose, and {@code models} restates which clips a model plays without the rate and amplitude it
 * plays them at - the same facts {@code poses} carries in full.
 *
 * <p><b>Walked by hand rather than mapped.</b> An expression node is an object with one member
 * named for what it does, which no field-mapped record shape can describe; and a Gson of this
 * vintage resolves an adapter declared on a nested generic inconsistently, so a single adapter over
 * the whole subtree is both the only shape that fits and the only one that does not depend on that.
 *
 * <p><b>A model's {@code shared} table is read as the graph it is, never expanded.</b> Every
 * {@code {"ref": n}} resolves to the SAME record instance rather than to a fresh copy, which is what
 * keeps a pose the size the file says it is: a humanoid's arms name nine hundred sub-expressions and
 * stand for twenty-two million, and a reader that rebuilt one per reference would be reading the
 * number the table exists not to write.
 *
 * @param poses the pose of each model class, by simple name
 */
@JsonAdapter(RawEntityPosesFile.Adapter.class)
public record RawEntityPosesFile(@NotNull Map<String, EntityPose> poses) {

    /** Reads the {@code poses} subtree, ignoring everything the file carries beside it. */
    static final class Adapter implements JsonDeserializer<RawEntityPosesFile> {

        @Override
        public @NotNull RawEntityPosesFile deserialize(
            @NotNull JsonElement root, @NotNull Type type, @NotNull JsonDeserializationContext context) {

            if (!root.isJsonObject()) throw new PipelineException("entity poses: the file is not an object");
            JsonElement poses = root.getAsJsonObject().get("poses");
            if (poses == null) return new RawEntityPosesFile(Map.of());
            if (!poses.isJsonObject()) throw new PipelineException("entity poses: 'poses' is not an object");

            Map<String, EntityPose> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : poses.getAsJsonObject().entrySet())
                out.put(entry.getKey(), pose(entry.getKey(), object(entry.getValue(), entry.getKey())));
            return new RawEntityPosesFile(Collections.unmodifiableMap(out));
        }

    }

    /** One model's pose, or the record of why it has none. */
    private static @NotNull EntityPose pose(@NotNull String model, @NotNull JsonObject node) {
        JsonElement refused = node.get("refused");
        if (refused != null)
            return new EntityPose(Map.of(), Map.of(), List.of(), Optional.of(refused.getAsString()));

        Shared shared = Shared.of(model, node.get("shared"));

        // The container the mesh flattened away, which is a parent transform above the bones rather
        // than one of them, so it is read into its own map and never into the bone map.
        JsonElement held = node.get("container");
        Map<PoseChannel, PoseExpr> container = held == null
            ? Map.of() : channels(model, "container", object(held, model), shared);

        Map<String, Map<PoseChannel, PoseExpr>> bones = new LinkedHashMap<>();
        JsonElement written = node.get("bones");
        if (written != null)
            for (Map.Entry<String, JsonElement> bone : object(written, model).entrySet())
                bones.put(bone.getKey(), channels(model, bone.getKey(), object(bone.getValue(), model), shared));

        List<EntityPose.Clip> clips = new ArrayList<>();
        JsonElement played = node.get("clips");
        if (played != null)
            for (JsonElement clip : array(played, model)) clips.add(clip(model, object(clip, model), shared));

        shared.requireAllRead(model);
        // Order-preserving rather than Map.copyOf, whose iteration order is salted per JVM launch.
        // Nothing reads the order for meaning, but the parity dump digests this map, and a digest
        // over a map that flaps is a row that fails its own reproducibility check and nothing else.
        return new EntityPose(container, Collections.unmodifiableMap(bones),
            List.copyOf(clips), Optional.empty());
    }

    /**
     * One model's shared sub-expressions, each built once and handed out by reference.
     *
     * <p>Built on first use rather than in a pass of its own, so a node is read as whichever kind
     * the place that names it wants - and a table declaring both a condition and an expression needs
     * no second vocabulary saying which of the two each entry is.
     *
     * <p>An entry the pose never names is a table carrying what nothing reads, which is a generator
     * that wrote more than it meant to rather than a file to accept quietly; one that names itself is
     * a cycle no emitted table can hold, and both are refused.
     */
    private static final class Shared {

        private final @NotNull JsonArray declared;
        private final @NotNull Map<Integer, PoseExpr> expressions = new LinkedHashMap<>();
        private final @NotNull Map<Integer, PosePredicate> conditions = new LinkedHashMap<>();
        private final @NotNull Set<Integer> resolving = new LinkedHashSet<>();

        private Shared(@NotNull JsonArray declared) {
            this.declared = declared;
        }

        static @NotNull Shared of(@NotNull String model, @Nullable JsonElement node) {
            return new Shared(node == null ? new JsonArray() : array(node, model));
        }

        /** The expression an entry stands for, built once however many places name it. */
        @NotNull PoseExpr expression(@NotNull String model, int at) {
            PoseExpr held = this.expressions.get(at);
            if (held != null) return held;
            PoseExpr built = RawEntityPosesFile.expression(model, entry(model, at), this);
            this.resolving.remove(at);
            this.expressions.put(at, built);
            return built;
        }

        /** The condition an entry stands for, built once however many places name it. */
        @NotNull PosePredicate condition(@NotNull String model, int at) {
            PosePredicate held = this.conditions.get(at);
            if (held != null) return held;
            PosePredicate built = RawEntityPosesFile.predicate(model, entry(model, at), this);
            this.resolving.remove(at);
            this.conditions.put(at, built);
            return built;
        }

        private @NotNull JsonElement entry(@NotNull String model, int at) {
            if (at < 0 || at >= this.declared.size())
                throw new PipelineException("entity poses: %s names shared %d of %d",
                    model, at, this.declared.size());
            if (!this.resolving.add(at))
                throw new PipelineException("entity poses: %s names shared %d from inside itself", model, at);
            return this.declared.get(at);
        }

        private void requireAllRead(@NotNull String model) {
            for (int at = 0; at < this.declared.size(); at++)
                if (!this.expressions.containsKey(at) && !this.conditions.containsKey(at))
                    throw new PipelineException("entity poses: %s declares shared %d, which nothing names", model, at);
        }

    }

    /** One bone's channels, held in the vocabulary's own order however the file listed them. */
    private static @NotNull Map<PoseChannel, PoseExpr> channels(
        @NotNull String model, @NotNull String bone, @NotNull JsonObject node, @NotNull Shared shared) {

        Map<PoseChannel, PoseExpr> out = new EnumMap<>(PoseChannel.class);
        for (Map.Entry<String, JsonElement> entry : node.entrySet()) {
            PoseChannel channel = PoseChannel.ofToken(entry.getKey());
            if (channel == null)
                throw new PipelineException("entity poses: %s.%s writes '%s', which is not a channel",
                    model, bone, entry.getKey());
            out.put(channel, expression(model, entry.getValue(), shared));
        }
        return out;
    }

    /** One play site: which clip, under what drive, at what the model plays it. */
    private static @NotNull EntityPose.Clip clip(
        @NotNull String model, @NotNull JsonObject node, @NotNull Shared shared) {

        JsonElement coordinate = node.get("clip");
        JsonElement gate = node.get("gate");
        if (coordinate == null || gate == null)
            throw new PipelineException("entity poses: %s plays a clip that names no coordinate or no drive", model);

        EntityPose.Gate drive = EntityPose.Gate.ofToken(gate.getAsString())
            .orElseThrow(() -> new PipelineException("entity poses: %s drives a clip by '%s', which is not a drive",
                model, gate.getAsString()));

        List<PoseExpr> arguments = new ArrayList<>();
        JsonElement args = node.get("args");
        if (args != null)
            for (JsonElement argument : array(args, model)) arguments.add(expression(model, argument, shared));
        return new EntityPose.Clip(coordinate.getAsString(), drive, List.copyOf(arguments));
    }

    /**
     * One expression, dispatched on the single member naming what it does.
     *
     * <p>The operator roster is consulted before the leaf names, so a table written by a newer
     * generator fails on the operation it names rather than on the shape of the node carrying it.
     */
    private static @NotNull PoseExpr expression(
        @NotNull String model, @NotNull JsonElement node, @NotNull Shared shared) {

        JsonObject held = object(node, model);
        Map.Entry<String, JsonElement> only = single(held, model, "an expression");
        String token = only.getKey();
        JsonElement body = only.getValue();

        PoseOperator operator = PoseOperator.ofToken(token);
        if (operator != null) {
            List<PoseExpr> operands = new ArrayList<>();
            for (JsonElement operand : array(body, model)) operands.add(expression(model, operand, shared));
            if (operands.size() != operator.arity())
                throw new PipelineException("entity poses: %s applies '%s' to %d operand(s), which takes %d",
                    model, token, operands.size(), operator.arity());
            return new PoseExpr.Op(operator, List.copyOf(operands));
        }

        return switch (token) {
            case "ref" -> shared.expression(model, body.getAsInt());
            // Narrowed through float on the way in, so the carrier holds the double a float value
            // widens to exactly. JSON writes both widths as digits, and reading "-0.2" as a double
            // gives a value no float ever had - close enough to look right and different from what
            // the generator folded with.
            case "const" -> new PoseExpr.Const((float) body.getAsDouble(), PoseOperator.Width.FLOAT);
            case "dconst" -> new PoseExpr.Const(body.getAsDouble(), PoseOperator.Width.DOUBLE);
            case "iconst" -> new PoseExpr.Const(body.getAsInt(), PoseOperator.Width.INT);
            case "input" -> new PoseExpr.Input(body.getAsString());
            case "carried" -> new PoseExpr.Carried(body.getAsString());
            case "input_fn" -> {
                JsonArray asked = array(body, model);
                yield new PoseExpr.InputFn(asked.get(0).getAsString(), asked.get(1).getAsString());
            }
            case "input_element" -> {
                JsonArray indexed = array(body, model);
                yield new PoseExpr.InputElement(indexed.get(0).getAsString(), indexed.get(1).getAsInt());
            }
            case "bone" -> {
                JsonArray read = array(body, model);
                String bone = read.get(0).getAsString();
                String channel = read.get(1).getAsString();
                PoseChannel of = PoseChannel.ofToken(channel);
                if (of == null)
                    throw new PipelineException("entity poses: %s reads '%s' of %s, which is not a channel",
                        model, channel, bone);
                yield new PoseExpr.BoneRead(bone, of);
            }
            case "select" -> {
                JsonArray arms = array(body, model);
                yield new PoseExpr.Select(predicate(model, arms.get(0), shared),
                    expression(model, arms.get(1), shared), expression(model, arms.get(2), shared));
            }
            default -> throw new PipelineException(
                "entity poses: %s names '%s', which this renderer does not know how to read", model, token);
        };
    }

    /** One condition, dispatched the same way an expression is. */
    private static @NotNull PosePredicate predicate(
        @NotNull String model, @NotNull JsonElement node, @NotNull Shared shared) {

        JsonObject held = object(node, model);
        Map.Entry<String, JsonElement> only = single(held, model, "a condition");
        String token = only.getKey();
        JsonElement body = only.getValue();

        PosePredicate.Comparison comparison = PosePredicate.Comparison.ofToken(token);
        if (comparison != null) {
            JsonArray operands = array(body, model);
            return new PosePredicate.Compare(comparison,
                expression(model, operands.get(0), shared), expression(model, operands.get(1), shared));
        }

        return switch (token) {
            case "ref" -> shared.condition(model, body.getAsInt());
            case "always" -> new PosePredicate.Constant(body.getAsBoolean());
            case "is" -> {
                JsonArray test = array(body, model);
                yield new PosePredicate.Is(test.get(0).getAsString(), test.get(1).getAsString());
            }
            case "has" -> new PosePredicate.Has(body.getAsString());
            case "not" -> new PosePredicate.Not(predicate(model, body, shared));
            default -> throw new PipelineException(
                "entity poses: %s turns on '%s', which this renderer does not know how to read", model, token);
        };
    }

    private static @NotNull Map.Entry<String, JsonElement> single(
        @NotNull JsonObject node, @NotNull String model, @NotNull String what) {

        if (node.size() != 1)
            throw new PipelineException("entity poses: %s carries %s of %d members, which names nothing",
                model, what, node.size());
        return node.entrySet().iterator().next();
    }

    private static @NotNull JsonObject object(@NotNull JsonElement node, @NotNull String model) {
        if (!node.isJsonObject()) throw new PipelineException("entity poses: %s carries a non-object where one is owed", model);
        return node.getAsJsonObject();
    }

    private static @NotNull JsonArray array(@NotNull JsonElement node, @NotNull String model) {
        if (!node.isJsonArray()) throw new PipelineException("entity poses: %s carries a non-array where one is owed", model);
        return node.getAsJsonArray();
    }

}
