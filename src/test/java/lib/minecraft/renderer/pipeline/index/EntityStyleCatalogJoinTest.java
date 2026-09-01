package lib.minecraft.renderer.pipeline.index;

import com.google.gson.Gson;
import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.MotionSource;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleDriver;
import lib.minecraft.renderer.exception.PipelineException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The models table's {@code styles} member assembled into the per-entity {@link StyleCatalog}, and
 * the explicit per-form {@code pose} member joined ahead of the coordinate-head derivation.
 *
 * <p>The composition rules are the load-bearing half: a row's drivers arrive FLAT against its
 * {@code base} chain, an own drive naming a {@code group} replaces the inherited driver of that
 * group, {@code rest} and {@code extent} default to {@code 0} and {@code 1} where a drive spells
 * neither, and a gated source survives the read with its gate. What refuses has to refuse at load:
 * a styles-carrying file naming no {@code period_ticks}, a base no sibling row carries, and a base
 * chain that cycles.
 */
@DisplayName("the styles member and the explicit pose member")
class EntityStyleCatalogJoinTest {

    /** Plain, because every reader here is declared on the type it reads. */
    private static final @NotNull Gson GSON = new Gson();

    private static final @NotNull String ENTITY = "minecraft:test";
    private static final @NotNull String COORD = "TestModel#createBodyLayer";
    private static final @NotNull String BABY_COORD = "BabyModel#createBodyLayer";

    /** A three-row catalog: a root, a base-composing row with a group replacement, a baby-only row. */
    private static final @NotNull String STYLED = """
        { "period_ticks": 24,
          "models": { "minecraft:test": {
            "axes": { "age": { "options": {
                "adult": { "geometry": "TestModel#createBodyLayer", "texture": "test" } } } },
            "styles": [
              { "id": "idle", "sources": [ "tick" ],
                "drives": [
                  { "field": "swayAngle", "wave": "sweep", "rest": 0.0, "extent": 0.7853982 },
                  { "field": "idleHeadTiltAnimationState", "wave": "hold", "group": "action" } ] },
              { "id": "stride", "base": "idle",
                "sources": [ "tick", { "source": "scroll", "gate": "charged" } ],
                "drives": [
                  { "field": "hopAnimationState", "wave": "hold", "extent": 1.0, "group": "action" },
                  { "field": "walkAnimationPos", "wave": "ramp" } ] },
              { "id": "croak", "base": "idle", "age": "baby", "sources": [ "select" ],
                "toggles": [ "croak" ],
                "drives": [ { "field": "croakAnimationState", "wave": "hold", "group": "action" } ] }
            ] } } }""";

    @Test
    @DisplayName("the catalog assembles in shipped order, drivers flat against the base chain")
    void catalogAssemblesFlat() {
        StyleCatalog catalog = assemble(STYLED).styles();
        assertEquals(24, catalog.periodTicks(), "the period rides the file header");
        assertEquals(List.of("idle", "stride", "croak"),
            catalog.styles().stream().map(PoseStyle::id).toList(), "rows arrive in shipped order");

        PoseStyle idle = catalog.byId("idle").orElseThrow();
        assertEquals(
            Map.of("swayAngle",
                new StyleDriver("swayAngle", StyleDriver.Wave.SWEEP, 0f, 0.7853982f, Optional.empty()),
                "idleHeadTiltAnimationState",
                new StyleDriver("idleHeadTiltAnimationState", StyleDriver.Wave.HOLD, 0f, 1f,
                    Optional.of("action"))),
            Map.copyOf(idle.drivers()), "the root row's own drives, rest and extent defaulted 0 and 1");
        assertEquals(List.of(new PoseStyle.StyleSource(MotionSource.TICK, Optional.empty())),
            List.copyOf(idle.sources()), "a bare token is an unconditional source");

        PoseStyle stride = catalog.byId("stride").orElseThrow();
        assertEquals(List.of("swayAngle", "hopAnimationState", "walkAnimationPos"),
            List.copyOf(stride.drivers().keySet()),
            "the composed row inherits the ungrouped driver and replaces the grouped one");
        assertEquals(idle.drivers().get("swayAngle"), stride.drivers().get("swayAngle"),
            "an inherited driver arrives as the base composed it");
        assertEquals(
            new StyleDriver("walkAnimationPos", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()),
            stride.drivers().get("walkAnimationPos"), "an own ungrouped drive is simply added");
        assertEquals(
            List.of(new PoseStyle.StyleSource(MotionSource.TICK, Optional.empty()),
                new PoseStyle.StyleSource(MotionSource.SCROLL, Optional.of("charged"))),
            List.copyOf(stride.sources()), "the object spelling carries its gate beside the bare token");

        PoseStyle croak = catalog.byId("croak").orElseThrow();
        assertEquals(Optional.of(Age.BABY), croak.age(), "an aged row carries its age");
        assertEquals(List.of("croak"), List.copyOf(croak.toggles()), "and the toggles it entails");
        assertEquals(List.of(new PoseStyle.StyleSource(MotionSource.SELECT, Optional.empty())),
            List.copyOf(croak.sources()), "a selection row names its mechanism");
    }

    @Test
    @DisplayName("a family naming no styles carries the one BIND_ONLY catalog")
    void absentStylesAnswerBindOnly() {
        Entity plain = assemble("""
            { "models": { "minecraft:test": {
                "axes": { "age": { "options": {
                    "adult": { "geometry": "TestModel#createBodyLayer", "texture": "test" } } } } } } }""");
        assertSame(StyleCatalog.BIND_ONLY, plain.styles(),
            "a never-set catalog is the shared BIND_ONLY instance");
    }

    @Test
    @DisplayName("styles without a period_ticks header refuse at load")
    void stylesWithoutAPeriodRefuse() {
        PipelineException raised = assertThrows(PipelineException.class, () -> assemble("""
            { "models": { "minecraft:test": {
                "axes": { "age": { "options": {
                    "adult": { "geometry": "TestModel#createBodyLayer", "texture": "test" } } } },
                "styles": [ { "id": "idle" } ] } } }"""));
        assertTrue(raised.getMessage().contains("period_ticks"),
            "the refusal names the missing header: " + raised.getMessage());
    }

    @Test
    @DisplayName("a base no sibling row carries refuses at load")
    void aDanglingBaseRefuses() {
        PipelineException raised = assertThrows(PipelineException.class,
            () -> assemble(styled("""
                [ { "id": "stride", "base": "ghost" } ]""")));
        assertTrue(raised.getMessage().contains("'ghost'"),
            "the refusal names the missing base: " + raised.getMessage());
    }

    @Test
    @DisplayName("a base chain that cycles refuses at load")
    void aCyclicBaseChainRefuses() {
        PipelineException raised = assertThrows(PipelineException.class,
            () -> assemble(styled("""
                [ { "id": "a", "base": "b" }, { "id": "b", "base": "a" } ]""")));
        assertTrue(raised.getMessage().contains("composes over itself"),
            "the refusal says what the chain does: " + raised.getMessage());
    }

    @Test
    @DisplayName("an explicit pose member wins over the coordinate-head derivation, on both ages")
    void anExplicitPoseMemberWins() {
        EntityPose byHead = pose(-1f);
        EntityPose byMember = pose(-2f);
        Map<String, EntityPose> poses = Map.of("TestModel", byHead, "OtherModel", byMember);

        // Absent, the coordinate head answers exactly as it always has.
        Entity derived = assemble("""
            { "models": { "minecraft:test": {
                "axes": { "age": { "options": {
                    "adult": { "geometry": "TestModel#createBodyLayer", "texture": "test" } } } } } } }""",
            poses);
        assertSame(byHead, derived.pose(), "an unstated form derives its pose off the coordinate head");

        // Stated, the member wins - on the family's own form and on the baby fork alike.
        Entity explicit = assemble("""
            { "models": { "minecraft:test": {
                "axes": { "age": { "options": {
                    "adult": { "geometry": "TestModel#createBodyLayer", "texture": "test",
                               "pose": "OtherModel" },
                    "baby": { "geometry": "BabyModel#createBodyLayer",
                              "pose": "OtherModel" } } } } } } }""",
            poses);
        assertSame(byMember, explicit.pose(), "the stated pose key wins over the coordinate head");
        assertSame(byMember, explicit.axes().babyPose().orElseThrow(),
            "and the baby form's stated key wins over its own coordinate");
    }

    // ------------------------------------------------------------------------------------

    /** The styled fixture with the given {@code styles} array in place of the standard three rows. */
    private static @NotNull String styled(@NotNull String styles) {
        return """
            { "period_ticks": 24,
              "models": { "minecraft:test": {
                "axes": { "age": { "options": {
                    "adult": { "geometry": "TestModel#createBodyLayer", "texture": "test" } } } },
                "styles": %s } } }""".formatted(styles);
    }

    private static @NotNull Entity assemble(@NotNull String json) {
        return assemble(json, Map.of());
    }

    private static @NotNull Entity assemble(@NotNull String json, @NotNull Map<String, EntityPose> poses) {
        RawEntityModelsFile raw = GSON.fromJson(json, RawEntityModelsFile.class);
        Entity built = EntityIndexBuilder.assemble(
                Map.of(COORD, mesh(), BABY_COORD, mesh()), raw, poses, Map.of())
            .get(ENTITY);
        assertNotNull(built, ENTITY + " is expected to assemble");
        return built;
    }

    /** One pose told apart from another by where its container stands. */
    private static @NotNull EntityPose pose(float y) {
        return new EntityPose(
            Concurrent.newUnmodifiableList(
                Map.of(PoseChannel.Y, new PoseExpr.Const(y, PoseOperator.Width.FLOAT))),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(), Optional.empty());
    }

    private static @NotNull EntityModelData mesh() {
        EntityModelData model = new EntityModelData();
        model.getBones().put("body", new EntityModelData.Bone());
        return model;
    }

}
