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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    public static @NotNull Emitted emit(
        @NotNull ToolingSession session, @NotNull GeometryManifest manifest,
        @NotNull Map<String, Set<String>> rootBones, @NotNull Set<String> posing,
        @NotNull Set<String> renderers, @NotNull JsonTree models, @NotNull Path out) {

        Diagnostics diagnostics = session.diagnostics().child("pose");
        List<KeyframeClip> clips = KeyframeDefinitionParser.parseAll(session.cache(), diagnostics);
        Map<String, String> roster = rosterClasses(session, manifest, posing, diagnostics);
        Map<String, PoseOutcome> walked = walkModels(session, roster, rootBones, diagnostics);
        Map<String, RenderTransform> transforms = walkRenderers(session, renderers, models);

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
        mergeRestingUndrawn(models, poses, diagnostics);
        transforms = foldTransforms(transforms, models, defaults, diagnostics);

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
        // Which renderers actually compose something, for the one caller that has to know: a shift
        // baked into a mesh and a transform composed above it are two spellings of one
        // setupRotations, and only here is it still known that a subject reaches both.
        Set<String> composing = new LinkedHashSet<>();
        transforms.values().stream()
            .filter(transform -> !transform.steps().isEmpty())
            .forEach(transform -> composing.add(transform.renderer()));

        Map<String, Float> facings = new LinkedHashMap<>();
        transforms.values()
            .stream()
            .filter(transform -> transform.facingYaw() != 0f)
            .forEach(transform -> facings.put(transform.renderer(), transform.facingYaw()));

        return new Emitted(Collections.unmodifiableSet(composing),
            Collections.unmodifiableMap(facings), rigidModels(poses, rootBones));
    }

    /**
     * What the pose flow settled that the mesh surgeries below it need.
     *
     * @param composing the simple names of renderers that compose steps above their meshes
     * @param facings the constant facing turn each renderer folds into its delegated body rotation,
     *     in degrees, by renderer simple name - absent for a renderer that folds none
     * @param rigid the models whose pose a turn about y can NOT be moved past, by simple name -
     *     stated as the failures so a model nobody posed is absent and therefore safe, which a set of
     *     the passes could not say apart from a model whose walk found nothing; see {@link #rigidModels}
     */
    public record Emitted(
        @NotNull Set<String> composing,
        @NotNull Map<String, Float> facings,
        @NotNull Set<String> rigid
    ) {}

    /**
     * The models a constant turn about y can NOT be moved past, so no facing may be baked into their
     * mesh.
     *
     * <p>A facing baked into a cube sits INSIDE the bone's own rotation, where the render applies it
     * outside every one of them. The two agree only where the turn commutes with what the pose writes
     * and with where the pose puts the bone: rotations about y commute with each other and with
     * nothing else, and a pivot the turn leaves alone is one standing on the axis it turns about.
     *
     * <p>So a model is rigid when some ROOT bone's pose writes a rotation off the y axis, or moves a
     * root pivot off it. A child bone is not asked: it rides its parent, and the turn reaches it
     * through the chain rather than through its own slot. A container is asked on the same terms,
     * being a parent above every root.
     *
     * @param poses every model's outcome
     * @param rootBones the bones each model's mesh names at top level
     * @return the models whose pose a turn would not survive
     */
    private static @NotNull Set<String> rigidModels(
        @NotNull Map<String, PoseOutcome> poses, @NotNull Map<String, Set<String>> rootBones) {

        Set<String> out = new LinkedHashSet<>();
        poses.forEach((model, outcome) -> {
            if (!(outcome instanceof PoseOutcome.Extracted extracted)) return;
            PoseProgram program = extracted.program();
            Set<String> roots = rootBones.getOrDefault(model, Set.of());
            for (Map.Entry<String, Map<PoseChannel, PoseExpr>> bone : program.bones().entrySet())
                if (roots.contains(bone.getKey()) && !passesTurn(bone.getValue())) {
                    out.add(model);
                    return;
                }
            for (Map<PoseChannel, PoseExpr> step : program.container())
                if (!passesTurn(step)) {
                    out.add(model);
                    return;
                }
        });
        return Collections.unmodifiableSet(out);
    }

    /**
     * Whether a turn about y survives one written channel map - no rotation off the y axis, and no
     * displacement off it either. A channel written to a constant zero displaces nothing, which is
     * what a pose assigning a rest position writes.
     */
    private static boolean passesTurn(@NotNull Map<PoseChannel, PoseExpr> written) {
        for (Map.Entry<PoseChannel, PoseExpr> channel : written.entrySet())
            switch (channel.getKey()) {
                case X_ROT, Z_ROT -> {
                    return false;
                }
                case X, Z -> {
                    if (channel.getValue().constantValue().orElse(Double.NaN) != 0d) return false;
                }
                default -> { }
            }
        return true;
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
     * Resolves every renderer's steps against the frame the subjects it draws stand in.
     *
     * <p>A transform is a program over the render state exactly as a pose is - vanilla's
     * {@code setupRotations} reads the same fields - so it folds by the same rule and through the
     * same fold, the steps travelling as a program with no bones. Leaving it unfolded where the
     * poses are folded is what would part the two: three fish read {@code isInWater}, which is a fact
     * about a subject holding still, and a reader that answered it nothing would swim each of them
     * onto its side.
     *
     * <p>The subject's own resting map is the whole frame here. A renderer has no model class and so
     * no resting defaults of its own, and the fold needs none: the one constant question a
     * {@code setupRotations} body asks - which direction the subjects it draws rest attached at -
     * is settled at the walk, because what it decides is which steps exist rather than what a
     * channel holds, so what reaches this fold is a program over the state's float and boolean
     * fields alone.
     *
     * @param transforms what each renderer composes, refusals included and passed through
     * @param models the model table, read for which subjects each renderer draws and what they rest at
     * @param inputDefaults what each figure rests at, one keyspace across every model
     * @param diagnostics the scope a refusal is recorded against
     * @return the residual per renderer, in the order the walk produced them
     */
    private static @NotNull Map<String, RenderTransform> foldTransforms(
        @NotNull Map<String, RenderTransform> transforms, @NotNull JsonTree models,
        @NotNull Map<String, Float> inputDefaults, @NotNull Diagnostics diagnostics) {

        Map<String, Map<Map<String, String>, Set<String>>> drawn = new LinkedHashMap<>();
        models.members().forEach((entity, row) -> row.findString("renderer").ifPresent(renderer ->
            drawn.computeIfAbsent(ClassKit.simpleName(renderer), name -> new LinkedHashMap<>())
                .computeIfAbsent(restOf(row), name -> new LinkedHashSet<>()).add(entity)));

        Map<String, RenderTransform> out = new TreeMap<>();
        for (Map.Entry<String, RenderTransform> entry : transforms.entrySet()) {
            String renderer = entry.getKey();
            RenderTransform transform = entry.getValue();
            if (!transform.isReadable()) {
                out.put(renderer, transform);
                continue;
            }

            PoseProgram program = new PoseProgram(renderer, transform.steps(), Map.of(), List.of());
            Map<Map<String, String>, Set<String>> reaching = drawn.getOrDefault(renderer, Map.of());
            Map<Map<String, String>, Set<String>> distinct = new LinkedHashMap<>();
            reaching.forEach((rest, subjects) ->
                distinct.computeIfAbsent(PoseFold.frameOf(program, rest, Map.of()),
                    frame -> new TreeSet<>()).addAll(subjects));
            if (distinct.size() > 1) {
                List<String> spelled = new ArrayList<>();
                distinct.forEach((frame, subjects) -> spelled.add(frame + " <- " + subjects));
                diagnostics.info(
                    "%s.setupRotations draws %d resting frames and is emitted unfolded: %s",
                    renderer, distinct.size(), String.join("; ", spelled));
                out.put(renderer, transform);
                continue;
            }

            Map<String, String> subjectRest =
                reaching.isEmpty() ? Map.of() : reaching.keySet().iterator().next();
            out.put(renderer, RenderTransform.of(renderer, transform.facingYaw(), PoseFold.fold(
                program, subjectRest, Map.of(), Map.of(), inputDefaults, DRIVEN).container()));
        }
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
            row.find("equipment").ifPresent(list -> list.elements().toList().forEach(item ->
                requirePose(poses, entity, namedPoser(item))));
        });
    }

    private static void requirePose(
        @NotNull Map<String, PoseOutcome> poses, @NotNull String entity, @Nullable String named) {

        if (named != null && !poses.containsKey(named))
            throw new ToolingException(
                "'%s' names pose '%s', which the pose table does not carry", entity, named);
    }

    /**
     * Merges what each site's pose rests not drawing into the model table's strip lists.
     *
     * <p>Which bones a subject rests without is a fact the fold already settled - every flag channel
     * in the corpus folds to a literal - so it is resolved here and shipped on the {@code undrawn}
     * lists rather than left as arithmetic for a render to evaluate. A site's list is its never-drawn
     * bones joined with what the pose its mesh takes rests hidden, and the join is per site because
     * the two halves key differently: a never-drawn bone is the subject's own fact - the illusioner
     * re-enables the hat the other illagers never draw - where a resting flag is the pose row's.
     *
     * <p>The sites carrying one are exactly the sites the reader strips: the family's bones node,
     * serving the body and every coat; an equipment layer's, over its own mesh; the baby age option,
     * which takes its own class's rest alone; and each size option naming a mesh, which joins the
     * family's never-drawn bones with its own class's rest.
     *
     * @param models the model table, rewritten in place ahead of being written
     * @param poses the rows the pose table carries
     * @param diagnostics the scope the merge is recorded against
     * @throws ToolingException if one family's bodies rest apart on the members their class names
     */
    private static void mergeRestingUndrawn(
        @NotNull JsonTree models, @NotNull Map<String, PoseOutcome> poses,
        @NotNull Diagnostics diagnostics) {

        Map<String, List<String>> resting = restingUndrawn(poses);
        int[] sites = {0};
        models.members().forEach((entity, row) -> {
            List<String> never = undrawnHeld(row.find("bones").orElse(null));

            // One family list serves the body and every coat, so two bodies resting apart on the
            // members their shared class names is a shape the table cannot carry.
            Set<List<String>> bodySeeds = new LinkedHashSet<>();
            for (String key : bodyKeys(row)) bodySeeds.add(resting.getOrDefault(key, List.of()));
            if (bodySeeds.size() > 1)
                throw new ToolingException(
                    "'%s' bodies rest apart (%s), which one family undrawn list cannot say",
                    entity, bodySeeds);
            List<String> bodySeed = bodySeeds.isEmpty() ? List.of() : bodySeeds.iterator().next();
            if (writeUndrawn(row, merged(never, bodySeed), OVERLAYS, "axes")) sites[0]++;

            row.findPath("axes", AGE, OPTIONS, "baby").ifPresent(baby ->
                baby.findString(GEOMETRY).ifPresent(coordinate -> {
                    List<String> seed = resting.getOrDefault(poseHead(coordinate), List.of());
                    if (!seed.isEmpty()) {
                        baby.putStrings("undrawn", seed.toArray(String[]::new));
                        sites[0]++;
                    }
                }));

            row.findPath("axes", "size", OPTIONS).ifPresent(options ->
                options.members().forEach((option, chosen) ->
                    chosen.findString(GEOMETRY).ifPresent(coordinate -> {
                        List<String> undrawn =
                            merged(never, resting.getOrDefault(poseHead(coordinate), List.of()));
                        if (!undrawn.isEmpty()) {
                            chosen.putStrings("undrawn", undrawn.toArray(String[]::new));
                            sites[0]++;
                        }
                    })));

            row.find("equipment").ifPresent(list -> list.elements().toList().forEach(item ->
                item.findString(GEOMETRY).ifPresent(coordinate -> {
                    String named = namedPoser(item);
                    String key = named != null ? named : poseHead(coordinate);
                    List<String> undrawn = merged(undrawnHeld(item.find("bones").orElse(null)),
                        resting.getOrDefault(key, List.of()));
                    if (writeUndrawn(item, undrawn, "layer_type")) sites[0]++;
                })));
        });
        diagnostics.info("resting-undrawn merged into %d site(s)", sites[0]);
    }

    /**
     * The bones each row's pose rests not drawing, refusing what the resolved form cannot carry.
     *
     * <p>Nothing at render reads a flag channel - the undrawn lists are the whole answer - so a flag
     * the fold could not settle to a literal has nowhere to surface but a wrong render, and a resting
     * {@code skip_draw} states a shape the lists cannot say: cubes skipped while the bone's children
     * still draw. Both refuse the flow instead, which is where a version bump that grows either shape
     * gets caught.
     *
     * @param poses the rows the pose table carries
     * @return row key to the bone names it rests not drawing, sorted, rows resting whole omitted
     * @throws ToolingException if a flag channel is not a literal, a container step writes one, or a
     *     row rests skipping a bone's cubes
     */
    private static @NotNull Map<String, List<String>> restingUndrawn(
        @NotNull Map<String, PoseOutcome> poses) {

        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, PoseOutcome> entry : poses.entrySet()) {
            if (!(entry.getValue() instanceof PoseOutcome.Extracted extracted)) continue;
            String row = entry.getKey();
            for (Map<PoseChannel, PoseExpr> step : extracted.program().container())
                for (PoseChannel channel : step.keySet())
                    if (channel.isFlag())
                        throw new ToolingException(
                            "'%s' writes '%s' on its container, which reaches no bone below it",
                            row, channel.token());
            Set<String> undrawn = new TreeSet<>();
            extracted.program().bones().forEach((bone, channels) -> {
                PoseExpr visible = channels.get(PoseChannel.VISIBLE);
                if (visible != null && restingFlag(row, bone, PoseChannel.VISIBLE, visible) == 0d)
                    undrawn.add(bone);
                PoseExpr skips = channels.get(PoseChannel.SKIP_DRAW);
                if (skips != null && restingFlag(row, bone, PoseChannel.SKIP_DRAW, skips) != 0d)
                    throw new ToolingException(
                        "'%s' rests '%s' skipping its own cubes, which an undrawn list cannot say",
                        row, bone);
            });
            if (!undrawn.isEmpty()) out.put(row, List.copyOf(undrawn));
        }
        return out;
    }

    /** A flag channel's one resting value, which is a literal or a refusal. */
    private static double restingFlag(
        @NotNull String row, @NotNull String bone, @NotNull PoseChannel channel,
        @NotNull PoseExpr expression) {

        return expression.constantValue().orElseThrow(() -> new ToolingException(
            "'%s' poses '%s.%s' by more than a literal, and nothing at render reads a flag",
            row, bone, channel.token()));
    }

    /** The undrawn list a bones node already carries, empty where there is no node or no member. */
    private static @NotNull List<String> undrawnHeld(@Nullable JsonTree bones) {
        if (bones == null) return List.of();
        JsonTree held = bones.find("undrawn").orElse(null);
        if (held == null) return List.of();
        List<String> out = new ArrayList<>();
        held.elements().toList().forEach(entry -> entry.asString().ifPresent(out::add));
        return out;
    }

    /** One list, the never-drawn bones first and the resting seed after, each name once. */
    private static @NotNull List<String> merged(
        @NotNull List<String> never, @NotNull List<String> seed) {

        if (seed.isEmpty()) return never;
        LinkedHashSet<String> out = new LinkedHashSet<>(never);
        out.addAll(seed);
        return List.copyOf(out);
    }

    /**
     * Writes one site's {@code undrawn} list into its {@code bones} node, opening the node where the
     * resolver left none.
     *
     * <p>The node is rebuilt rather than added to, for the reason {@link #namePoser} rebuilds it: the
     * member order a node carries is the resolver's own put chain, and {@code undrawn} sits between
     * {@code pose} and {@code toggles}. A node opened here anchors where the resolver would have put
     * one, so a table reads the same whichever of the two wrote the member.
     *
     * @param holder the family row or equipment overlay carrying the node
     * @param undrawn the bones the site rests not drawing
     * @param anchors the members a created node is placed ahead of, first present winning
     * @return whether anything was written
     */
    private static boolean writeUndrawn(
        @NotNull JsonTree holder, @NotNull List<String> undrawn, @NotNull String... anchors) {

        if (undrawn.isEmpty()) return false;
        JsonTree held = holder.find("bones").orElse(null);
        JsonTree rebuilt = JsonTree.object();
        if (held != null) held.findString("pose").ifPresent(pose -> rebuilt.put("pose", pose));
        rebuilt.putStrings("undrawn", undrawn.toArray(String[]::new));
        if (held != null) held.members().forEach((member, value) -> {
            if (!"pose".equals(member) && !"undrawn".equals(member)) rebuilt.put(member, value);
        });
        if (held != null) holder.put("bones", rebuilt);
        else insertMember(holder, "bones", rebuilt, anchors);
        return true;
    }

    /** Places a new member ahead of the first anchor the holder carries, appending past them all. */
    private static void insertMember(
        @NotNull JsonTree holder, @NotNull String key, @NotNull JsonTree value,
        @NotNull String... anchors) {

        List<String> held = holder.keys().toList();
        Map<String, JsonTree> members = new LinkedHashMap<>();
        for (String member : held) members.put(member, holder.find(member).orElseThrow());
        Set<String> before = Set.of(anchors);
        holder.clear();
        boolean placed = false;
        for (String member : held) {
            if (!placed && before.contains(member)) {
                holder.put(key, value);
                placed = true;
            }
            holder.put(member, members.get(member));
        }
        if (!placed) holder.put(key, value);
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
            Set<String> keys = bodyKeys(row);
            if (!keys.isEmpty()) out.put(entity, keys);
        });
        return out;
    }

    /** The pose keys one subject's body resolves, by the rule {@link #bodyKeysOf} states. */
    private static @NotNull Set<String> bodyKeys(@NotNull JsonTree row) {
        String named = namedPoser(row);
        Set<String> keys = new LinkedHashSet<>();
        reaches(keys, named, row.findPath("axes", AGE, OPTIONS, ADULT)
            .flatMap(adult -> adult.findString(GEOMETRY)).orElse(null));
        row.findPath("axes", VARIANT, OPTIONS).ifPresent(options ->
            options.members().forEach((coat, chosen) ->
                reaches(keys, named, chosen.findString(GEOMETRY).orElse(null))));
        return keys;
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

            row.find("equipment").ifPresent(list -> list.elements().toList().forEach(item ->
                reaches(keys, namedPoser(item), item.findString(GEOMETRY).orElse(null))));
            row.find("armor").ifPresent(armor -> {
                reaches(keys, null, armor.findString(GEOMETRY).orElse(null));
                armor.find("alternate").ifPresent(alternate ->
                    reaches(keys, null, alternate.findString(GEOMETRY).orElse(null)));
            });
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
        @NotNull ToolingSession session, @NotNull Set<String> renderers, @NotNull JsonTree models) {

        Map<String, RenderTransform> out = new TreeMap<>();
        for (String renderer : renderers) {
            String name = ClassKit.simpleName(renderer);
            RenderTransform transform = RenderTransformWalk.read(session.cache(), renderer,
                (state, member) -> restingConstant(session, models, name, state, member));
            if (transform != null) out.putIfAbsent(transform.renderer(), transform);
        }
        return out;
    }

    /**
     * Which constant a render-state member rests holding, for the subjects one renderer draws.
     *
     * <p>The subject's own resting map answers first and the state's constructor answers the rest,
     * the precedence the pose fold takes - and every subject the renderer draws must land on one
     * constant, because the row answers for all of them. A disagreement, or a member neither
     * names, answers nothing and the walk refuses.
     *
     * @param session the live session
     * @param models the model table, read for which subjects the renderer draws and their rests
     * @param renderer the renderer's simple name, which the model table keys a subject's row by
     * @param state the render-state class the walked body reads its members of, by internal name
     * @param member the enum member being read
     * @return the one constant every drawn subject rests holding, or empty
     */
    private static @NotNull Optional<String> restingConstant(
        @NotNull ToolingSession session, @NotNull JsonTree models, @NotNull String renderer,
        @NotNull String state, @NotNull String member) {

        String constructed = InputDefaultResolver
            .resolveConstants(session.cache(), state, Set.of(member)).get(member);
        Set<String> answers = new LinkedHashSet<>();
        models.members().forEach((entity, row) -> row.findString("renderer").ifPresent(named -> {
            if (ClassKit.simpleName(named).equals(renderer))
                answers.add(restOf(row).getOrDefault(member, constructed));
        }));
        if (answers.isEmpty()) return Optional.ofNullable(constructed);
        return answers.size() == 1 ? Optional.ofNullable(answers.iterator().next()) : Optional.empty();
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
