package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
     * The render-state figures the tick drives, which stay symbolic through the fold.
     *
     * <p>Elapsed age is what makes one frame differ from its neighbour at all; the stride pair is
     * what a gait adds, and vanilla steps the phase BY the amplitude once a tick rather than deriving
     * it from the clock, so the two are one schedule and a caller naming one without the other has
     * described no gait. Everything else an offline subject answers at rest.
     */
    private static final @NotNull Set<String> DRIVEN =
        Set.of("ageInTicks", "walkAnimationPos", "walkAnimationSpeed");

    /**
     * What separates a pose key from the frame it stands for, where one class poses more than one
     * way.
     *
     * <p>The reader splits a coordinate at its first {@code #} and keys the pose on what is left, so
     * a suffix written here arrives verbatim and needs nothing of the reader at all. It is the
     * character a derived mesh's own suffixes are spelled with for the same reason - it appears in
     * no Java identifier, so nothing it separates can be mistaken for part of a name.
     */
    private static final char SPLIT = '@';

    /** The members of the model table this reads, which are the reader's join and not this flow's. */
    private static final @NotNull String AGE = "age";

    private static final @NotNull String VARIANT = "variant";

    private static final @NotNull String OPTIONS = "options";

    private static final @NotNull String ADULT = "adult";

    private static final @NotNull String GEOMETRY = "geometry";

    private static final @NotNull String OVERLAYS = "overlays";

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
        @NotNull Set<String> renderers, @NotNull JsonTree models, @NotNull Path out) {

        Diagnostics diagnostics = session.diagnostics().child("pose");
        List<KeyframeClip> clips = KeyframeDefinitionParser.parseAll(session.cache(), diagnostics);
        Map<String, String> roster = rosterClasses(session, manifest, posing, diagnostics);
        Map<String, PoseOutcome> walked = walkModels(session, roster, rootBones, diagnostics);
        Map<String, RenderTransform> transforms = walkRenderers(session, renderers);

        // Resolved before anything is written, because the fold reads all three: what the walk left
        // is a program over the render state, and these are what that state answers at rest.
        Map<String, Float> defaults =
            InputDefaultResolver.resolve(session.cache(), InputDefaultResolver.namedBy(walked), diagnostics);
        Map<String, Map<String, String>> restingByModel = new LinkedHashMap<>();
        Map<String, Map<String, Float>> questionsByModel = new LinkedHashMap<>();
        Set<String> switched = InputDefaultResolver.constantsNamedBy(walked);
        Set<String> asked = InputDefaultResolver.questionsNamedBy(walked);
        for (Map.Entry<String, String> model : roster.entrySet()) {
            String renderState = PoseWalk.renderStateOf(session.cache(), model.getKey());
            if (renderState == null) continue;
            String name = ClassKit.simpleName(model.getKey());
            Map<String, String> resting =
                InputDefaultResolver.resolveConstants(session.cache(), renderState, switched);
            if (!resting.isEmpty()) restingByModel.put(name, resting);
            Map<String, Float> answers =
                InputDefaultResolver.resolveQuestions(session.cache(), renderState, asked);
            if (!answers.isEmpty()) questionsByModel.put(name, answers);
        }

        Map<String, PoseOutcome> poses =
            foldAll(walked, models, restingByModel, questionsByModel, defaults, diagnostics);
        requirePosersResolve(models, poses);

        JsonTree root = session.envelope("definitions-package listing order for clips; "
            + "model simple name for poses, and bone name within a pose");
        JsonTree clipsNode = root.child("clips");
        for (KeyframeClip clip : clips) clipsNode.put(clip.coordinate(), clipNode(clip));

        JsonTree posesNode = root.child("poses");
        PoseJson.all(poses).forEach(posesNode::put);

        // What a figure reads as before anything has happened to the subject, for the figures whose
        // own render state builds them at something other than nothing. Written at the root because
        // a figure is named by its bare field name, which is one keyspace across every model.
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
        //
        // Written under the ROW's key rather than the class's, which are the same string until a
        // class poses more than one way: the reader looks a default up by the key it read the row
        // under, so a class that split would otherwise leave its answers under a name no row carries.
        JsonTree restingNode = JsonTree.object();
        restingByModel.forEach((model, resting) -> {
            for (String key : rowsOf(poses, model)) {
                JsonTree perRow = JsonTree.object();
                resting.forEach(perRow::put);
                restingNode.put(key, perRow);
            }
        });
        JsonTree questionsNode = JsonTree.object();
        questionsByModel.forEach((model, answers) -> {
            for (String key : rowsOf(poses, model)) {
                JsonTree perRow = JsonTree.object();
                answers.forEach(perRow::put);
                questionsNode.put(key, perRow);
            }
        });
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

    /**
     * Resolves every walked pose against the frame the subjects reaching it stand in.
     *
     * <p>A row is folded against ONE frame, which is what keeps the result a graph: a node reached
     * down six paths has one binding and folds to one residual. So a class two subjects reach at two
     * frames has no single one, and it is either SPLIT - each body naming the key it takes, one row
     * per frame - or emitted exactly as walked and named in the log. Folding an illager against
     * another illager's arms draws a subject vanilla never draws and renders as though it were
     * deliberate; an unfolded row keeps reading the tables at render, so refusing costs bytes and
     * never correctness.
     *
     * <p><b>A frame is what the row can tell apart, not the resting map a subject carries.</b> Two
     * subjects whose states differ only where this pose never looks stand in one frame and fold to
     * one residual, which is the difference between three of the corpus's crowded classes folding
     * and none of them doing.
     *
     * <p><b>A split is available only where every site reaching the class is a body.</b> A body is
     * the one site a subject can name the poser of, so a class an overlay or an equipment layer also
     * reaches would want a key those sites have nowhere to carry - and a body naming several classes
     * at once cannot name a different key for one of them.
     *
     * <p>A row nothing reaches is folded against its model's own defaults, there being no subject to
     * answer for it.
     *
     * @param walked every model's pose as the walk left it
     * @param models the model table, read for what reaches each pose and written where one splits
     * @param restingByModel which constant each enum member rests holding, per model
     * @param questionsByModel what a question rests answering, per model
     * @param inputDefaults what each figure rests at, one keyspace across every model
     * @param diagnostics the scope a refusal is recorded against
     * @return the residual per row key, a split class answering under each key it was given
     */
    private static @NotNull Map<String, PoseOutcome> foldAll(
        @NotNull Map<String, PoseOutcome> walked, @NotNull JsonTree models,
        @NotNull Map<String, Map<String, String>> restingByModel,
        @NotNull Map<String, Map<String, Float>> questionsByModel,
        @NotNull Map<String, Float> inputDefaults, @NotNull Diagnostics diagnostics) {

        Map<String, Set<String>> bodies = bodyKeysOf(models);
        Map<String, Set<String>> elsewhere = otherKeysOf(models);
        Map<String, Map<Map<String, String>, Set<String>>> frames = framesOf(models, bodies, elsewhere);

        Map<String, PoseOutcome> out = new TreeMap<>();
        int folded = 0;
        for (Map.Entry<String, PoseOutcome> entry : walked.entrySet()) {
            String model = entry.getKey();
            if (!(entry.getValue() instanceof PoseOutcome.Extracted extracted)) {
                out.put(model, entry.getValue());
                continue;
            }

            Map<String, String> modelRest = restingByModel.getOrDefault(model, Map.of());
            Map<String, Float> modelAnswers = questionsByModel.getOrDefault(model, Map.of());
            // Grouped by the frame the row can TELL APART rather than by the raw resting maps. A
            // pose asks two questions of a resting state and asks them only of the members it names,
            // so two subjects disagreeing anywhere else are one frame - which is most of them.
            Map<Map<String, String>, Set<String>> reaching = frames.getOrDefault(model, Map.of());
            Map<Map<String, String>, Set<String>> distinct = new LinkedHashMap<>();
            Map<Map<String, String>, Map<String, String>> standIn = new LinkedHashMap<>();
            reaching.forEach((rest, subjects) -> {
                Map<String, String> frame = PoseFold.frameOf(extracted.program(), rest, modelRest);
                distinct.computeIfAbsent(frame, key -> new TreeSet<>()).addAll(subjects);
                standIn.putIfAbsent(frame, rest);
            });

            if (distinct.size() > 1) {
                Map<Map<String, String>, String> split =
                    splitKeys(model, distinct.keySet(), bodies, elsewhere);
                if (split.isEmpty()) {
                    List<String> spelled = new ArrayList<>();
                    distinct.forEach((frame, subjects) -> spelled.add(frame + " <- " + subjects));
                    diagnostics.info("%s is reached at %d resting frames and is emitted unfolded: %s",
                        model, distinct.size(), String.join("; ", spelled));
                    out.put(model, entry.getValue());
                    continue;
                }
                split.forEach((frame, key) -> {
                    distinct.get(frame).forEach(subject -> namePoser(models, subject, key));
                    out.put(key, new PoseOutcome.Extracted(PoseFold.fold(extracted.program(),
                        standIn.get(frame), modelRest, modelAnswers, inputDefaults, DRIVEN)));
                });
                diagnostics.info("%s poses %d ways and each body names the one it takes: %s",
                    model, split.size(), new TreeSet<>(split.values()));
                folded++;
                continue;
            }

            // Any of the raw maps behind the one frame folds to the same residual, every member the
            // pose names answering the same in all of them, so the first is taken as it stands.
            Map<String, String> subjectRest =
                reaching.isEmpty() ? Map.of() : reaching.keySet().iterator().next();
            out.put(model, new PoseOutcome.Extracted(PoseFold.fold(extracted.program(), subjectRest,
                modelRest, modelAnswers, inputDefaults, DRIVEN)));
            folded++;
        }
        diagnostics.info("folded %d of %d walked pose(s) against the frame their subjects rest in",
            folded, walked.size());
        return out;
    }

    /**
     * The key each frame's row is written under, or empty where the class cannot be split.
     *
     * <p>The suffix names the members the frames DISAGREE on and nothing else, so a key says what
     * makes its row a different pose rather than restating everything the subject rests at. A frame
     * answering none of them keeps the bare class name, which is what a subject with no {@code rest}
     * already resolves - so nothing is written into its row at all.
     *
     * @param model the pose class the frames reach
     * @param frames the distinct frames it is reached at
     * @param bodies each subject's body keys, read for whether a body can name this one alone
     * @param elsewhere each subject's other keys, read for whether anything else reaches it
     * @return frame to the key its row takes, empty when no split is available or none tells the
     *     frames apart
     */
    private static @NotNull Map<Map<String, String>, String> splitKeys(
        @NotNull String model, @NotNull Set<Map<String, String>> frames,
        @NotNull Map<String, Set<String>> bodies, @NotNull Map<String, Set<String>> elsewhere) {

        for (Set<String> keys : elsewhere.values())
            if (keys.contains(model)) return Map.of();
        for (Set<String> keys : bodies.values())
            if (keys.contains(model) && keys.size() > 1) return Map.of();

        Set<String> differing = disagreeing(frames);
        Map<Map<String, String>, String> out = new LinkedHashMap<>();
        for (Map<String, String> frame : frames) {
            StringBuilder suffix = new StringBuilder();
            for (String member : differing) {
                String held = frame.get(member);
                if (held == null) continue;
                if (!suffix.isEmpty()) suffix.append(',');
                suffix.append(member).append('=').append(held);
            }
            out.put(frame, suffix.isEmpty() ? model : model + SPLIT + suffix);
        }
        // A key that does not tell the frames apart is not a split: two rows under one name would
        // leave whichever was written second standing for both, silently.
        return Set.copyOf(out.values()).size() == frames.size() ? out : Map.of();
    }

    /**
     * The rows one model class answers for - its own where it poses one way, and the keys a split
     * gave it where it does not.
     *
     * @param poses the rows the table carries
     * @param model the model class's simple name
     * @return the keys, in the order the table carries them
     */
    private static @NotNull List<String> rowsOf(
        @NotNull Map<String, PoseOutcome> poses, @NotNull String model) {

        if (poses.containsKey(model)) return List.of(model);
        List<String> out = new ArrayList<>();
        for (String key : poses.keySet())
            if (key.startsWith(model + SPLIT)) out.add(key);
        return out;
    }

    /** The members the frames do not agree on, which is what a key has to name to tell them apart. */
    private static @NotNull Set<String> disagreeing(@NotNull Set<Map<String, String>> frames) {
        Set<String> named = new TreeSet<>();
        for (Map<String, String> frame : frames) named.addAll(frame.keySet());
        named.removeIf(member -> {
            Set<String> held = new HashSet<>();
            for (Map<String, String> frame : frames) held.add(frame.get(member));
            return held.size() == 1;
        });
        return named;
    }

    /**
     * Writes into one subject's row the pose key its body takes.
     *
     * <p>The bones node is rebuilt rather than added to, because the member order a row carries is
     * the resolver's own put chain and {@code pose} opens that node there.
     */
    private static void namePoser(
        @NotNull JsonTree models, @NotNull String subject, @NotNull String key) {

        JsonTree row = models.child(subject);
        JsonTree named = JsonTree.object().put("pose", key);
        row.find("bones").ifPresent(bones -> bones.members().forEach((member, held) -> {
            if (!"pose".equals(member)) named.put(member, held);
        }));
        row.put("bones", named);
    }

    /**
     * Refuses a subject that names a poser the table does not carry.
     *
     * <p>A missing key is SILENT at render: the reader answers the empty pose, whose refusal is
     * empty, so the mesh draws unposed and unstripped with nothing said. A coordinate the walk never
     * looked at is entitled to answer nothing - a worn shell, a saddle, a mesh derived under a
     * suffix - but a name a row DECLARES is a statement that there is a pose there.
     *
     * @param models the model table as it will be written
     * @param poses the rows the table carries
     * @throws ToolingException if a declared poser resolves to no row
     */
    private static void requirePosersResolve(
        @NotNull JsonTree models, @NotNull Map<String, PoseOutcome> poses) {

        models.members().forEach((entity, row) -> {
            requirePose(poses, entity, namedPoser(row));
            row.find("layers").ifPresent(list -> list.elements().toList().forEach(layer ->
                requirePose(poses, entity, layer.find("overlay").map(PoseFlow::namedPoser).orElse(null))));
        });
    }

    private static void requirePose(
        @NotNull Map<String, PoseOutcome> poses, @NotNull String entity, @Nullable String named) {

        if (named != null && !poses.containsKey(named))
            throw new ToolingException(
                "'%s' names pose '%s', which the pose table does not carry", entity, named);
    }

    /**
     * Which subjects reach which pose, and what each of them answers about itself at rest.
     *
     * @param models the model table, keyed by entity id
     * @param bodies each subject's body keys
     * @param elsewhere each subject's keys nothing of its own overrides
     * @return pose key to each distinct resting map reaching it, and the subjects carrying that map
     */
    private static @NotNull Map<String, Map<Map<String, String>, Set<String>>> framesOf(
        @NotNull JsonTree models, @NotNull Map<String, Set<String>> bodies,
        @NotNull Map<String, Set<String>> elsewhere) {

        Map<String, Map<Map<String, String>, Set<String>>> out = new LinkedHashMap<>();
        models.members().forEach((entity, row) -> {
            Map<String, String> rest = restOf(row);
            Set<String> reached = new LinkedHashSet<>(bodies.getOrDefault(entity, Set.of()));
            reached.addAll(elsewhere.getOrDefault(entity, Set.of()));
            for (String key : reached)
                out.computeIfAbsent(key, name -> new LinkedHashMap<>())
                    .computeIfAbsent(rest, name -> new LinkedHashSet<>())
                    .add(entity);
        });
        return out;
    }

    /** Which constant each enum member this subject rests holding, empty where it names none. */
    private static @NotNull Map<String, String> restOf(@NotNull JsonTree row) {
        JsonTree rest = row.find("rest").orElse(null);
        if (rest == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        rest.members().forEach((member, held) ->
            held.asString().ifPresent(value -> out.put(member, value)));
        return Map.copyOf(out);
    }

    /**
     * The pose keys each subject's BODY resolves - the sites the subject's own {@code bones.pose}
     * governs, and the only ones it can name a key for.
     *
     * <p>A body is the adult age option and, for a family with coats, each coat: those are what
     * {@code EntityIndexBuilder} resolves through the family's poser where it names one, and
     * everything else keys off the coordinate it draws. Reading the coordinate's head beside the
     * name instead credits a class the subject does not pose through at all - it had a zombified
     * piglin standing for {@code AdultPiglinModel} while its body poses as
     * {@code AdultZombifiedPiglinModel}, and that phantom is enough to make a class look reached at
     * two frames.
     *
     * @param models the model table, keyed by entity id
     * @return entity id to the keys its body takes, omitting a subject whose body resolves none
     */
    private static @NotNull Map<String, Set<String>> bodyKeysOf(@NotNull JsonTree models) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        models.members().forEach((entity, row) -> {
            String named = namedPoser(row);
            Set<String> keys = new LinkedHashSet<>();
            reaches(keys, named, row.findPath("axes", AGE, OPTIONS, ADULT)
                .flatMap(adult -> adult.findString(GEOMETRY)).orElse(null));
            row.findPath("axes", VARIANT, OPTIONS).ifPresent(options ->
                options.members().forEach((coat, chosen) ->
                    reaches(keys, named, chosen.findString(GEOMETRY).orElse(null))));
            if (!keys.isEmpty()) out.put(entity, keys);
        });
        return out;
    }

    /**
     * The pose keys each subject reaches that nothing of its own can override - its baby mesh, its
     * size and shape alternatives, every overlay pass and every equipment layer.
     *
     * <p>An equipment layer names its own poser and is here all the same: what it names is a key of
     * ITS mesh, and a split writes the body's key, so a class a layer reaches is one a body cannot
     * rename without leaving that layer resolving the old name.
     *
     * @param models the model table, keyed by entity id
     * @return entity id to the keys its other meshes take, omitting a subject that reaches none
     */
    private static @NotNull Map<String, Set<String>> otherKeysOf(@NotNull JsonTree models) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        models.members().forEach((entity, row) -> {
            Set<String> keys = new LinkedHashSet<>();
            row.find("axes").ifPresent(axes -> axes.members().forEach((axis, held) ->
                held.find(OPTIONS).ifPresent(options -> options.members().forEach((option, chosen) -> {
                    if (!isBody(axis, option))
                        reaches(keys, null, chosen.findString(GEOMETRY).orElse(null));
                    chosen.find(OVERLAYS).ifPresent(list -> list.elements().toList().forEach(
                        overlay -> reaches(keys, null, overlay.findString(GEOMETRY).orElse(null))));
                }))));

            row.find(OVERLAYS).ifPresent(list -> list.elements().toList().forEach(overlay -> {
                reaches(keys, null, overlay.findString(GEOMETRY).orElse(null));
                overlay.find("baby").ifPresent(baby ->
                    reaches(keys, null, baby.findString(GEOMETRY).orElse(null)));
            }));

            row.find("layers").ifPresent(list -> list.elements().toList().forEach(layer ->
                layer.find("overlay").ifPresent(overlay -> {
                    reaches(keys, namedPoser(overlay), overlay.findString(GEOMETRY).orElse(null));
                    overlay.find("alternate").ifPresent(alternate ->
                        reaches(keys, null, alternate.findString(GEOMETRY).orElse(null)));
                })));
            if (!keys.isEmpty()) out.put(entity, keys);
        });
        return out;
    }

    /** Whether an axis option is the subject's body, which is the site a poser is named for. */
    private static boolean isBody(@NotNull String axis, @NotNull String option) {
        return VARIANT.equals(axis) || (AGE.equals(axis) && ADULT.equals(option));
    }

    /** The poser a row or an equipment overlay names, or {@code null} where it names none. */
    private static @Nullable String namedPoser(@NotNull JsonTree node) {
        return node.find("bones").flatMap(bones -> bones.findString("pose")).orElse(null);
    }

    /**
     * One site's key - the poser named for it, or the class heading the coordinate it draws.
     *
     * <p>A site is a mesh, so a coordinate is what says there is one: a row naming a poser for a
     * mesh it does not carry names a pose nothing takes, and the reader passes over it the same way.
     */
    private static void reaches(
        @NotNull Set<String> keys, @Nullable String named, @Nullable String coordinate) {

        if (coordinate == null) return;
        String key = named != null ? named : poseHead(coordinate);
        if (key != null && !key.isEmpty()) keys.add(key);
    }

    /** The class a geometry coordinate is headed with, which is what the reader keys a pose by. */
    private static @Nullable String poseHead(@Nullable String coordinate) {
        if (coordinate == null) return null;
        int member = coordinate.indexOf('#');
        return member < 0 ? coordinate : coordinate.substring(0, member);
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
