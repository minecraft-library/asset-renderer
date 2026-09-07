package lib.minecraft.renderer.pose.install;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.PosePredicate;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * A serializer of a woven pose row into the table spelling the pose table reader parses - the
 * row's {@code {shared, container, bones, clips}} fragment plus one file-level clip table per
 * distinct coordinate its play sites name, so a matured custom style can graduate into an
 * authored table without a hand transcription of its graphs.
 *
 * <p><b>The graph is preserved, never expanded.</b> The table syntax is tree-with-refs, and a
 * naive recursive writer spells one subtree per path - a pose is a graph whose nodes stand for
 * enormously many paths, so that expansion does not terminate in practice. One identity-keyed
 * walk counts references per node instance, every multiply-referenced node takes a
 * {@code shared[]} index bottom-up in first-visit order - children indexed before any parent
 * spells them - and every use of an indexed node is written {@code {"ref": n}}. The reader
 * resolves each entry back to one instance, so the sharing the row carries survives the round
 * trip.
 *
 * <p>Emission is graph-shape-only and deterministic: radians stay radians and seconds stay
 * seconds, each literal is spelled at the width its token names, bones spell in name order and
 * channels in the vocabulary's own order, so two emits of one row are byte-identical and a
 * reloaded fragment evaluates bit-identically to its source. The clip tables ride along because
 * a play site names a coordinate the enclosing file must declare - a row fragment alone cannot
 * load - and a row playing no site emits the fragment alone. Where the emission lands is the
 * caller's decision; nothing here writes a file.
 */
@Parity(subject = Subject.ENTITY)
public final class PoseEmitter {

    private PoseEmitter() {}

    /**
     * Serializes a pose row to its table spelling - the pose-row fragment and the file-level
     * clip table each of its play sites names.
     *
     * @param pose the row to serialize
     * @return the emitted fragment and its clip tables
     * @throws IllegalArgumentException if the pose records a refusal, a literal's width does not
     * hold its value exactly, a clip channel displaces at no instant, or two play sites resolve
     * one coordinate to different tables
     */
    public static @NotNull Emission emit(@NotNull EntityPose pose) {
        if (!pose.isReadable())
            throw new IllegalArgumentException(String.format(
                "Cannot emit a pose that could not be read: %s", pose.refusal().orElseThrow()));
        Writer writer = new Writer(pose);
        return new Emission(writer.row().toString(), writer.clips());
    }

    /**
     * One emitted row - the pose-row fragment beside the file-level table each of its play
     * sites names, held apart because the two land in different members of an enclosing file.
     *
     * @param row the {@code {shared, container, bones, clips}} pose-row fragment, as JSON text
     * @param clips one file-level clip table per distinct play-site coordinate, as JSON text
     * keyed by coordinate in first-site order - empty for a row playing no site
     */
    public record Emission(
        @NotNull String row,
        @NotNull ConcurrentMap<String, String> clips
    ) {}

    /**
     * One emission pass over one row - the reference count, the shared-table assignment and the
     * spelling, in that order, every walk keyed on node identity.
     *
     * <p>Each walk short-circuits on an instance already seen, so a node is visited once however
     * many paths reach it and the sharing the row's graphs carry is exactly what the emitted
     * table declares.
     */
    private static final class Writer {

        private final @NotNull EntityPose pose;
        private final @NotNull Map<String, Map<PoseChannel, PoseExpr>> bones;
        private final @NotNull Map<Object, Integer> occurrences = new IdentityHashMap<>();
        private final @NotNull Map<Object, Integer> indexed = new IdentityHashMap<>();
        private final @NotNull List<Object> table = new ArrayList<>();

        private Writer(@NotNull EntityPose pose) {
            this.pose = pose;
            this.bones = new TreeMap<>(pose.bones());
            this.roots(this::count);
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            this.roots(root -> this.assign(root, visited));
        }

        // ------------------------------------------------------------------------------------
        // reference counting and shared-table assignment
        // ------------------------------------------------------------------------------------

        /**
         * Hands every expression root of the row to one walk in spelling order - container steps
         * first, then bones by name, then play-site arguments, channels always in the
         * vocabulary's own order - so counting, assignment and spelling all see one sequence.
         */
        private void roots(@NotNull Consumer<PoseExpr> walk) {
            for (Map<PoseChannel, PoseExpr> step : this.pose.container())
                written(step, walk);
            for (Map<PoseChannel, PoseExpr> channels : this.bones.values())
                written(channels, walk);
            for (EntityPose.Clip site : this.pose.clips())
                site.arguments().forEach(walk);
        }

        /**
         * Hands one channel map's written expressions to a walk, in the vocabulary's own order.
         */
        private static void written(
            @NotNull Map<PoseChannel, PoseExpr> channels, @NotNull Consumer<PoseExpr> walk) {

            for (PoseChannel channel : PoseChannel.values()) {
                PoseExpr held = channels.get(channel);
                if (held != null) walk.accept(held);
            }
        }

        /**
         * Counts one reference to an expression, descending into its children only on the first -
         * a node is counted per reference but walked per instance, which is what keeps the count
         * linear over a graph standing for enormously many paths.
         */
        private void count(@NotNull PoseExpr node) {
            if (this.occurrences.merge(node, 1, Integer::sum) > 1) return;
            switch (node) {
                case PoseExpr.Op op -> op.operands().forEach(this::count);
                case PoseExpr.Select select -> {
                    this.count(select.condition());
                    this.count(select.whenTrue());
                    this.count(select.whenFalse());
                }
                default -> { }
            }
        }

        /**
         * Counts one reference to a condition, under the same contract as the expression walk.
         */
        private void count(@NotNull PosePredicate node) {
            if (this.occurrences.merge(node, 1, Integer::sum) > 1) return;
            this.count(node.left());
            this.count(node.right());
        }

        /**
         * Assigns {@code shared[]} indices bottom-up in first-visit order - children take an
         * index before any parent spells them, so every reference an entry writes points at an
         * earlier entry and two emits of one row assign identically.
         */
        private void assign(@NotNull PoseExpr node, @NotNull Set<Object> visited) {
            if (!visited.add(node)) return;
            switch (node) {
                case PoseExpr.Op op -> op.operands().forEach(operand -> this.assign(operand, visited));
                case PoseExpr.Select select -> {
                    this.assign(select.condition(), visited);
                    this.assign(select.whenTrue(), visited);
                    this.assign(select.whenFalse(), visited);
                }
                default -> { }
            }
            this.index(node);
        }

        /**
         * Assigns condition indices, under the same contract as the expression walk.
         */
        private void assign(@NotNull PosePredicate node, @NotNull Set<Object> visited) {
            if (!visited.add(node)) return;
            this.assign(node.left(), visited);
            this.assign(node.right(), visited);
            this.index(node);
        }

        /**
         * Registers one node in the shared table when more than one reference reaches it.
         */
        private void index(@NotNull Object node) {
            if (this.occurrences.get(node) < 2) return;
            this.indexed.put(node, this.table.size());
            this.table.add(node);
        }

        // ------------------------------------------------------------------------------------
        // the pose-row fragment
        // ------------------------------------------------------------------------------------

        /**
         * The pose-row fragment - shared, container, bones, play sites and state silhouettes,
         * empty members omitted.
         */
        private @NotNull JsonObject row() {
            JsonObject out = new JsonObject();
            if (!this.table.isEmpty()) {
                JsonArray shared = new JsonArray();
                for (Object node : this.table)
                    shared.add(node instanceof PoseExpr expression
                        ? this.body(expression)
                        : this.body((PosePredicate) node));
                out.add("shared", shared);
            }
            if (!this.pose.container().isEmpty()) {
                JsonArray steps = new JsonArray();
                for (Map<PoseChannel, PoseExpr> step : this.pose.container())
                    steps.add(this.channelsOf(step));
                out.add("container", steps);
            }
            if (!this.bones.isEmpty()) {
                JsonObject written = new JsonObject();
                this.bones.forEach((bone, channels) -> written.add(bone, this.channelsOf(channels)));
                out.add("bones", written);
            }
            if (!this.pose.clips().isEmpty()) {
                JsonArray sites = new JsonArray();
                for (EntityPose.Clip site : this.pose.clips())
                    sites.add(this.site(site));
                out.add("clips", sites);
            }
            if (!this.pose.states().isEmpty()) {
                // Each silhouette spells its bones through a writer of its own, so its shared
                // table is scoped to its bones exactly as the reader scopes it on the way back.
                JsonObject states = new JsonObject();
                new TreeMap<>(this.pose.states()).forEach((key, silhouette) ->
                    states.add(key, new Writer(new EntityPose(Concurrent.newUnmodifiableList(),
                        silhouette.bones(), Concurrent.newUnmodifiableList(), Optional.empty())).row()));
                out.add("states", states);
            }
            return out;
        }

        /**
         * One channel map's writes, each channel token carrying a use of its expression.
         */
        private @NotNull JsonObject channelsOf(@NotNull Map<PoseChannel, PoseExpr> channels) {
            JsonObject out = new JsonObject();
            for (PoseChannel channel : PoseChannel.values()) {
                PoseExpr held = channels.get(channel);
                if (held != null) out.add(channel.token(), this.spell(held));
            }
            return out;
        }

        /**
         * One play site - the coordinate, its drive token, the gate field where present, its arguments.
         */
        private @NotNull JsonObject site(@NotNull EntityPose.Clip site) {
            JsonObject out = new JsonObject();
            out.addProperty("clip", site.coordinate());
            out.addProperty("drive", site.drive().token());
            site.field().ifPresent(field -> out.addProperty("field", field));
            if (!site.arguments().isEmpty()) {
                JsonArray arguments = new JsonArray();
                for (PoseExpr argument : site.arguments())
                    arguments.add(this.spell(argument));
                out.add("args", arguments);
            }
            return out;
        }

        /**
         * One use of an expression - a reference where the node is indexed, its body inline otherwise.
         */
        private @NotNull JsonObject spell(@NotNull PoseExpr node) {
            Integer at = this.indexed.get(node);
            return at == null ? this.body(node) : ref(at);
        }

        /**
         * One use of a condition - a reference where the node is indexed, its body inline otherwise.
         */
        private @NotNull JsonObject spell(@NotNull PosePredicate node) {
            Integer at = this.indexed.get(node);
            return at == null ? this.body(node) : ref(at);
        }

        /**
         * One expression's own spelling - a single member named for what it does, children as uses.
         */
        private @NotNull JsonObject body(@NotNull PoseExpr node) {
            JsonObject out = new JsonObject();
            switch (node) {
                case PoseExpr.Const held -> this.literal(out, held);
                case PoseExpr.Input input -> out.addProperty("input", input.field());
                case PoseExpr.BoneRead read -> {
                    JsonArray coordinates = new JsonArray();
                    coordinates.add(read.bone());
                    coordinates.add(read.channel().token());
                    out.add("bone", coordinates);
                }
                case PoseExpr.Op op -> {
                    JsonArray operands = new JsonArray();
                    for (PoseExpr operand : op.operands())
                        operands.add(this.spell(operand));
                    out.add(op.operator().token(), operands);
                }
                case PoseExpr.Select select -> {
                    JsonArray arms = new JsonArray();
                    arms.add(this.spell(select.condition()));
                    arms.add(this.spell(select.whenTrue()));
                    arms.add(this.spell(select.whenFalse()));
                    out.add("select", arms);
                }
            }
            return out;
        }

        /**
         * One condition's own spelling - the comparison token over its two operand uses.
         */
        private @NotNull JsonObject body(@NotNull PosePredicate node) {
            JsonObject out = new JsonObject();
            JsonArray operands = new JsonArray();
            operands.add(this.spell(node.left()));
            operands.add(this.spell(node.right()));
            out.add(node.comparison().token(), operands);
            return out;
        }

        /**
         * One literal at the width its token names - the reader narrows {@code const} through
         * float and reads {@code iconst} as an int, so a value those readings do not hold
         * exactly refuses here rather than reloading as a near miss.
         */
        private void literal(@NotNull JsonObject out, @NotNull PoseExpr.Const held) {
            double value = held.value();
            switch (held.width()) {
                case FLOAT -> {
                    if ((double) (float) value != value)
                        throw new IllegalArgumentException(String.format(
                            "Cannot emit float literal '%s', which no float holds exactly", value));
                    out.addProperty("const", (float) value);
                }
                case DOUBLE -> out.addProperty("dconst", value);
                case INT -> {
                    if ((double) (int) value != value)
                        throw new IllegalArgumentException(String.format(
                            "Cannot emit int literal '%s', which no int holds exactly", value));
                    out.addProperty("iconst", (int) value);
                }
            }
        }

        /**
         * One reference into the shared table.
         */
        private static @NotNull JsonObject ref(int at) {
            JsonObject out = new JsonObject();
            out.addProperty("ref", at);
            return out;
        }

        // ------------------------------------------------------------------------------------
        // the file-level clip tables
        // ------------------------------------------------------------------------------------

        /**
         * The file-level clip tables the row's play sites name, keyed once per distinct
         * coordinate in first-site order - each site holds its resolved table instance, so the
         * tables are read off the row itself.
         */
        private @NotNull ConcurrentMap<String, String> clips() {
            Map<String, PoseClip> tables = new LinkedHashMap<>();
            for (EntityPose.Clip site : this.pose.clips()) {
                PoseClip held = tables.putIfAbsent(site.coordinate(), site.clip());
                if (held != null && held != site.clip() && !held.equals(site.clip()))
                    throw new IllegalArgumentException(String.format(
                        "Cannot emit coordinate '%s', which two play sites resolve to different tables",
                        site.coordinate()));
            }
            return tables.entrySet()
                .stream()
                .collect(Concurrent.toUnmodifiableLinkedMap(
                    Map.Entry::getKey,
                    entry -> table(entry.getKey(), entry.getValue()).toString()));
        }

        /**
         * One clip table - its length, looping where it loops, and each channel it displaces.
         */
        private static @NotNull JsonObject table(@NotNull String coordinate, @NotNull PoseClip table) {
            JsonObject out = new JsonObject();
            out.addProperty("length", table.lengthSeconds());
            if (table.looping()) out.addProperty("looping", true);
            if (!table.channels().isEmpty()) {
                JsonArray channels = new JsonArray();
                for (PoseClip.Channel channel : table.channels())
                    channels.add(channel(coordinate, channel));
                out.add("channels", channels);
            }
            return out;
        }

        /**
         * One displacement channel, refusing an instant-free one here rather than at reload.
         */
        private static @NotNull JsonObject channel(
            @NotNull String coordinate, @NotNull PoseClip.Channel channel) {

            if (channel.keyframes().isEmpty())
                throw new IllegalArgumentException(String.format(
                    "Cannot emit clip '%s', which displaces '%s' at no instant",
                    coordinate, channel.bone()));
            JsonObject out = new JsonObject();
            out.addProperty("bone", channel.bone());
            out.addProperty("target", channel.target().token());
            JsonArray keyframes = new JsonArray();
            for (PoseClip.Keyframe keyframe : channel.keyframes())
                keyframes.add(keyframe(keyframe));
            out.add("keyframes", keyframes);
            return out;
        }

        /**
         * One authored instant - its time, the three components, and the curve riding it.
         */
        private static @NotNull JsonObject keyframe(@NotNull PoseClip.Keyframe keyframe) {
            JsonObject out = new JsonObject();
            out.addProperty("time", keyframe.timeSeconds());
            JsonArray value = new JsonArray();
            value.add(keyframe.x());
            value.add(keyframe.y());
            value.add(keyframe.z());
            out.add("value", value);
            out.addProperty("curve", keyframe.interpolation().token());
            return out;
        }

    }

}
