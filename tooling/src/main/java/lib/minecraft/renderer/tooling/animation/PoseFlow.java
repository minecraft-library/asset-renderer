package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Emits the skeletal pose table: every authored keyframe clip, and which model plays which.
 *
 * <p>The table is self-contained and joins to the others by the coordinate they already share. A
 * clip is keyed the way a mesh is, {@code Class#member}, and the model roster is keyed by the same
 * class the geometry coordinates are headed with - so a reader that has resolved a mesh has
 * everything it needs to find the poses that mesh can take, and nothing had to be threaded through
 * the model table to say so.
 *
 * <p>Every declared clip is emitted, including the two no model plays. Dropping them would make a
 * table that lost a clip indistinguishable from a walk that failed to find one, and the count is
 * what the corpus reconciliation checks.
 */
@UtilityClass
public final class PoseFlow {

    /**
     * Parses every clip and every binding, then writes the pose table.
     *
     * @param session the live session
     * @param manifest the registry the models walk populated, read for its factory classes
     * @param rootBones the bones each class's mesh names at top level, from the geometry flow
     * @param out the output path
     */
    public static void emit(
        @NotNull ToolingSession session, @NotNull GeometryManifest manifest,
        @NotNull Map<String, Set<String>> rootBones, @NotNull Path out) {

        Diagnostics diagnostics = session.diagnostics().child("pose");
        List<KeyframeClip> clips = KeyframeDefinitionParser.parseAll(session.cache(), diagnostics);
        Set<String> roster = rosterClasses(manifest);
        Map<String, List<ClipBinding>> byModel = resolveModels(session, roster, diagnostics);
        Map<String, PoseOutcome> poses = walkModels(session, roster, rootBones, diagnostics);

        JsonTree root = session.envelope("definitions-package listing order for clips; "
            + "model simple name for models and for poses, and bone name within a pose");
        JsonTree clipsNode = root.child("clips");
        for (KeyframeClip clip : clips) clipsNode.put(clip.coordinate(), clipNode(clip));

        JsonTree modelsNode = root.child("models");
        for (Map.Entry<String, List<ClipBinding>> entry : byModel.entrySet()) {
            JsonTree bindings = JsonTree.array();
            for (ClipBinding binding : entry.getValue())
                bindings.add(JsonTree.object().put("clip", binding.clip()).put("gate", binding.gate().token()));
            modelsNode.put(entry.getKey(), bindings);
        }

        // Written after the two that were here first, so neither of them moves a byte.
        JsonTree posesNode = root.child("poses");
        PoseJson.all(poses).forEach(posesNode::put);

        // What a figure reads as before anything has happened to the subject, for the figures whose
        // own render state builds them at something other than nothing. Written at the root because
        // a figure is named by its bare field name, which is one keyspace across every model.
        Map<String, Float> defaults =
            InputDefaultResolver.resolve(session.cache(), InputDefaultResolver.namedBy(poses), diagnostics);
        if (!defaults.isEmpty()) {
            JsonTree defaultsNode = root.child("input_defaults");
            defaults.forEach(defaultsNode::put);
        }

        reportDeadClips(clips, byModel, diagnostics);
        reportRefusedPoses(poses, diagnostics);
        root.write(out);
        diagnostics.info("wrote %s", out.toAbsolutePath());
    }

    /** One clip's length, looping flag and channels, in declaration order. */
    private static @NotNull JsonTree clipNode(@NotNull KeyframeClip clip) {
        JsonTree node = JsonTree.object().put("length", clip.lengthSeconds());
        if (clip.looping()) node.put("looping", true);
        JsonTree channels = node.childArray("channels");
        for (KeyframeClip.BoneChannel channel : clip.channels()) {
            JsonTree channelNode = JsonTree.object()
                .put("bone", channel.bone())
                .put("target", channel.target());
            JsonTree keyframes = channelNode.childArray("keyframes");
            for (AnimationValue.Frame frame : channel.keyframes()) {
                JsonTree keyframe = JsonTree.object().put("time", frame.timeSeconds());
                keyframe.putFloats("value", frame.vector().x(), frame.vector().y(), frame.vector().z());
                keyframe.put("curve", frame.interpolation());
                keyframes.add(keyframe);
            }
            channels.add(channelNode);
        }
        return node;
    }

    /**
     * Resolves the clip bindings of every model class the geometry manifest names.
     *
     * <p>Keyed by the factory class, which is what a geometry coordinate is headed with and what
     * the bone walk already treats as the model. A class appears in the manifest once per mesh
     * derivation, so it is resolved once and the result reused.
     */
    private static @NotNull Map<String, List<ClipBinding>> resolveModels(
        @NotNull ToolingSession session, @NotNull Set<String> roster, @NotNull Diagnostics diagnostics) {

        Map<String, List<ClipBinding>> out = new TreeMap<>();
        for (String internal : roster) {
            List<ClipBinding> bindings = ClipBindingResolver.resolve(session.cache(), internal, diagnostics);
            if (bindings.isEmpty()) continue;
            out.merge(ClassKit.simpleName(internal), bindings, (first, second) -> first);
        }
        return out;
    }

    /**
     * Every model class the geometry manifest sources a mesh from.
     *
     * <p>Held apart from what is done with it, because the two halves want different subsets: a
     * model binds a clip only if it plays one, and every model has a pose even when that pose is
     * empty. Narrowing the roster where the clips are resolved would have made the poses the same
     * twenty-one rows rather than the hundred and eleven models there are.
     */
    private static @NotNull Set<String> rosterClasses(@NotNull GeometryManifest manifest) {
        Set<String> classes = new LinkedHashSet<>();
        for (GeometryRequest request : manifest.entries().values()) classes.add(request.factoryClass());
        return classes;
    }

    /**
     * Walks every model's {@code setupAnim} into the pose it computes, or into why there is not one.
     *
     * <p>Keyed by simple name like the rest of the table, first binding winning, which is the same
     * rule the clip bindings resolve under - a class appears once per mesh derivation and answers
     * the same either time.
     */
    private static @NotNull Map<String, PoseOutcome> walkModels(
        @NotNull ToolingSession session, @NotNull Set<String> roster,
        @NotNull Map<String, Set<String>> rootBones, @NotNull Diagnostics diagnostics) {

        Map<String, PoseOutcome> out = new TreeMap<>();
        for (String internal : roster)
            out.putIfAbsent(ClassKit.simpleName(internal), PoseWalk.extract(
                session.cache(), internal, rootBones.getOrDefault(internal, Set.of()), diagnostics));
        return out;
    }

    /**
     * Counts the models whose pose could not be walked, so the gap is a stated number rather than
     * something a reader has to total up out of the table.
     */
    private static void reportRefusedPoses(
        @NotNull Map<String, PoseOutcome> poses, @NotNull Diagnostics diagnostics) {

        long refused = poses.values().stream().filter(PoseOutcome.Refused.class::isInstance).count();
        if (refused > 0)
            diagnostics.info("walked %d of %d model poses; %d refused",
                poses.size() - refused, poses.size(), refused);
    }

    /**
     * Records the clips nothing plays, so shipping them is a stated decision rather than an
     * oversight a reader has to rediscover.
     */
    private static void reportDeadClips(
        @NotNull List<KeyframeClip> clips, @NotNull Map<String, List<ClipBinding>> byModel,
        @NotNull Diagnostics diagnostics) {

        Set<String> played = new LinkedHashSet<>();
        byModel.values().forEach(bindings -> bindings.forEach(binding -> played.add(binding.clip())));
        Set<String> dead = new TreeSet<>();
        for (KeyframeClip clip : clips)
            if (!played.contains(clip.coordinate())) dead.add(clip.coordinate());
        if (!dead.isEmpty())
            diagnostics.info("emitted %d clip(s) no model plays: %s", dead.size(), String.join(", ", dead));
    }

}
