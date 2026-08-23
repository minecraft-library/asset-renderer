package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Emits the skeletal pose table: every authored keyframe clip, and every model's pose.
 *
 * <p>The table is self-contained and joins to the others by the coordinate they already share. A
 * clip is keyed the way a mesh is, {@code Class#member}, and a pose is keyed by the same class the
 * geometry coordinates are headed with - so a reader that has resolved a mesh has everything it
 * needs to find the pose that mesh takes, and each pose row carries the play sites its own body
 * reaches, gate and arguments included.
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
     * @param posing the model classes the renderers pose with, which the manifest does not name
     * @param renderers the subjects' renderer classes, read for what each composes above its meshes
     * @param out the output path
     */
    public static void emit(
        @NotNull ToolingSession session, @NotNull GeometryManifest manifest,
        @NotNull Map<String, Set<String>> rootBones, @NotNull Set<String> posing,
        @NotNull Set<String> renderers, @NotNull Path out) {

        Diagnostics diagnostics = session.diagnostics().child("pose");
        List<KeyframeClip> clips = KeyframeDefinitionParser.parseAll(session.cache(), diagnostics);
        Map<String, String> roster = rosterClasses(session, manifest, posing, diagnostics);
        Map<String, PoseOutcome> poses = walkModels(session, roster, rootBones, diagnostics);
        Map<String, RenderTransform> transforms = walkRenderers(session, renderers);

        JsonTree root = session.envelope("definitions-package listing order for clips; "
            + "model simple name for poses, and bone name within a pose");
        JsonTree clipsNode = root.child("clips");
        for (KeyframeClip clip : clips) clipsNode.put(clip.coordinate(), clipNode(clip));

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

        // Which constant each enum member rests holding, for the members a pose switches on. Held
        // apart from the figures because answering nothing is a real answer for one and no answer at
        // all for the other: a figure nobody models rests at zero, where an enum member that matches
        // no constant is in a state no enum is in.
        //
        // Keyed by MODEL rather than written flat, because the keyspace a bare field name spans is
        // not one type. Two unrelated states declare a 'pose' and two more a 'rightArmPose', and a
        // flat table cannot say which a subject reads - where the model can, its own setupAnim
        // naming the state its members are read off.
        //
        // What a QUESTION rests answering rides the same keying and the same walk of the state
        // chain, because it is the same question about a different kind of member: a reference the
        // state holds is a whole value, and every component of it is an answer a subject stands at.
        JsonTree restingNode = JsonTree.object();
        JsonTree questionsNode = JsonTree.object();
        Set<String> switched = InputDefaultResolver.constantsNamedBy(poses);
        Set<String> asked = InputDefaultResolver.questionsNamedBy(poses);
        for (Map.Entry<String, String> model : roster.entrySet()) {
            String renderState = PoseWalk.renderStateOf(session.cache(), model.getKey());
            if (renderState == null) continue;
            String name = ClassKit.simpleName(model.getKey());
            Map<String, String> resting =
                InputDefaultResolver.resolveConstants(session.cache(), renderState, switched);
            if (!resting.isEmpty()) {
                JsonTree perModel = JsonTree.object();
                resting.forEach(perModel::put);
                restingNode.put(name, perModel);
            }
            Map<String, Float> answers =
                InputDefaultResolver.resolveQuestions(session.cache(), renderState, asked);
            if (answers.isEmpty()) continue;
            JsonTree perModelQuestions = JsonTree.object();
            answers.forEach(perModelQuestions::put);
            questionsNode.put(name, perModelQuestions);
        }
        if (!restingNode.isEmpty()) root.put("rest_defaults", restingNode);
        if (!questionsNode.isEmpty()) root.put("question_defaults", questionsNode);

        // What each RENDERER puts above every mesh it submits, which is a fact about the renderer
        // rather than about any one model: a subject draws its body and its overlay passes through
        // several model classes and one transform reaches all of them. Keyed by renderer simple
        // name, which is what the model table already carries per subject.
        //
        // Written last so nothing above it moves a byte.
        if (!transforms.isEmpty()) {
            JsonTree renderersNode = root.child("renderers");
            PoseJson.allTransforms(transforms).forEach(renderersNode::put);
        }

        reportDeadClips(clips, poses, diagnostics);
        reportRefusedPoses(poses, diagnostics);
        reportRefusedTransforms(transforms, diagnostics);
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
     * Every model class the geometry manifest sources a mesh from.
     *
     * <p>Held apart from what is done with it, because the two halves want different subsets: a
     * model binds a clip only if it plays one, and every model has a pose even when that pose is
     * empty. Narrowing the roster where the clips are resolved would have made the poses the same
     * twenty-one rows rather than the hundred and eleven models there are.
     */
    private static @NotNull Map<String, String> rosterClasses(
        @NotNull ToolingSession session, @NotNull GeometryManifest manifest,
        @NotNull Set<String> posing, @NotNull Diagnostics diagnostics) {

        Map<String, String> classes = new LinkedHashMap<>();
        for (GeometryRequest request : manifest.entries().values())
            classes.putIfAbsent(request.factoryClass(), request.factoryClass());
        // A class that poses a mesh it did not bake still has to be walked, or the model table names
        // a pose the pose table does not carry and the reader resolves it to nothing at all. The mesh
        // it poses is the one its nearest baking ancestor declares - a subclass reusing its parent's
        // layer is exactly what put it here - so that is where its top-level bones are read from.
        for (String model : posing) {
            if (classes.containsKey(model)) continue;
            String bakes = nearestBaking(session, model, classes.keySet());
            if (bakes == null) {
                diagnostics.warn("pose class '%s' bakes no mesh and inherits none - walked without one",
                    ClassKit.simpleName(model));
                bakes = model;
            }
            classes.put(model, bakes);
        }
        return classes;
    }

    /**
     * The nearest class up a model's own hierarchy that bakes a mesh, itself included.
     *
     * <p>What a posing subclass poses is whatever its parent declared, because reusing that layer
     * rather than declaring one is the whole reason the two classes parted company.
     */
    private static @Nullable String nearestBaking(
        @NotNull ToolingSession session, @NotNull String model, @NotNull Set<String> baking) {

        String[] found = {null};
        ClassKit.walkSuperChain(session.cache(), model, node -> {
            if (found[0] == null && baking.contains(node.name)) found[0] = node.name;
        });
        return found[0];
    }

    /**
     * Walks every model's {@code setupAnim} into the pose it computes, or into why there is not one.
     *
     * <p>Keyed by simple name like the rest of the table, first binding winning, which is the same
     * rule the clip bindings resolve under - a class appears once per mesh derivation and answers
     * the same either time.
     */
    private static @NotNull Map<String, PoseOutcome> walkModels(
        @NotNull ToolingSession session, @NotNull Map<String, String> roster,
        @NotNull Map<String, Set<String>> rootBones, @NotNull Diagnostics diagnostics) {

        Map<String, PoseOutcome> out = new TreeMap<>();
        for (Map.Entry<String, String> model : roster.entrySet())
            out.putIfAbsent(ClassKit.simpleName(model.getKey()), PoseWalk.extract(
                session.cache(), model.getKey(),
                rootBones.getOrDefault(model.getValue(), Set.of()), diagnostics));
        return out;
    }

    /**
     * Reads what each subject's renderer composes above the meshes it submits.
     *
     * <p>Keyed by the renderer's own simple name, first answer winning, because several subjects
     * share one renderer and it answers the same for all of them. A renderer that composes nothing
     * beyond the base is absent rather than empty.
     */
    private static @NotNull Map<String, RenderTransform> walkRenderers(
        @NotNull ToolingSession session, @NotNull Set<String> renderers) {

        Map<String, RenderTransform> out = new TreeMap<>();
        for (String renderer : renderers) {
            RenderTransform transform = RenderTransformWalk.read(session.cache(), renderer);
            if (transform != null) out.putIfAbsent(transform.renderer(), transform);
        }
        return out;
    }

    /**
     * Names every renderer whose {@code setupRotations} could not be read whole, so a subject this
     * places by nothing reads as a stated refusal rather than as a renderer that composes nothing.
     */
    private static void reportRefusedTransforms(
        @NotNull Map<String, RenderTransform> transforms, @NotNull Diagnostics diagnostics) {

        for (Map.Entry<String, RenderTransform> entry : transforms.entrySet())
            if (!entry.getValue().isReadable())
                diagnostics.info("%s.setupRotations %s - no transform",
                    entry.getKey(), entry.getValue().refusal().orElseThrow());
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
     *
     * <p>What is played is read off the walked poses' own play sites, which is where the table
     * itself carries the fact - a pose row's {@code clips} member names each clip its body applies.
     */
    private static void reportDeadClips(
        @NotNull List<KeyframeClip> clips, @NotNull Map<String, PoseOutcome> poses,
        @NotNull Diagnostics diagnostics) {

        Set<String> played = new LinkedHashSet<>();
        for (PoseOutcome outcome : poses.values())
            if (outcome instanceof PoseOutcome.Extracted extracted)
                for (PoseClipSite site : extracted.program().clipSites()) played.add(site.clip());
        Set<String> dead = new TreeSet<>();
        for (KeyframeClip clip : clips)
            if (!played.contains(clip.coordinate())) dead.add(clip.coordinate());
        if (!dead.isEmpty())
            diagnostics.info("emitted %d clip(s) no model plays: %s", dead.size(), String.join(", ", dead));
    }

}
