package lib.minecraft.renderer.bake.pose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.engine.pose.ClipDrive;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseEvaluator;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseWidth;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The authored clips a model plays, evaluated onto its bones.
 *
 * <p>Five things are worth pinning. The linear curve has to be JOML's OWN arithmetic, because
 * vanilla reaches it through {@code Vector3fc.lerp} and JOML's {@code fma} is not the intrinsic one
 * - a mirror written from the method's name rather than its body is wrong at the last bit on every
 * interpolated frame. A state-driven clip has to contribute nothing, because that is what a subject
 * standing still gets and it is what the breeze's own reference says vanilla does. A walk-driven one
 * has to contribute even when nothing walks, because that is where the corpus puts the terms that
 * keep a clip running at rest. A clip has to reach the bone's DESCENDANTS through the scale it
 * writes, which is the whole reason that scale is not the uniform factor beside it. And what each
 * play site displaces apart has to add up to what the sites displace together, bit for bit, because
 * the two forms keep separate bodies and only the corpus says they still agree.
 */
@DisplayName("the authored clips a model plays")
class ClipPlayerTest {

    /** The universal standing row, which is what an undriven site's clip plays under. */
    private static final @NotNull PoseStyle IDLE_ROW =
        StyleCatalog.BIND_ONLY.resolve(PoseStyle.IDLE, AppearanceOptions.defaults()::applies, "minecraft:test");

    private static ConcurrentMap<String, Entity> entities;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
    }

    @Test
    @DisplayName("the linear curve is JOML's own lerp, at the bit")
    void theLinearCurveMirrorsJoml() {
        // JOML is on this classpath and on no other, which is what lets the mirror be held to the
        // real thing rather than to a reading of it. Its `lerp` is `org.joml.Math.fma(b - a, t, a)`,
        // and that routes to java.lang.Math.fma ONLY when `joml.useMathFma` is set - the property
        // defaults to false, so what runs is the written-out form. Asserted across a spread of
        // instants because the two groupings agree at the ends and part in the middle.
        float from = RAMP_FROM;
        float to = RAMP_TO;
        for (int millis = 0; millis <= 1000; millis += 31) {
            // The progress is derived the way the evaluator derives it rather than from the position
            // handed in, because a play site truncates its instant to whole milliseconds and the
            // product that reaches that truncation is itself a float - `0.217 * 50 * 20` lands just
            // under 217 and floors to 216. That is real and separately visible; comparing the curve
            // against a progress the evaluator never used would only hide it behind this assertion.
            float at = millis / 1000f;
            float progress = (long) (at * WALK_MILLIS_PER_POSITION * WALK_RATE) / 1000f;
            Vector3f expected = new Vector3f(from, from, from)
                .lerp(new Vector3f(to, to, to), progress, new Vector3f());

            float measured = displacement(at);
            assertEquals(expected.x(), measured, 0f,
                "the linear curve at " + progress + " is JOML's own number, not one a rewrite lands on");
        }
    }

    @Test
    @DisplayName("a clip reaching a bone's own scale reaches everything under it too")
    void aScaledBoneCarriesItsSubtree() {
        // The reason a clip's scale rides the pose scale and not the rest factor beside it. That one
        // is every scale above and at the bone, which the tooling already flattened onto the mesh, so
        // it is applied to a cube's own operands and must not propagate; the pose scale is the field
        // a clip displaces over that rest, and has to, the way vanilla's PoseStack.scale does.
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", new EntityMesh.Bone());
        mesh.getBones().put("head", child("body"));

        EntityMesh posed = PosePlayer.posed(scaling("body"), mesh, IDLE_ROW,
            StyleCatalog.BIND_ONLY.periodTicks(), 0);

        assertTrue(posed.getBones().get("body").isPoseScaled(), "the bone the clip names is scaled");
        assertFalse(posed.getBones().get("head").isPoseScaled(),
            "and its child carries no scale of its own - it inherits one through the chain");
        assertEquals(1f, posed.getBones().get("body").getScale(), 0f,
            "the rest factor beside it is untouched, a clip scaling the bone over its rest alone");
    }

    @Test
    @DisplayName("a clip scaling a root the mesh does not declare scales the container, and so the whole mesh")
    void aScaledRootCarriesTheWholeMesh() {
        // Vanilla's lookup answers `root` with the model's own root where no part takes the name,
        // and offsetScale adds onto a root reset to one. So the displacement rides the step every
        // top-level bone hangs from, as one plus the displacement, and the chain carries it to all
        // of them - no bone that draws is scaled itself, and no uniform factor moves.
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", new EntityMesh.Bone());
        mesh.getBones().put("head", child("body"));
        mesh.getBones().put("tail", new EntityMesh.Bone());

        EntityMesh posed = PosePlayer.posed(scaling("root"), mesh, IDLE_ROW,
            StyleCatalog.BIND_ONLY.periodTicks(), 0);

        List<String> names = List.copyOf(posed.getBones().keySet());
        assertEquals(mesh.getBones().size() + 1, names.size(), "the mesh gains one step");
        EntityMesh.Bone step = posed.getBones().get(names.getLast());
        assertTrue(step.getCubes().isEmpty(), "which draws nothing of its own");
        assertEquals(1f, step.getPoseScale().x(), 0f, "and stands at one where the clip keys nothing");
        assertEquals(1f, step.getPoseScale().y(), 0f, "on either axis");
        assertEquals(1.5f, step.getPoseScale().z(), 0f, "and at one plus the displacement where it does");
        assertEquals(1f, step.getScale(), 0f, "its own uniform factor untouched");
        assertEquals(names.getLast(), posed.getBones().get("body").getParent(), "the body hangs from it");
        assertEquals(names.getLast(), posed.getBones().get("tail").getParent(), "and so does every other root");
        assertEquals("body", posed.getBones().get("head").getParent(), "while a child keeps its parent");
        for (String name : mesh.getBones().keySet()) {
            assertFalse(posed.getBones().get(name).isPoseScaled(), name + " carries no scale of its own");
            assertEquals(1f, posed.getBones().get(name).getScale(), 0f, name + " keeps its uniform factor");
        }
    }

    @Test
    @DisplayName("a state-driven clip contributes nothing while its own gate answers zero")
    void aStateDrivenClipDoesNotPlay() {
        // Most of the corpus's play sites are state-driven and the breeze's six are among them, so
        // it is the subject that says what a gate decides. Asked of the GATE rather than through a
        // render, because which member a group rests at is the roster's business and this is not:
        // what is pinned here is that a site whose state answers zero displaces nothing, whatever
        // put that zero there, and that the same site displaces something once it answers one.
        Entity breeze = subject("minecraft:breeze");
        assertFalse(breeze.pose().clips().isEmpty(), "a breeze plays clips");
        assertTrue(breeze.pose().clips().stream().allMatch(clip -> clip.drive() == ClipDrive.SELECT),
            "and every one of them is state-driven");

        assertTrue(ClipPlayer.deltas(breeze.pose(), breeze.model(), field -> 0d).isEmpty(),
            "a frame answering zero for every state plays none of the six");

        // The whirl its own tick starts unconditionally, which is the one a default render selects.
        assertFalse(ClipPlayer.deltas(breeze.pose(), breeze.model(),
                field -> "idle".equals(field) ? 1d : 0d).isEmpty(),
            "and the one state a frame answers is the one that displaces a bone");
    }

    @Test
    @DisplayName("a walk-driven clip runs on a subject that is not walking")
    void aWalkDrivenClipRunsAtRest() {
        // The nautilus is where the corpus puts the terms that make this true: its play site carries
        // `walkAnimationPos + ageInTicks / 5` for the instant and `walkAnimationSpeed + 0.2` for the
        // amplitude, so the age term runs the clock and the floor keeps the amplitude off zero. A
        // reading that gated a walk-driven clip on something walking would freeze it.
        Entity nautilus = subject("minecraft:nautilus");
        PoseStyle idle = nautilus.styles()
            .resolve(PoseStyle.IDLE, AppearanceOptions.defaults()::applies, "minecraft:nautilus");
        EntityMesh still = PosePlayer.posed(nautilus, idle, nautilus.styles().periodTicks(), 0).model();
        EntityMesh later = PosePlayer.posed(nautilus, idle, nautilus.styles().periodTicks(), 9).model();

        assertNotEqualMeshes(still, later);
    }

    @Test
    @DisplayName("a site nothing drives holds its clip at the first instant")
    void anUndrivenSiteHoldsAtItsFirstInstant() {
        // Vanilla's own `apply(0L, 1.0f)`: the clip contributes its first keyframe whole, at full
        // amplitude, and the frame is never consulted - there is no time axis to read from it.
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", new EntityMesh.Bone());

        Map<PoseChannel, Float> written = ClipPlayer.deltas(
            ramping(ClipDrive.NONE, Concurrent.newUnmodifiableList()), mesh,
            PoseEvaluator.AT_REST).bones().get("body");

        assertNotNull(written, "an undriven site still displaces the bone it names");
        assertEquals(RAMP_FROM, written.get(PoseChannel.X_ROT), 0f,
            "and what it contributes is the first keyframe, whole");
    }

    @Test
    @DisplayName("every target names the channel triple it lands on, axis for axis")
    void everyTargetAxisNamesItsChannel() {
        // The three channels a target is declared with are mutually assignable, and the merge
        // below reads whichever one it is handed without asking what it accumulates - so a pair
        // swapped in the declaration, or in the switch that reads them out, sums a rotation into
        // a scale on every frame and compiles clean. Nine slots, stated here rather than left to
        // whichever of them a fixture happens to discriminate.
        assertEquals(PoseChannel.X, PoseChannel.Kind.POSITION.channel(0));
        assertEquals(PoseChannel.Y, PoseChannel.Kind.POSITION.channel(1));
        assertEquals(PoseChannel.Z, PoseChannel.Kind.POSITION.channel(2));

        assertEquals(PoseChannel.X_ROT, PoseChannel.Kind.ROTATION.channel(0));
        assertEquals(PoseChannel.Y_ROT, PoseChannel.Kind.ROTATION.channel(1));
        assertEquals(PoseChannel.Z_ROT, PoseChannel.Kind.ROTATION.channel(2));

        assertEquals(PoseChannel.X_SCALE, PoseChannel.Kind.SCALE.channel(0));
        assertEquals(PoseChannel.Y_SCALE, PoseChannel.Kind.SCALE.channel(1));
        assertEquals(PoseChannel.Z_SCALE, PoseChannel.Kind.SCALE.channel(2));

        for (PoseChannel.Kind target : PoseChannel.Kind.values()) {
            assertThrows(IllegalArgumentException.class, () -> target.channel(3),
                target + " has three axes, so a fourth is an error rather than a wrap");
            assertThrows(IllegalArgumentException.class, () -> target.channel(-1),
                target + " has three axes, so none below zero reads one");
        }
    }

    @Test
    @DisplayName("each target's three channels accumulate the one thing that target names")
    void eachTargetKeepsToOneKind() {
        // The stronger statement behind the slot pins: a target lands on one kind throughout, so
        // a swap ACROSS two targets is caught here even where both slots hold the same axis.
        for (PoseChannel.Kind target : PoseChannel.Kind.values()) {
            PoseChannel.Kind kind = target.channel(0).kind();
            for (int axis = 0; axis < 3; axis++)
                assertEquals(kind, target.channel(axis).kind(),
                    () -> target + " displaces one kind of member, whichever of its axes is read");
        }

        assertEquals(PoseChannel.Kind.POSITION, PoseChannel.Kind.POSITION.channel(0).kind());
        assertEquals(PoseChannel.Kind.ROTATION, PoseChannel.Kind.ROTATION.channel(0).kind());
        assertEquals(PoseChannel.Kind.SCALE, PoseChannel.Kind.SCALE.channel(0).kind());
    }

    @Test
    @DisplayName("what each play site displaces adds up to what the sites displace together, bit for bit")
    void deltasAreTheSumOfTheirSites() {
        // The two forms keep separate bodies over the same two steps, so nothing but this holds them
        // together. Adding the sites back in site order is the order the whole form merges in, which
        // is what lets the floats agree to the bit rather than to a tolerance. Every shipped pose on
        // every mesh it poses, under every row its form renders at that form's age, across one figure
        // period's ticks.
        List<String> failures = new ArrayList<>();
        int compared = 0;
        int together = 0;
        for (Entity entity : entities.values())
            for (Form form : forms(entity))
                for (Posed posed : meshesOf(form.entity())) {
                    if (posed.pose().clips().isEmpty()) continue;
                    for (PoseStyle row : rowsOf(form))
                        for (int tick = 0; tick < StyleCatalog.FIGURE_PERIOD_TICKS; tick++) {
                            ToDoubleFunction<String> frame =
                                row.frameAt(tick, form.entity().styles().periodTicks());
                            ConcurrentList<ClipPlayer.SitePlay> sites =
                                ClipPlayer.siteDeltas(posed.pose(), posed.mesh(), frame);
                            String at = form.label() + " " + posed.label() + " '" + row.id() + "' @" + tick;
                            // A site answering nothing adds nothing to a sum, so the one-answer-per-site
                            // contract is read off the answers themselves: one per declared site, in
                            // declared order, a clock exactly where the site plays.
                            List<EntityPose.Clip> declared = posed.pose().clips();
                            if (sites.size() != declared.size()) {
                                failures.add(at + ": " + sites.size() + " answers for "
                                    + declared.size() + " sites");
                            }
                            for (int index = 0; index < Math.min(sites.size(), declared.size()); index++) {
                                ClipPlayer.SitePlay site = sites.get(index);
                                boolean plays = site.displacement() != ClipPlayer.Displacement.NONE;
                                if (site.site() != declared.get(index))
                                    failures.add(at + ": answer " + index + " is not its declared site");
                                if (site.millis().isPresent() != plays) {
                                    failures.add(at + ": answer " + index + " clock " + site.millis()
                                        + ", plays " + plays);
                                }
                            }
                            List<String> whole = ledger(ClipPlayer.deltas(posed.pose(), posed.mesh(), frame));
                            List<String> summed = ledger(summed(sites));
                            if (!whole.equals(summed))
                                failures.add(at + ": whole " + whole + ", summed " + summed);
                            if (sites.stream().filter(site -> !site.displacement().isEmpty()).count() > 1)
                                together++;
                            compared++;
                        }
                }

        assertTrue(compared > 0, "the walk reaches a pose that plays clips");
        assertTrue(together > 0, "and an instant at which two sites displace at once, " + compared + " compared");
        assertTrue(failures.isEmpty(), failures.size() + " of " + compared + " instants disagree, the first "
            + failures.subList(0, Math.min(5, failures.size())));
    }

    // ------------------------------------------------------------------------------------

    /** The two ends of the mirrored ramp, chosen to disagree in the middle rather than at the ends. */
    private static final float RAMP_FROM = -0.37f;
    private static final float RAMP_TO = 0.9128f;

    /** What a walk-driven site multiplies its position by before truncating - vanilla's own fifty. */
    private static final float WALK_MILLIS_PER_POSITION = 50f;

    /** The rate the mirrored site plays at, chosen so a second of position is a second of clip. */
    private static final float WALK_RATE = 20f;

    /** What a two-keyframe linear clip displaces its bone by at one instant of its own span. */
    private static float displacement(float at) {
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", new EntityMesh.Bone());

        // An undriven site holds the clip at nothing, so the instant is driven in as a walk position:
        // millis is `position * 50 * rate`, and a rate of 20 turns a second of clip into a second.
        EntityPose pose = ramping(ClipDrive.STRIDE,
            Concurrent.newUnmodifiableList(constant(at), constant(1f), constant(WALK_RATE), constant(1f)));

        Map<PoseChannel, Float> written =
            ClipPlayer.deltas(pose, mesh, PoseEvaluator.AT_REST).bones().get("body");
        assertNotNull(written, "the clip displaces the bone it names");
        return written.get(PoseChannel.X_ROT);
    }

    /** A pose playing the two-keyframe ramp clip under one drive, at the given arguments. */
    private static @NotNull EntityPose ramping(
        @NotNull ClipDrive drive, @NotNull ConcurrentList<PoseExpr> arguments) {

        PoseClip clip = new PoseClip(1f, false, Concurrent.newUnmodifiableList(new PoseClip.Channel("body",
            PoseChannel.Kind.ROTATION, Concurrent.newUnmodifiableList(
                new PoseClip.Keyframe(0f, RAMP_FROM, RAMP_FROM, RAMP_FROM, PoseClip.Interpolation.LINEAR),
                new PoseClip.Keyframe(1f, RAMP_TO, RAMP_TO, RAMP_TO, PoseClip.Interpolation.LINEAR)))));
        return new EntityPose(Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableMap(),
            Concurrent.newUnmodifiableList(new EntityPose.Clip("test", drive, Optional.empty(), arguments, clip)),
            Optional.empty());
    }

    /** A pose playing one clip that scales a named bone on one axis. */
    private static @NotNull EntityPose scaling(@NotNull String bone) {
        PoseClip clip = new PoseClip(1f, false, Concurrent.newUnmodifiableList(new PoseClip.Channel(bone,
            PoseChannel.Kind.SCALE, Concurrent.newUnmodifiableList(
                new PoseClip.Keyframe(0f, 0f, 0f, 0.5f, PoseClip.Interpolation.LINEAR)))));
        return new EntityPose(Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableMap(),
            Concurrent.newUnmodifiableList(new EntityPose.Clip(
                "test", ClipDrive.NONE, Optional.empty(), Concurrent.newUnmodifiableList(), clip)),
            Optional.empty());
    }

    private static @NotNull EntityMesh.Bone child(@NotNull String parent) {
        EntityMesh.Bone bone = new EntityMesh.Bone();
        return new EntityMesh.Bone(bone.getPivot(), bone.getRotation(), bone.getBindPoseRotation(),
            bone.getScale(), bone.getCubes(), parent);
    }

    private static @NotNull PoseExpr constant(float value) {
        return new PoseExpr.Constant(value, PoseWidth.FLOAT);
    }

    private static void assertNotEqualMeshes(
        @NotNull EntityMesh first, @NotNull EntityMesh second) {

        assertFalse(first.getBones().equals(second.getBones()),
            "a walk-driven clip is expected to stand somewhere different at a later tick");
    }

    private static @NotNull Entity subject(@NotNull String id) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        return entity;
    }

    /**
     * One form a shipped row carries.
     *
     * @param label how a failure names the form
     * @param entity the form's own definition, carrying its meshes, poses and catalog
     * @param baby whether the form is the row's baby
     */
    private record Form(@NotNull String label, @NotNull Entity entity, boolean baby) {}

    /**
     * One mesh with the pose that poses it.
     *
     * @param label how a failure names the mesh within its form
     * @param pose the pose posing the mesh
     * @param mesh the mesh posed
     */
    private record Posed(@NotNull String label, @NotNull EntityPose pose, @NotNull EntityMesh mesh) {}

    /**
     * Every form one shipped row carries, the row itself first - its baby and each shape, size and
     * variant sub-definition, each once however many axes reach it.
     */
    private static @NotNull List<Form> forms(@NotNull Entity row) {
        List<Form> forms = new ArrayList<>();
        collect(row.id().toString(), row, false, Collections.newSetFromMap(new IdentityHashMap<>()), forms);
        return forms;
    }

    private static void collect(
        @NotNull String label, @NotNull Entity entity, boolean baby,
        @NotNull Set<Entity> seen, @NotNull List<Form> forms) {

        if (!seen.add(entity)) return;
        forms.add(new Form(label, entity, baby));
        entity.axes().baby().ifPresent(form -> collect(label + " age=baby", form, true, seen, forms));
        entity.axes().shape().options()
            .forEach((key, form) -> collect(label + " shape=" + key, form, baby, seen, forms));
        entity.axes().size().options()
            .forEach((key, form) -> collect(label + " size=" + key, form, baby, seen, forms));
        entity.axes().variant().options()
            .forEach((key, form) -> collect(label + " variant=" + key, form, baby, seen, forms));
    }

    /**
     * Every mesh one form poses with the pose that poses it - the body, the body under each wearer
     * pose a filled slot swaps in, each overlay pass and its no-hat alternate, and each equipment mesh.
     */
    private static @NotNull List<Posed> meshesOf(@NotNull Entity form) {
        List<Posed> meshes = new ArrayList<>();
        meshes.add(new Posed("body", form.pose(), form.model()));
        for (Entity.EquipmentOverlay equipment : form.layers().equipment())
            equipment.wearerPose().ifPresent(pose ->
                meshes.add(new Posed("body wearing " + equipment.slot(), pose, form.model())));
        for (int index = 0; index < form.overlays().size(); index++) {
            Entity.OverlayLayer overlay = form.overlays().get(index);
            meshes.add(new Posed("pass " + index, overlay.pose(), overlay.model()));
            int pass = index;
            overlay.noHatModel().ifPresent(alternate ->
                meshes.add(new Posed("pass " + pass + " no-hat", overlay.pose(), alternate)));
        }
        for (Entity.EquipmentOverlay equipment : form.layers().equipment())
            meshes.add(new Posed("equip " + equipment.slot(), equipment.pose(), equipment.model()));
        return meshes;
    }

    /** Every shipped row one form renders at its own age, then the universal standing and walking rows. */
    private static @NotNull List<PoseStyle> rowsOf(@NotNull Form form) {
        Age age = form.baby() ? Age.BABY : Age.ADULT;
        List<PoseStyle> rows = new ArrayList<>();
        for (PoseStyle row : form.entity().styles().styles())
            if (row.age().map(age::equals).orElse(true)) rows.add(row);
        for (String universal : List.of(PoseStyle.IDLE, PoseStyle.STRIDE))
            rows.add(StyleCatalog.BIND_ONLY.resolve(universal, any -> true, form.label()));
        return rows;
    }

    /** What a pose's sites displace added together in site order, channel by channel. */
    private static @NotNull ClipPlayer.Displacement summed(@NotNull List<ClipPlayer.SitePlay> sites) {
        Map<String, Map<PoseChannel, Float>> bones = new LinkedHashMap<>();
        Map<PoseChannel, Float> container = new EnumMap<>(PoseChannel.class);
        for (ClipPlayer.SitePlay site : sites) {
            site.displacement().bones().forEach((bone, channels) -> {
                Map<PoseChannel, Float> written =
                    bones.computeIfAbsent(bone, any -> new EnumMap<>(PoseChannel.class));
                channels.forEach((channel, value) -> written.merge(channel, value, Float::sum));
            });
            site.displacement().container()
                .forEach((channel, value) -> container.merge(channel, value, Float::sum));
        }
        return new ClipPlayer.Displacement(bones, container);
    }

    /** Every displaced channel in order with its value's raw bits, so two ledgers agree only bit for bit. */
    private static @NotNull List<String> ledger(@NotNull ClipPlayer.Displacement displacement) {
        List<String> entries = new ArrayList<>();
        displacement.bones().forEach((bone, channels) -> channels.forEach((channel, value) ->
            entries.add("'" + bone + "' " + channel.token() + " " + bits(value))));
        displacement.container().forEach((channel, value) ->
            entries.add("container " + channel.token() + " " + bits(value)));
        return entries;
    }

    private static @NotNull String bits(float value) {
        return value + " (" + Integer.toHexString(Float.floatToRawIntBits(value)) + ")";
    }

}
