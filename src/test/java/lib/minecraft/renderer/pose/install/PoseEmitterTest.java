package lib.minecraft.renderer.pose.install;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleDriver;
import lib.minecraft.renderer.engine.kit.PoseKit;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.index.RawEntityPosesFile;
import lib.minecraft.renderer.pose.MotionSource;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.author.BuiltStyle;
import lib.minecraft.renderer.pose.author.Poses;
import lib.minecraft.renderer.pose.author.Side;
import lib.minecraft.renderer.pose.author.Turn;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static lib.minecraft.renderer.pose.compile.CompilerFixtures.boneWrite;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.constant;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.dadd;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.input;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.pose;
import static lib.minecraft.renderer.pose.install.RegistrarFixtures.definitions;
import static lib.minecraft.renderer.pose.install.RegistrarFixtures.entity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Emission round-trip - a woven row serialized to its table spelling, read back through the real
 * table reader, and evaluated bit-for-bit against its source, with the shared table preserved as
 * the graph it spells rather than the tree that graph stands for.
 */
@DisplayName("an emitted row reloads as the graph it spells")
class PoseEmitterTest {

    /**
     * The catalog period both sides frame their excursions against.
     */
    private static final int PERIOD = 24;

    /**
     * Plain, because the reader is declared on the type it reads rather than configured onto one.
     */
    private static final @NotNull Gson GSON = new Gson();

    /**
     * The hand-authored table spelling the same woven wave, beside the test sources.
     */
    private static final @NotNull String FIXTURE = "/lib/minecraft/renderer/pose/install/woven_wave_parity.json";

    // ------------------------------------------------------------------------------------
    // round trips
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("an animated woven row round-trips - fragment, clip table and every shared entry")
    void animatedRowRoundTrips() {
        EntityModelData mesh = humanoid();
        EntityPose source = woven(mesh, waveStyle());
        PoseEmitter.Emission emission = PoseEmitter.emit(source);

        assertEquals(List.of("style:wave"), List.copyOf(emission.clips().keySet()),
            "one file-level table per distinct play-site coordinate");
        // Reaching the pose at all proves every emitted shared entry is read - the reader
        // refuses a table declaring an entry nothing names.
        EntityPose loaded = load(envelope(emission, true));
        assertSame(loaded.bones().get("head").get(PoseChannel.Y_ROT),
            loaded.bones().get("hat").get(PoseChannel.Y_ROT),
            "each reference resolves to one instance per shared entry");
        assertEvaluatesEqually(source, loaded, mesh, styleRow("wave", true));
    }

    @Test
    @DisplayName("a static woven row emits the row alone, and round-trips")
    void staticRowEmitsTheRowAlone() {
        EntityModelData mesh = humanoid();
        EntityPose source = woven(mesh, sitStyle());
        PoseEmitter.Emission emission = PoseEmitter.emit(source);

        assertTrue(emission.clips().isEmpty(), "a row playing no site names no table");
        assertEvaluatesEqually(source, load(envelope(emission, true)), mesh, styleRow("sit", false));
    }

    @Test
    @DisplayName("the emitted row evaluates equal to its hand-authored table")
    void emittedRowMatchesTheHandAuthoredTable() {
        EntityModelData mesh = humanoid();
        EntityPose reloaded = load(envelope(PoseEmitter.emit(woven(mesh, waveStyle())), true));

        assertEvaluatesEqually(loadedFixture(), reloaded, mesh, styleRow("wave", true));
    }

    @Test
    @DisplayName("a raw splice round-trips behind its gate - the selection and its predicate spell, reload and evaluate on both arms")
    void rawSpliceRoundTripsBehindItsGate() {
        EntityModelData mesh = humanoid();
        EntityPose source = woven(mesh, rawStyle());
        EntityPose loaded = load(envelope(PoseEmitter.emit(source), true));

        PoseExpr.Select gated = (PoseExpr.Select) loaded.bones().get("body").get(PoseChannel.X_ROT);
        assertEquals("style$hatch", ((PoseExpr.Input) gated.condition().left()).field(),
            "the reloaded splice still rides this style's own gate");
        assertEvaluatesEqually(source, loaded, mesh, styleRow("hatch", true));
        assertEvaluatesEqually(source, loaded, mesh, styleRow("wave", true));
    }

    // ------------------------------------------------------------------------------------
    // refusals, sharing and determinism
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("the row-only animated fragment refuses - the play-site parse needs the clips table")
    void rowOnlyAnimatedFragmentRefuses() {
        PoseEmitter.Emission emission = PoseEmitter.emit(woven(humanoid(), waveStyle()));

        PipelineException refusal = assertThrows(PipelineException.class,
            () -> GSON.fromJson(envelope(emission, false), RawEntityPosesFile.class),
            "a play site naming a table the file does not declare fails where the file is read");
        assertTrue(refusal.getMessage().contains("declares no table for"), refusal.getMessage());
    }

    @Test
    @DisplayName("a forty-rung diamond ladder emits each shared node once, never the tree")
    void diamondLadderEmitsEachNodeOnce() {
        // Each rung's two operands are the SAME previous-rung instance, so forty-one nodes stand
        // for 2^40 paths; a per-path writer does not finish, and this emission stays linear.
        PoseExpr rung = constant(0.25d);
        for (int height = 0; height < 40; height++)
            rung = dadd(rung, rung);
        PoseEmitter.Emission emission = PoseEmitter.emit(boneWrite("body", PoseChannel.X_ROT, rung));

        JsonObject row = JsonParser.parseString(emission.row()).getAsJsonObject();
        assertEquals(40, row.getAsJsonArray("shared").size(),
            "one entry per multiply-referenced node - every rung below the top");
        assertTrue(emission.row().length() < 4096,
            "the fragment grows with the node count, never with the paths the rungs stand for");

        PoseExpr.Op top = (PoseExpr.Op) load(envelope(emission, true))
            .bones().get("body").get(PoseChannel.X_ROT);
        assertSame(top.operands().getFirst(), top.operands().getLast(),
            "the reloaded top rung shares one instance below itself");
    }

    @Test
    @DisplayName("two emits of one row are byte-identical")
    void emissionIsDeterministic() {
        EntityPose source = woven(humanoid(), waveStyle());
        PoseEmitter.Emission first = PoseEmitter.emit(source);
        PoseEmitter.Emission second = PoseEmitter.emit(source);

        assertEquals(first.row(), second.row(), "the fragment spells identically both times");
        assertEquals(first.clips(), second.clips(), "and so does each clip table");
    }

    @Test
    @DisplayName("a pose recording a refusal does not emit")
    void unreadablePoseRefuses() {
        EntityPose unreadable = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of()), Concurrent.newUnmodifiableList(),
            Optional.of("no geometry"));

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
            () -> PoseEmitter.emit(unreadable));
        assertTrue(refusal.getMessage().contains("could not be read"), refusal.getMessage());
    }

    // ------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------

    /**
     * Both rows posed per tick over one whole period - equality is on the evaluated bits alone.
     */
    private static void assertEvaluatesEqually(
        @NotNull EntityPose source, @NotNull EntityPose loaded,
        @NotNull EntityModelData mesh, @NotNull PoseStyle row) {

        assertEquals(source.bones().keySet(), loaded.bones().keySet(),
            "the reloaded row writes the same bones");
        for (int tick = 0; tick < PERIOD; tick++)
            assertEquals(
                PoseKit.posed(source, mesh, row, PERIOD, tick).getBones(),
                PoseKit.posed(loaded, mesh, row, PERIOD, tick).getBones(),
                "tick " + tick + " evaluates bit-for-bit across emission and reload");
    }

    /**
     * The given style installed over the shipped shapes on the given mesh, woven by the registrar.
     */
    private static @NotNull EntityPose woven(@NotNull EntityModelData mesh, @NotNull BuiltStyle style) {
        // The shipped table writes head and hat with one shared instance, the way a shell that
        // copies its head is baked.
        PoseExpr shippedHead = dadd(constant(0.25d), input("ageInTicks"));
        EntityPose shipped = pose(List.of(), Map.of(
            "head", Map.of(PoseChannel.Y_ROT, shippedHead),
            "hat", Map.of(PoseChannel.Y_ROT, shippedHead)), List.of());
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", mesh, shipped, StyleCatalog.BIND_ONLY)));
        registrar.add("minecraft:test", style);
        return registrar.definitions().get("minecraft:test").pose();
    }

    /**
     * The animated wave - two stance writes and one keyframed swing, so a play site is woven.
     */
    private static @NotNull BuiltStyle waveStyle() {
        return Poses.humanoid("wave")
            .head(head -> head.yaw(15))
            .arm(Side.RIGHT, arm -> arm.pitch(-40)
                .timeline(track -> track.swing(Turn.ROLL, -20, 20).over(0.6)))
            .build();
    }

    /**
     * The static sit - the same stance writes with no timeline, so no site and no table.
     */
    private static @NotNull BuiltStyle sitStyle() {
        return Poses.humanoid("sit")
            .head(head -> head.yaw(15))
            .arm(Side.RIGHT, arm -> arm.pitch(-40))
            .build();
    }

    /**
     * A raw splice over one channel beside two ordinary stances - the gated arm the hatch weaves,
     * whose graph reads a live field of its own so neither arm is a constant.
     */
    private static @NotNull BuiltStyle rawStyle() {
        return Poses.custom("hatch")
            .bone("head", head -> head.yaw(15))
            .bone("right_arm", arm -> arm.pitch(-40))
            .expr("body", PoseChannel.X_ROT, dadd(constant(0.5d), input("ageInTicks")))
            .build();
    }

    /**
     * One style row both sides evaluate under - drives the shipped subtree and both splices,
     * plus the clip gate and its clock for an animated style, with round extents so the
     * expectation is the graph shape alone.
     */
    private static @NotNull PoseStyle styleRow(@NotNull String id, boolean animated) {
        Map<String, StyleDriver> drivers = new LinkedHashMap<>();
        drivers.put("ageInTicks", new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP, 0f, 0.01f, Optional.empty()));
        drivers.put("style$" + id + "$head$y_rot",
            new StyleDriver("style$" + id + "$head$y_rot", StyleDriver.Wave.HOLD, 0f, 0.2f, Optional.empty()));
        drivers.put("style$" + id + "$right_arm$x_rot",
            new StyleDriver("style$" + id + "$right_arm$x_rot", StyleDriver.Wave.HOLD, 0f, -0.5f, Optional.empty()));
        if (animated) {
            drivers.put("style$" + id,
                new StyleDriver("style$" + id, StyleDriver.Wave.HOLD, 0f, 1f, Optional.empty()));
            drivers.put("style$" + id + "$clock",
                new StyleDriver("style$" + id + "$clock", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()));
        }
        return new PoseStyle(id,
            Concurrent.newUnmodifiableList(new PoseStyle.StyleSource(MotionSource.TICK, Optional.empty())),
            Concurrent.newUnmodifiableMap(drivers),
            Concurrent.newUnmodifiableList(), Optional.empty(), Optional.empty());
    }

    /**
     * A minimal enclosing file - the emitted tables where asked, and the row under one model name.
     */
    private static @NotNull JsonObject envelope(@NotNull PoseEmitter.Emission emission, boolean withTables) {
        JsonObject root = new JsonObject();
        root.addProperty("format", 3);
        if (withTables) {
            JsonObject clips = new JsonObject();
            emission.clips().forEach((coordinate, table) -> clips.add(coordinate, JsonParser.parseString(table)));
            root.add("clips", clips);
        }
        JsonObject poses = new JsonObject();
        poses.add("Woven", JsonParser.parseString(emission.row()));
        root.add("poses", poses);
        return root;
    }

    /**
     * The enveloped row, read back through the real table reader.
     */
    private static @NotNull EntityPose load(@NotNull JsonObject envelope) {
        return GSON.fromJson(envelope, RawEntityPosesFile.class).poses().get("Woven");
    }

    /**
     * The hand-authored table's pose row, read through the real table reader.
     */
    private static @NotNull EntityPose loadedFixture() {
        try (InputStream held = PoseEmitterTest.class.getResourceAsStream(FIXTURE)) {
            String text = new String(held.readAllBytes(), StandardCharsets.UTF_8);
            return GSON.fromJson(text, RawEntityPosesFile.class).poses().get("WovenWave");
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read fixture '" + FIXTURE + "'", ex);
        }
    }

}
