package lib.minecraft.renderer.bake.pose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleClock;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.frame.StripFit;
import lib.minecraft.renderer.engine.frame.StripTrack;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.pose.ClipDrive;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseEvaluator;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseOperator;
import lib.minecraft.renderer.engine.pose.PoseWidth;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a resolved row is sampled into before its strip is fitted - one track per contribution to a
 * visible channel, named by the mesh and the member it moves.
 *
 * <p>The tracks are the strip's whole view of the subject, so what is pinned is what they would get
 * wrong silently: a channel spelled so that two contributions to one member do not meet, a scroll
 * read in the wrong sheet's texels, a clip site's period read off the wrong clock, a clock or a scroll
 * running backward read as repeating on no period at all, a mesh skipped because its pose cannot be
 * read or its bone does not draw, a pass's own mesh skipped because it moves under the body's pose,
 * an equipment layer never walked, a position read in a scaled mesh's drawn units rather than the
 * model's own, and a pass's opacity lost because the pass redraws the body.
 */
@DisplayName("the channels a resolved row is sampled into")
class StripSamplerTest {

    /**
     * A channel's spelling: the mesh, then a bone or a container step, then the member - or a pass's
     * scroll axis, or its opacity.
     */
    private static final @NotNull Pattern GRAMMAR = Pattern.compile(
        "(?<mesh>body|pass\\d+|equip:[^.]+)\\.(?<part>\\$container\\d+|[^.$]+)"
            + "\\.(?<member>x|y|z|x_rot|y_rot|z_rot|x_scale|y_scale|z_scale)"
            + "|pass\\d+\\.scroll\\.[uv]|pass\\d+\\.opacity");

    /** How far two tracks of one pose over two meshes may part where one mesh is flattened at a scale. */
    private static final double MODEL_UNIT_EPSILON = 1e-4;

    private static ConcurrentMap<String, Entity> entities;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
    }

    @Test
    @DisplayName("a zombie's stride is sampled as its body's bone members, each rotation in degrees")
    void theChannelsAreNamedByMeshBoneAndMember() {
        // The zombie draws no pass and wears no equipment layer, so everything its stride moves is
        // the body's, and the leg swing is the member every walk writes.
        Entity zombie = adult("minecraft:zombie");
        PoseStyle stride = row(zombie, PoseStyle.STRIDE);
        int period = zombie.styles().periodTicks();
        List<StripTrack> tracks = StripSampler.tracks(zombie, stride, period);

        assertFalse(tracks.isEmpty(), "a walking zombie moves");
        for (StripTrack track : tracks)
            assertTrue(track.channel().startsWith("body."), track.channel() + " is a member of the body");

        for (String bone : List.of("right_leg", "left_leg", "right_arm")) {
            StripTrack swing = only(tracks, "body." + bone + ".x_rot");
            assertEquals(StripTrack.Kind.ROTATION, swing.kind(), bone + " turns");
            assertEquals(new StripTrack.Role.Expression(), swing.role(), "on the pose's own expression");
            assertEquals(OptionalDouble.of(360d), swing.wrap(), "and comes round at a full turn");
            for (int tick = 0; tick < StripFit.WINDOW_TICKS; tick++) {
                float radians = PosePlayer.evaluate(zombie.pose(), zombie.model(), stride.frameAt(tick, period))
                    .bones().get(bone).get(PoseChannel.X_ROT);
                assertEquals(Math.toDegrees(radians), swing.values()[tick], 0d,
                    bone + " at tick " + tick + " is the evaluator's angle in degrees");
            }
        }
    }

    @Test
    @DisplayName("every shipped row's channels are spelled by mesh, bone or container step, and member")
    void everyShippedChannelIsSpelledByTheGrammar() {
        // Contributions are summed by channel, so a spelling one site writes and another does not is
        // two channels where the eye sees one. Each kind is held to the member its name says.
        int sampled = 0;
        int containers = 0;
        for (Entity definition : entities.values())
            for (Age age : agesOf(definition)) {
                AppearanceOptions appearance = AppearanceOptions.builder().age(age).build();
                Entity subject = appearance.resolve(definition);
                for (String id : definition.styles().ids()) {
                    PoseStyle style;
                    try {
                        style = subject.styles().resolve(id, appearance::applies, definition.id().toString());
                    } catch (RenderException refused) {
                        continue;
                    }
                    if (!style.moves()) continue;
                    for (StripTrack track : StripSampler.tracks(subject, style, subject.styles().periodTicks())) {
                        String at = definition.id() + " " + age + " '" + id + "' " + track.channel();
                        Matcher spelled = GRAMMAR.matcher(track.channel());
                        assertTrue(spelled.matches(), at + " is spelled by the grammar");
                        assertEquals(kindOf(track.channel(), spelled), track.kind(), at + " carries its member's kind");
                        assertEquals(track.kind() == StripTrack.Kind.ROTATION || track.kind() == StripTrack.Kind.TEXEL,
                            track.wrap().isPresent(), at + " wraps exactly where its kind comes round");
                        if (spelled.group("part") != null && spelled.group("part").startsWith("$container"))
                            containers++;
                    }
                    sampled++;
                }
            }

        assertTrue(sampled > 200, "the walk samples the corpus, " + sampled + " rows");
        assertTrue(containers > 0, "and some row moves a container step, so its spelling is read");
    }

    @Test
    @DisplayName("a breeze's wind scroll is sampled in its own sheet's texels, wrapping at the sheet")
    void theBreezeScrollWrapsAtItsSheet() {
        Entity breeze = adult("minecraft:breeze");
        PoseStyle idle = row(breeze, PoseStyle.IDLE);
        List<StripTrack> tracks = StripSampler.tracks(breeze, idle, breeze.styles().periodTicks());

        int scrolling = 0;
        for (int index = 0; index < breeze.overlays().size(); index++) {
            Entity.OverlayLayer pass = breeze.overlays().get(index);
            if (pass.textureScroll().isEmpty()) continue;
            scrolling++;
            Vector2f rate = pass.textureScroll().get();
            int width = pass.model().getTextureWidth();
            StripTrack scroll = only(tracks, "pass" + index + ".scroll.u");
            assertEquals(StripTrack.Kind.TEXEL, scroll.kind(), "a scroll moves the sheet");
            assertEquals(new StripTrack.Role.Looping(1d / rate.x()), scroll.role(), "once round per its rate's inverse");
            assertEquals(OptionalDouble.of(width), scroll.wrap(), "coming round at the sheet's width");
            for (int tick = 0; tick < StripFit.WINDOW_TICKS; tick++) {
                assertEquals((double) (pass.textureOffsetAt(tick).orElseThrow().x() * width), scroll.values()[tick], 0d,
                    "at tick " + tick + " it is the drawn offset, in that sheet's texels");
            }
            assertEquals(0f, rate.y(), "the wind scrolls across and not down");
            String unscrolled = "pass" + index + ".scroll.v";
            assertTrue(tracks.stream().noneMatch(track -> track.channel().equals(unscrolled)),
                "so the axis it does not scroll is not sampled");
        }
        assertEquals(1, scrolling, "the breeze scrolls one pass");
    }

    @Test
    @DisplayName("a breeze's wind, its own mesh under the body's pose, is sampled as a mesh of its own")
    void aPassMeshUnderTheBodysPoseIsWalked() {
        // Only a pass redrawing the body's very mesh under the body's very pose is passed over. The
        // wind shares the body's pose but draws a mesh of its own, so what the pose moves on the
        // wind's bones - its idle clip's sway - reaches the strip through the pass and nothing else.
        Entity breeze = adult("minecraft:breeze");
        PoseStyle idle = row(breeze, PoseStyle.IDLE);
        int period = breeze.styles().periodTicks();
        List<StripTrack> tracks = StripSampler.tracks(breeze, idle, period);

        int own = 0;
        for (int index = 0; index < breeze.overlays().size(); index++) {
            Entity.OverlayLayer pass = breeze.overlays().get(index);
            if (pass.pose() != breeze.pose() || pass.model() == breeze.model()) continue;
            own++;
            int members = sampledAsEvaluated(tracks, "pass" + index, pass.pose(), pass.model(), idle, period);
            assertTrue(members > 0, "pass " + index + " samples the members the pose moves on its own mesh, among "
                + tracks.stream().map(StripTrack::channel).toList());
        }
        assertEquals(1, own, "the breeze draws one pass of its own mesh under the body's pose");
    }

    @Test
    @DisplayName("a horse's worn armour is sampled under its slot, as its own pose writes its own mesh")
    void anEquipmentLayerIsWalked() {
        // The armour's legs and head swing with the body's, under channels of their own, whether or
        // not the slot is filled.
        Entity horse = adult("minecraft:horse");
        PoseStyle stride = row(horse, PoseStyle.STRIDE);
        int period = horse.styles().periodTicks();
        List<StripTrack> tracks = StripSampler.tracks(horse, stride, period);

        int walked = 0;
        for (Entity.EquipmentOverlay equipment : horse.layers().equipment()) {
            String prefix = "equip:" + equipment.slot();
            if (sampledAsEvaluated(tracks, prefix, equipment.pose(), equipment.model(), stride, period) > 0) walked++;
        }
        assertTrue(walked > 0, "some equipment layer's members are sampled, among "
            + tracks.stream().map(StripTrack::channel).toList());
        only(tracks, "equip:body.left_front_leg.x_rot");
    }

    @Test
    @DisplayName("an authored two-second looping clip reads a forty-tick period off its clock")
    void anAuthoredLoopingClipReadsItsPeriod() {
        // An authored timeline lowers to one selection-gated site whose clock ramps at a tick per
        // tick, so its clip clock advances fifty milliseconds a tick.
        Entity subject = clipped(true);
        StripTrack swing = only(StripSampler.tracks(subject, AUTHORED, subject.styles().periodTicks()), "body.body.x_rot");

        assertEquals(new StripTrack.Role.Looping(40d), swing.role(), "two seconds of clip loop every forty ticks");
    }

    @Test
    @DisplayName("an authored two-second clip played once reads a forty-tick run off its clock")
    void aOnceClipReadsItsLength() {
        Entity subject = clipped(false);
        StripTrack swing = only(StripSampler.tracks(subject, AUTHORED, subject.styles().periodTicks()), "body.body.x_rot");

        assertEquals(new StripTrack.Role.OneShot(40d), swing.role(), "it runs forty ticks and holds");
    }

    @Test
    @DisplayName("a looping clip whose clock runs backward reads its forty-tick period off the clock's speed")
    void aBackwardLoopingClipReadsItsPeriod() {
        // The clock runs down from fifteen seconds at fifty milliseconds a tick and stays above zero
        // across the window, so the clip comes round on its length at that speed as it does forward.
        Entity subject = clipped(true, rewound(300f));
        StripTrack swing = only(StripSampler.tracks(subject, AUTHORED, subject.styles().periodTicks()), "body.body.x_rot");

        assertEquals(new StripTrack.Role.Looping(40d), swing.role(), "two seconds of clip loop every forty ticks");
    }

    @Test
    @DisplayName("a clip played once whose clock runs backward runs until the clock reaches the clip's start")
    void aBackwardOnceClipRunsToItsStart() {
        // The clock starts a second and a half into the clip and runs down at fifty milliseconds a
        // tick, so the clip reaches its first instant at tick thirty and holds it there.
        Entity subject = clipped(false, rewound(30f));
        StripTrack swing = only(StripSampler.tracks(subject, AUTHORED, subject.styles().periodTicks()), "body.body.x_rot");

        assertEquals(new StripTrack.Role.OneShot(30d), swing.role(), "it runs thirty ticks and holds");
    }

    @Test
    @DisplayName("a scroll running backward repeats on the inverse of its rate")
    void aBackwardScrollReadsItsPeriod() {
        EntityMesh mesh = mesh();
        Vector2f rate = new Vector2f(-0.0185f, 0f);
        Entity subject = Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(mesh)
            .pose(EntityPose.NONE)
            .overlays(Concurrent.newUnmodifiableList(new Entity.OverlayLayer(mesh, Optional.empty(),
                PassDeclaration.DEFAULT, 0xFFFFFFFF, false, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), EntityPose.NONE, Optional.of(rate))))
            .build();
        StripTrack scroll = only(StripSampler.tracks(subject, TICKING, subject.styles().periodTicks()), "pass0.scroll.u");

        assertEquals(new StripTrack.Role.Looping(1d / 0.0185f), scroll.role(), "once round per its rate's inverse");
        assertEquals(OptionalDouble.of(mesh.getTextureWidth()), scroll.wrap(), "coming round at the sheet's width");
    }

    @Test
    @DisplayName("a pose that could not be read contributes nothing, clips included")
    void anUnreadablePoseContributesNothing() {
        // The evaluator writes nothing for a refused pose, but the clips it carries would still play
        // if asked - which is what makes the refusal the sampler's to honour.
        EntityPose readable = clipped(true).pose();
        EntityPose refused = new EntityPose(readable.container(), swinging("body", PoseChannel.X_ROT),
            readable.clips(), Optional.of("the pose could not be read"));
        Entity subject = Entity.builder().id(ResourceId.parse("minecraft:test")).model(mesh()).pose(refused).build();

        assertTrue(StripSampler.tracks(subject, AUTHORED, subject.styles().periodTicks()).isEmpty(),
            "a refused pose moves nothing");
    }

    @Test
    @DisplayName("a bone a toggle hides is sampled as if it drew")
    void aHiddenBoneIsStillSampled() {
        EntityMesh mesh = mesh();
        mesh.getBones().put("horn", bone(Vector3f.ZERO, 1f).withVisible(false));
        Entity subject = Entity.builder().id(ResourceId.parse("minecraft:test")).model(mesh)
            .pose(new EntityPose(Concurrent.newUnmodifiableList(), swinging("horn", PoseChannel.X_ROT),
                Concurrent.newUnmodifiableList(), Optional.empty()))
            .build();

        StripTrack horn = only(StripSampler.tracks(subject, TICKING, subject.styles().periodTicks()), "body.horn.x_rot");
        assertEquals(StripTrack.Kind.ROTATION, horn.kind(), "the hidden horn's swing is a track like any other");
    }

    @Test
    @DisplayName("one pose over a mesh at its own scale and one flattened at 2.35 samples one set of tracks")
    void positionsAreInTheModelsOwnUnits() {
        // A whole-mesh scale is flattened into the bones, so the scaled mesh stores its pivot at the
        // factor times vanilla's field plus the feet-anchor shift. The model's own units read the field
        // back on both; a mesh's drawn units would read the scaled one 2.35 times as far.
        float factor = 2.35f;
        float field = 10f;
        EntityMesh own = new EntityMesh();
        own.getBones().put("body", bone(new Vector3f(0f, field, 0f), 1f));
        EntityMesh flattened = new EntityMesh();
        flattened.getBones().put("body",
            bone(new Vector3f(0f, factor * field + EntityMesh.flattenedShift(factor), 0f), factor));

        Map<PoseChannel, PoseExpr> channels = new EnumMap<>(PoseChannel.class);
        channels.put(PoseChannel.Y, op(PoseOperator.ADD, new PoseExpr.BoneRead("body", PoseChannel.Y),
            op(PoseOperator.MUL, wave("walkAnimationPos", 0.6662f), constant(2f))));
        channels.put(PoseChannel.X_ROT, op(PoseOperator.MUL, wave("walkAnimationPos", 0.6662f), constant(0.5f)));
        EntityPose pose = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("body", channels)), Concurrent.newUnmodifiableList(), Optional.empty());

        List<StripTrack> unscaled = StripSampler.tracks(subject(own, pose), STRIDING, period());
        List<StripTrack> scaled = StripSampler.tracks(subject(flattened, pose), STRIDING, period());

        StripTrack lift = only(unscaled, "body.body.y");
        assertEquals(StripTrack.Kind.POSITION, lift.kind(), "the pose lifts the body");
        assertTrue(range(lift.values()) > 3d, "by more than three model pixels top to bottom");
        assertEquals(unscaled.size(), scaled.size(), "both meshes sample the same channels");
        for (int index = 0; index < unscaled.size(); index++) {
            StripTrack expected = unscaled.get(index);
            StripTrack actual = scaled.get(index);
            assertEquals(expected.channel(), actual.channel(), "track " + index + " names one channel on both");
            for (int tick = 0; tick < StripFit.WINDOW_TICKS; tick++) {
                assertEquals(expected.values()[tick], actual.values()[tick], MODEL_UNIT_EPSILON,
                    expected.channel() + " at tick " + tick + " reads the model's own value on the scaled mesh");
            }
        }
    }

    @Test
    @DisplayName("a pass's opacity is sampled as it draws, even where the pass redraws the body")
    void aPassOpacityIsSampledAsDrawn() {
        // A pass drawing the body's own mesh under the body's own pose writes the body's channels a
        // second time, so its channels are not sampled - but its opacity is its own, and the warden's
        // glow is exactly that shape.
        EntityMesh mesh = mesh();
        EntityPose pose = new EntityPose(Concurrent.newUnmodifiableList(), swinging("body", PoseChannel.X_ROT),
            Concurrent.newUnmodifiableList(), Optional.empty());
        PoseExpr pulse = op(PoseOperator.MAX, constant(0f),
            op(PoseOperator.MUL, op(PoseOperator.MTH_COS,
                op(PoseOperator.MUL, new PoseExpr.Input("ageInTicks"), constant(0.045f))), constant(0.25f)));
        Entity subject = Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(mesh)
            .pose(pose)
            .overlays(Concurrent.newUnmodifiableList(new Entity.OverlayLayer(mesh, Optional.empty(),
                PassDeclaration.DEFAULT, 0xFFFFFFFF, false, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), pose, Optional.empty(), Optional.of(pulse))))
            .build();

        int period = subject.styles().periodTicks();
        List<StripTrack> tracks = StripSampler.tracks(subject, TICKING, period);
        only(tracks, "body.body.x_rot");
        List<StripTrack> passes = tracks.stream().filter(track -> track.channel().startsWith("pass0.")).toList();
        assertEquals(1, passes.size(), "the pass contributes its opacity and none of the body's channels");

        StripTrack opacity = passes.getFirst();
        assertEquals("pass0.opacity", opacity.channel());
        assertEquals(StripTrack.Kind.OPACITY, opacity.kind(), "a fraction of full opacity");
        assertEquals(new StripTrack.Role.Expression(), opacity.role(), "read off its own expression");
        assertEquals(OptionalDouble.empty(), opacity.wrap(), "and wrapping nowhere");
        boolean lit = false;
        boolean dark = false;
        for (int tick = 0; tick < StripFit.WINDOW_TICKS; tick++) {
            double drawn = PosePlayer.opacityAt(pulse, mesh, TICKING.frameAt(tick, period));
            assertEquals(drawn, opacity.values()[tick], 0d, "at tick " + tick + " it is the opacity the pass draws at");
            assertTrue(drawn >= 0d && drawn <= 1d, "which is a fraction");
            lit |= drawn > 0d;
            dark |= drawn == 0d;
        }
        assertTrue(lit && dark, "the pulse lights and goes dark inside the window");
    }

    // ------------------------------------------------------------------------------------

    /** A row elapsed age drives, ramped at one - the shape of every shipped standing row. */
    private static final @NotNull PoseStyle TICKING = style("idle", StyleClock.TICK,
        new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()));

    /** A row walking at the full stride - the amplitude held at one and the phase ramped by it. */
    private static final @NotNull PoseStyle STRIDING = style("stride", StyleClock.STRIDE,
        new StyleDriver("walkAnimationSpeed", StyleDriver.Wave.HOLD, 0f, 1f, Optional.empty()),
        new StyleDriver("walkAnimationPos", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()));

    /** A row an authored timeline selects - its gate held at one and its clock ramped at one, beside elapsed age. */
    private static final @NotNull PoseStyle AUTHORED = style("authored", StyleClock.SELECT,
        new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()),
        new StyleDriver("authored", StyleDriver.Wave.HOLD, 0f, 1f, Optional.empty()),
        new StyleDriver("authored$clock", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()));

    /** A loaded definition at its adult age, resolved as a default render resolves it. */
    private static @NotNull Entity adult(@NotNull String id) {
        Entity definition = entities.get(id);
        assertNotNull(definition, id + " is expected to load");
        return AppearanceOptions.defaults().resolve(definition);
    }

    /** A resolved subject's in-force row of one id, as an adult default render resolves it. */
    private static @NotNull PoseStyle row(@NotNull Entity subject, @NotNull String id) {
        return subject.styles().resolve(id, AppearanceOptions.defaults()::applies, subject.id().toString());
    }

    /** The ages a definition renders at - the adult, and the baby where it carries a baby form. */
    private static @NotNull List<Age> agesOf(@NotNull Entity definition) {
        List<Age> ages = new ArrayList<>(List.of(Age.ADULT));
        if (definition.axes().baby().isPresent()) ages.add(Age.BABY);
        return ages;
    }

    /** The one track a channel is sampled into, failing where there is none or more than one. */
    private static @NotNull StripTrack only(@NotNull List<StripTrack> tracks, @NotNull String channel) {
        List<StripTrack> named = tracks.stream().filter(track -> track.channel().equals(channel)).toList();
        assertEquals(1, named.size(), channel + " is sampled into one track, among "
            + tracks.stream().map(StripTrack::channel).toList());
        return named.getFirst();
    }

    /**
     * Counts the bone members one mesh contributes, holding each to a bone the mesh declares and
     * each read off the pose's own expression to the evaluator's value at every tick, a rotation in
     * degrees.
     */
    private static int sampledAsEvaluated(
        @NotNull List<StripTrack> tracks, @NotNull String prefix, @NotNull EntityPose pose,
        @NotNull EntityMesh mesh, @NotNull PoseStyle style, int period) {

        List<PoseEvaluator.ChannelWrites> writes = new ArrayList<>(StripFit.WINDOW_TICKS);
        for (int tick = 0; tick < StripFit.WINDOW_TICKS; tick++)
            writes.add(PosePlayer.evaluate(pose, mesh, style.frameAt(tick, period)));

        int members = 0;
        for (StripTrack track : tracks) {
            Matcher spelled = GRAMMAR.matcher(track.channel());
            if (!spelled.matches() || !prefix.equals(spelled.group("mesh"))) continue;
            if (spelled.group("member") == null || spelled.group("part").startsWith("$container")) continue;
            members++;
            String bone = spelled.group("part");
            assertTrue(mesh.getBones().containsKey(bone), track.channel() + " moves a bone of the mesh it is named for");
            if (!new StripTrack.Role.Expression().equals(track.role())) continue;
            PoseChannel member = memberOf(spelled.group("member"));
            for (int tick = 0; tick < StripFit.WINDOW_TICKS; tick++) {
                float written = writes.get(tick).bones().get(bone).get(member);
                double expected = member.kind() == PoseChannel.Kind.ROTATION ? Math.toDegrees(written) : written;
                assertEquals(expected, track.values()[tick], 0d,
                    track.channel() + " at tick " + tick + " is what the pose writes on that mesh");
            }
        }
        return members;
    }

    /** The member a channel's token spells. */
    private static @NotNull PoseChannel memberOf(@NotNull String token) {
        for (PoseChannel member : PoseChannel.values())
            if (member.token().equals(token)) return member;

        throw new AssertionError(token + " names no member");
    }

    /** The kind a channel's spelling names - its member's, or a scroll's or an opacity's. */
    private static @NotNull StripTrack.Kind kindOf(@NotNull String channel, @NotNull Matcher spelled) {
        String member = spelled.group("member");
        if (member == null) return channel.endsWith(".opacity") ? StripTrack.Kind.OPACITY : StripTrack.Kind.TEXEL;
        for (PoseChannel written : PoseChannel.values()) {
            if (!written.token().equals(member)) continue;
            return switch (written.kind()) {
                case POSITION -> StripTrack.Kind.POSITION;
                case ROTATION -> StripTrack.Kind.ROTATION;
                case SCALE -> StripTrack.Kind.SCALE;
            };
        }
        throw new AssertionError(channel + " names no member");
    }

    /** A clip-playing subject: one two-second clip swinging the body, behind one authored site. */
    private static @NotNull Entity clipped(boolean looping) {
        return clipped(looping, new PoseExpr.Input("authored$clock"));
    }

    /**
     * A clip-playing subject: one two-second clip swinging the body, behind one authored site reading
     * its clock off a term.
     */
    private static @NotNull Entity clipped(boolean looping, @NotNull PoseExpr clock) {
        PoseClip clip = new PoseClip(2f, looping, Concurrent.newUnmodifiableList(new PoseClip.Channel("body",
            PoseChannel.Kind.ROTATION, Concurrent.newUnmodifiableList(
                new PoseClip.Keyframe(0f, 0f, 0f, 0f, PoseClip.Interpolation.LINEAR),
                new PoseClip.Keyframe(1f, 0.5f, 0f, 0f, PoseClip.Interpolation.LINEAR),
                new PoseClip.Keyframe(2f, 0f, 0f, 0f, PoseClip.Interpolation.LINEAR)))));
        EntityPose pose = new EntityPose(Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableMap(),
            Concurrent.newUnmodifiableList(new EntityPose.Clip("style:authored", ClipDrive.SELECT,
                Optional.of("authored"), Concurrent.newUnmodifiableList(clock), clip)),
            Optional.empty());
        return Entity.builder().id(ResourceId.parse("minecraft:test")).model(mesh()).pose(pose).build();
    }

    /** An authored clock running down from a tick, a tick a tick. */
    private static @NotNull PoseExpr rewound(float fromTick) {
        return op(PoseOperator.SUB, constant(fromTick), new PoseExpr.Input("authored$clock"));
    }

    /** A pose writing one member of one bone as a swing on elapsed age. */
    private static @NotNull ConcurrentMap<String, Map<PoseChannel, PoseExpr>> swinging(
        @NotNull String bone, @NotNull PoseChannel channel) {

        Map<PoseChannel, PoseExpr> written = new EnumMap<>(PoseChannel.class);
        written.put(channel, op(PoseOperator.MUL, wave("ageInTicks", 0.3f), constant(0.5f)));
        return Concurrent.newUnmodifiableMap(Map.of(bone, written));
    }

    /** A sine of one field at one rate. */
    private static @NotNull PoseExpr wave(@NotNull String field, float rate) {
        return op(PoseOperator.MTH_SIN, op(PoseOperator.MUL, new PoseExpr.Input(field), constant(rate)));
    }

    private static @NotNull PoseExpr op(@NotNull PoseOperator operator, @NotNull PoseExpr... operands) {
        return new PoseExpr.Op(operator, Concurrent.newUnmodifiableList(operands));
    }

    private static @NotNull PoseExpr constant(float value) {
        return new PoseExpr.Constant(value, PoseWidth.FLOAT);
    }

    /** A row moved through one clock by the given drivers. */
    private static @NotNull PoseStyle style(
        @NotNull String id, @NotNull StyleClock clock, @NotNull StyleDriver... drivers) {

        Map<String, StyleDriver> driven = new LinkedHashMap<>();
        for (StyleDriver driver : drivers) driven.put(driver.field(), driver);
        return new PoseStyle(id,
            Concurrent.newUnmodifiableList(new PoseStyle.StyleSource(clock, Optional.empty())),
            Concurrent.newUnmodifiableMap(driven), Concurrent.newUnmodifiableList(), Optional.empty(),
            Optional.empty());
    }

    /** A one-bone mesh, the bone carrying one cube. */
    private static @NotNull EntityMesh mesh() {
        ConcurrentList<EntityMesh.Cube> cubes = Concurrent.newList();
        cubes.add(new EntityMesh.Cube());
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", new EntityMesh.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 1f,
            cubes, null));
        return mesh;
    }

    /** A cubeless top-level bone at a pivot, resting at a scale. */
    private static @NotNull EntityMesh.Bone bone(@NotNull Vector3f pivot, float scale) {
        return new EntityMesh.Bone(pivot, EulerRotation.NONE, EulerRotation.NONE, scale, Concurrent.newList(), null);
    }

    /** A subject drawing one mesh under one pose, with nothing else. */
    private static @NotNull Entity subject(@NotNull EntityMesh mesh, @NotNull EntityPose pose) {
        return Entity.builder().id(ResourceId.parse("minecraft:test")).model(mesh).pose(pose).build();
    }

    /** The figure period a subject with no catalog of its own frames at. */
    private static int period() {
        return subject(mesh(), EntityPose.NONE).styles().periodTicks();
    }

    /** How far a series runs from its lowest sample to its highest. */
    private static double range(double @NotNull [] values) {
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (double value : values) {
            low = Math.min(low, value);
            high = Math.max(high, value);
        }
        return high - low;
    }

}
