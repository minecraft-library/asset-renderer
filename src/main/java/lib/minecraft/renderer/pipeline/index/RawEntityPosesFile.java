package lib.minecraft.renderer.pipeline.index;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import lib.minecraft.renderer.asset.pose.PosePredicate;
import lib.minecraft.renderer.exception.PipelineException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
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
 * <p>{@code clips} is read with it, and each play site is resolved to its table here rather than at
 * render, so nothing downstream needs the file's global index and a site naming a clip the file does
 * not carry fails where the file is read. What the file carries beside those is left undeclared for
 * Gson to drop: {@code models} restates which clips a model plays without the rate and amplitude it
 * plays them at, and the three defaults tables answer questions a shipped pose no longer asks - every
 * one of them was resolved where the table was written.
 *
 * <p><b>Walked by hand rather than mapped.</b> An expression node is an object with one member
 * named for what it does, which no field-mapped record shape can describe; and a Gson of this
 * vintage resolves an adapter declared on a nested generic inconsistently, so a single adapter over
 * the whole subtree is both the only shape that fits and the only one that does not depend on that.
 *
 * <p>The file's {@code renderers} member is read beside them - what each renderer puts above every
 * mesh it submits, in the same step shape a pose's container carries and under the same
 * {@code shared} interning, keyed by the renderer rather than by a model because one renderer
 * answers for every mesh a subject draws.
 *
 * <p><b>A model's {@code shared} table is read as the graph it is, never expanded.</b> Every
 * {@code {"ref": n}} resolves to the SAME record instance rather than to a fresh copy, which is what
 * keeps a pose the size the file says it is: a humanoid's arms name nine hundred sub-expressions and
 * stand for twenty-two million, and a reader that rebuilt one per reference would be reading the
 * number the table exists not to write.
 *
 * @param poses the pose of each model class, by simple name
 * @param renderTransforms the steps each renderer puts above every mesh it submits, by renderer
 *     simple name, holding only the renderers that compose one and could be read
 */
@JsonAdapter(RawEntityPosesFile.Adapter.class)
public record RawEntityPosesFile(
    @NotNull Map<String, EntityPose> poses,
    @NotNull Map<String, List<Map<PoseChannel, PoseExpr>>> renderTransforms
) {

    /** Reads the {@code poses} and {@code renderers} subtrees, ignoring what the file carries beside them. */
    static final class Adapter implements JsonDeserializer<RawEntityPosesFile> {

        @Override
        public @NotNull RawEntityPosesFile deserialize(
            @NotNull JsonElement root, @NotNull Type type, @NotNull JsonDeserializationContext context) {

            if (!root.isJsonObject()) throw new PipelineException("entity poses: the file is not an object");
            ConcurrentMap<String, PoseClip> tables = clipTables(root.getAsJsonObject().get("clips"));
            ConcurrentMap<String, List<Map<PoseChannel, PoseExpr>>> transforms =
                renderTransforms(root.getAsJsonObject().get("renderers"));
            JsonElement poses = root.getAsJsonObject().get("poses");
            if (poses == null) return new RawEntityPosesFile(Map.of(), transforms);
            if (!poses.isJsonObject()) throw new PipelineException("entity poses: 'poses' is not an object");

            ConcurrentMap<String, EntityPose> out = poses.getAsJsonObject()
                .entrySet()
                .stream()
                .collect(Concurrent.toUnmodifiableLinkedMap(
                    Map.Entry::getKey,
                    entry -> pose(entry.getKey(), object(entry.getValue(), entry.getKey()), tables)));
            return new RawEntityPosesFile(out, transforms);
        }

    }

    /**
     * What each renderer puts above every mesh it submits, by renderer simple name.
     *
     * <p>Read into the shape a pose's container already has, because that is what it is - a sequence
     * of part poses above every bone a mesh names at top level. It is keyed by RENDERER rather than
     * by model because that is whose fact it is: one renderer submits a body and a run of overlay
     * passes through several model classes, and one transform reaches all of them.
     *
     * <p>A renderer whose own declaration could not be read whole carries a refusal instead of steps
     * and is dropped here. Nothing downstream can act on it - a transform read in half would place
     * the subject somewhere vanilla never puts it - so the row exists for a reader of the table
     * rather than for this one.
     */
    private static @NotNull ConcurrentMap<String, List<Map<PoseChannel, PoseExpr>>> renderTransforms(
        @Nullable JsonElement node) {

        if (node == null) return Concurrent.newUnmodifiableLinkedMap();
        if (!node.isJsonObject())
            throw new PipelineException("entity poses: 'renderers' is not an object");

        return node.getAsJsonObject()
            .entrySet()
            .stream()
            .filter(entry -> object(entry.getValue(), entry.getKey()).get("refused") == null)
            .map(entry -> Map.entry(entry.getKey(),
                composedSteps(entry.getKey(), object(entry.getValue(), entry.getKey()))))
            .filter(entry -> !entry.getValue().isEmpty())
            .collect(Concurrent.toUnmodifiableLinkedMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * The steps one renderer's {@code container} declares, read under that renderer's own
     * {@code shared} table.
     *
     * @param renderer the renderer simple name, for the refusals
     * @param declared the renderer's own declaration
     * @return the steps it composes, empty where it composes none
     */
    private static @NotNull ConcurrentList<Map<PoseChannel, PoseExpr>> composedSteps(
        @NotNull String renderer, @NotNull JsonObject declared) {

        Shared shared = Shared.of(renderer, declared.get("shared"));
        JsonElement held = declared.get("container");
        ConcurrentList<Map<PoseChannel, PoseExpr>> steps = held == null
            ? Concurrent.newUnmodifiableList()
            : array(held, renderer)
                .asList()
                .stream()
                .map(step -> channels(renderer, "container", object(step, renderer), shared))
                .collect(Concurrent.toUnmodifiableList());
        shared.requireAllRead(renderer);
        return steps;
    }

    /**
     * Every authored clip the file carries, by the coordinate a play site names it with.
     *
     * <p>Read once for the file rather than per pose, the same table serving every model that plays
     * it - a nautilus and a zombie nautilus play one swim, and a camel and its saddle one sit.
     */
    private static @NotNull ConcurrentMap<String, PoseClip> clipTables(@Nullable JsonElement node) {
        if (node == null) return Concurrent.newUnmodifiableLinkedMap();
        if (!node.isJsonObject()) throw new PipelineException("entity poses: 'clips' is not an object");

        return node.getAsJsonObject()
            .entrySet()
            .stream()
            .collect(Concurrent.toUnmodifiableLinkedMap(
                Map.Entry::getKey,
                entry -> clipTable(entry.getKey(), object(entry.getValue(), entry.getKey()))));
    }

    /**
     * One authored clip.
     *
     * <p>Times and values are narrowed through {@code float} on the way in, on the same terms a
     * literal is: they are what vanilla's own builder computed at that width, and reading the
     * decimal back as a double gives a number no float ever held.
     */
    private static @NotNull PoseClip clipTable(@NotNull String coordinate, @NotNull JsonObject node) {
        JsonElement length = node.get("length");
        if (length == null)
            throw new PipelineException("entity poses: clip %s declares no length", coordinate);

        JsonElement declared = node.get("channels");
        ConcurrentList<PoseClip.Channel> channels = declared == null
            ? Concurrent.newUnmodifiableList()
            : array(declared, coordinate)
                .asList()
                .stream()
                .map(channel -> clipChannel(coordinate, object(channel, coordinate)))
                .collect(Concurrent.toUnmodifiableList());

        JsonElement looping = node.get("looping");
        return new PoseClip((float) length.getAsDouble(),
            looping != null && looping.getAsBoolean(), channels);
    }

    /** One bone's displacement table, in one of its three members. */
    private static @NotNull PoseClip.Channel clipChannel(
        @NotNull String coordinate, @NotNull JsonObject node) {

        JsonElement bone = node.get("bone");
        JsonElement target = node.get("target");
        if (bone == null || target == null)
            throw new PipelineException("entity poses: clip %s carries a channel naming no bone or no target",
                coordinate);

        PoseClip.Target displaces = PoseClip.Target.ofToken(target.getAsString());
        if (displaces == null)
            throw new PipelineException("entity poses: clip %s displaces '%s', which is not a target",
                coordinate, target.getAsString());

        JsonElement declared = node.get("keyframes");
        ConcurrentList<PoseClip.Keyframe> keyframes = declared == null
            ? Concurrent.newUnmodifiableList()
            : array(declared, coordinate)
                .asList()
                .stream()
                .map(keyframe -> clipKeyframe(coordinate, object(keyframe, coordinate)))
                .collect(Concurrent.toUnmodifiableList());
        if (keyframes.isEmpty())
            throw new PipelineException("entity poses: clip %s displaces '%s' at no instant",
                coordinate, bone.getAsString());

        return new PoseClip.Channel(bone.getAsString(), displaces, keyframes);
    }

    /** One authored instant of a channel. */
    private static @NotNull PoseClip.Keyframe clipKeyframe(
        @NotNull String coordinate, @NotNull JsonObject node) {

        JsonElement time = node.get("time");
        JsonElement value = node.get("value");
        JsonElement curve = node.get("curve");
        if (time == null || value == null || curve == null)
            throw new PipelineException("entity poses: clip %s carries a keyframe missing a member",
                coordinate);

        PoseClip.Interpolation interpolation = PoseClip.Interpolation.ofToken(curve.getAsString());
        if (interpolation == null)
            throw new PipelineException("entity poses: clip %s reaches a keyframe by '%s', which is not a curve",
                coordinate, curve.getAsString());

        JsonArray components = array(value, coordinate);
        if (components.size() != 3)
            throw new PipelineException("entity poses: clip %s carries a keyframe of %d components, which takes 3",
                coordinate, components.size());

        return new PoseClip.Keyframe((float) time.getAsDouble(),
            (float) components.get(0).getAsDouble(), (float) components.get(1).getAsDouble(),
            (float) components.get(2).getAsDouble(), interpolation);
    }

    /** One model's pose, or the record of why it has none. */
    private static @NotNull EntityPose pose(
        @NotNull String model, @NotNull JsonObject node, @NotNull Map<String, PoseClip> tables) {

        JsonElement refused = node.get("refused");
        if (refused != null)
            return new EntityPose(Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableMap(Map.of()),
                Concurrent.newUnmodifiableList(), Optional.of(refused.getAsString()));

        Shared shared = Shared.of(model, node.get("shared"));

        // The container the mesh flattened away, which is a parent transform above the bones rather
        // than one of them, so it is read into its own list and never into the bone map. An ORDERED
        // list, because a renderer composing one out of a sequence says everything by the order:
        // each element is a part pose, and only a body posing a flattened root writes just the one.
        JsonElement held = node.get("container");
        ConcurrentList<Map<PoseChannel, PoseExpr>> container = held == null
            ? Concurrent.newUnmodifiableList()
            : array(held, model)
                .asList()
                .stream()
                .map(step -> channels(model, "container", object(step, model), shared))
                .collect(Concurrent.toUnmodifiableList());

        // Order-preserving rather than Map.copyOf, whose iteration order is salted per JVM launch.
        // Nothing reads the order for meaning, but the parity dump digests this map, and a digest
        // over a map that flaps is a row that fails its own reproducibility check and nothing else.
        JsonElement written = node.get("bones");
        ConcurrentMap<String, Map<PoseChannel, PoseExpr>> bones = written == null
            ? Concurrent.newUnmodifiableLinkedMap()
            : object(written, model)
                .entrySet()
                .stream()
                .collect(Concurrent.toUnmodifiableLinkedMap(
                    Map.Entry::getKey,
                    bone -> channels(model, bone.getKey(), object(bone.getValue(), model), shared)));

        JsonElement played = node.get("clips");
        ConcurrentList<EntityPose.Clip> clips = played == null
            ? Concurrent.newUnmodifiableList()
            : array(played, model)
                .asList()
                .stream()
                .map(site -> clip(model, object(site, model), shared, tables))
                .collect(Concurrent.toUnmodifiableList());

        shared.requireAllRead(model);
        return new EntityPose(container, bones, clips, Optional.empty());
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
            // The emitter still writes the two flag channels; which bones a subject rests without is
            // resolved at generation into the model table's undrawn lists, so nothing here reads one.
            if ("visible".equals(entry.getKey()) || "skip_draw".equals(entry.getKey())) continue;
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
        @NotNull String model, @NotNull JsonObject node, @NotNull Shared shared,
        @NotNull Map<String, PoseClip> tables) {

        JsonElement coordinate = node.get("clip");
        JsonElement gate = node.get("gate");
        if (coordinate == null || gate == null)
            throw new PipelineException("entity poses: %s plays a clip that names no coordinate or no drive", model);

        PoseClip table = tables.get(coordinate.getAsString());
        if (table == null)
            throw new PipelineException("entity poses: %s plays '%s', which this file declares no table for",
                model, coordinate.getAsString());

        EntityPose.Gate drive = EntityPose.Gate.findByToken(gate.getAsString())
            .orElseThrow(() -> new PipelineException("entity poses: %s drives a clip by '%s', which is not a drive",
                model, gate.getAsString()));

        // A state-driven site names the field its gate reads, and the other two drives write none.
        // Refused rather than defaulted: a site the emitter left unnamed would answer zero at render
        // and never play, which reads exactly like a subject nothing has ticked.
        JsonElement state = node.get("state");
        if (drive == EntityPose.Gate.STATE && state == null)
            throw new PipelineException(
                "entity poses: %s gates '%s' on an animation state it names nowhere", model,
                coordinate.getAsString());

        JsonElement args = node.get("args");
        ConcurrentList<PoseExpr> arguments = args == null
            ? Concurrent.newUnmodifiableList()
            : array(args, model)
                .asList()
                .stream()
                .map(argument -> expression(model, argument, shared))
                .collect(Concurrent.toUnmodifiableList());
        return new EntityPose.Clip(coordinate.getAsString(), drive,
            state == null ? "" : state.getAsString(), arguments, table);
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
            ConcurrentList<PoseExpr> operands = array(body, model)
                .asList()
                .stream()
                .map(operand -> expression(model, operand, shared))
                .collect(Concurrent.toUnmodifiableList());
            if (operands.size() != operator.arity())
                throw new PipelineException("entity poses: %s applies '%s' to %d operand(s), which takes %d",
                    model, token, operands.size(), operator.arity());
            return new PoseExpr.Op(operator, operands);
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

        if ("ref".equals(token)) return shared.condition(model, body.getAsInt());

        PosePredicate.Comparison comparison = PosePredicate.Comparison.ofToken(token);
        if (comparison == null)
            throw new PipelineException(
                "entity poses: %s turns on '%s', which this renderer does not know how to read", model, token);

        JsonArray operands = array(body, model);
        return new PosePredicate(comparison,
            expression(model, operands.get(0), shared), expression(model, operands.get(1), shared));
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
