package lib.minecraft.renderer.author.pose;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.MotionSource;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleDriver;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.option.OutputOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.support.StubRendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static lib.minecraft.renderer.author.pose.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.author.pose.CompilerFixtures.row;
import static lib.minecraft.renderer.author.pose.RegistrarFixtures.definitions;
import static lib.minecraft.renderer.author.pose.RegistrarFixtures.entity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The per-style period's whole travel path - the builder verb lowered onto the compiled row, the
 * frame oracle resolving the row's own window over the caller's, the strip schedule stepping at
 * the declared window, the rendered strip holding each frame for that window's own tick span,
 * and the narrowed-catalog rebuild keeping a declared period where an appearance drops a gated
 * source.
 */
@DisplayName("a declared period reframes one style's own window")
class PeriodFramingTest {

    /**
     * The catalog period every fixture frames against unless a row declares its own.
     */
    private static final int PERIOD = 24;

    /**
     * The declared window in ticks - 2.4 seconds at the twenty-tick clock.
     */
    private static final int DECLARED = 48;

    /**
     * The field the breathe sway drives - the style's own spelling of the head's roll.
     */
    private static final String SWAY_FIELD = "style$breathe$head$z_rot";

    // ---- compile lowering ----------------------------------------------------------------------

    @Test
    @DisplayName("the period verb lowers to whole ticks on the compiled row")
    void thePeriodVerbLowersToWholeTicks() {
        PoseCompiler.Compiled compiled = breathe();
        assertEquals(Optional.of(DECLARED), compiled.style().periodTicks(),
            "2.4 seconds land as 48 ticks");
        StyleDriver sway = compiled.style().drivers().get(SWAY_FIELD);
        assertEquals(StyleDriver.Wave.SWEEP, sway.wave(), "the sway still lowers to one sweep");
    }

    // ---- frameAt resolution --------------------------------------------------------------------

    @Test
    @DisplayName("frameAt resolves the row's own period over the caller's")
    void frameAtResolvesTheRowsOwnPeriod() {
        PoseStyle breathe = breathe().style();
        assertEquals(rest(), breathe.frameAt(0, PERIOD).applyAsDouble(SWAY_FIELD),
            "the sweep rests at its near bound at tick zero");
        assertEquals(peak(), breathe.frameAt(DECLARED / 2, PERIOD).applyAsDouble(SWAY_FIELD),
            "and peaks mid-window at tick 24, where the caller's 24-tick period would rest it");
        assertEquals(rest(), breathe.frameAt(DECLARED, PERIOD).applyAsDouble(SWAY_FIELD),
            "one whole excursion completes over 48 ticks");
    }

    @Test
    @DisplayName("an empty component frames at the given period")
    void anEmptyComponentFramesAtTheGivenPeriod() {
        PoseStyle undeclared = undeclared(breathe().style());
        assertEquals(peak(), undeclared.frameAt(PERIOD / 2, PERIOD).applyAsDouble(SWAY_FIELD),
            "the same drivers peak mid-window at tick 12");
        assertEquals(rest(), undeclared.frameAt(PERIOD, PERIOD).applyAsDouble(SWAY_FIELD),
            "and complete one excursion over the caller's 24 ticks");
    }

    // ---- strip framing -------------------------------------------------------------------------

    @Test
    @DisplayName("the schedule steps at the declared window - six ticks per frame, not three")
    void theScheduleStepsAtTheDeclaredWindow() {
        PoseStyle breathe = breathe().style();
        StyleCatalog catalog = RegistrarFixtures.catalog(breathe);
        assertEquals(6, catalog.stripTicksPerFrame(breathe),
            "48 ticks divide across the eight-frame strip");
        assertEquals(3, catalog.stripTicksPerFrame(),
            "while the catalog's own step is untouched");
        assertEquals(3, catalog.stripTicksPerFrame(undeclared(breathe)),
            "and the same row with the component empty frames at the catalog step");
    }

    @Test
    @DisplayName("every shipped row schedules exactly as the catalog does")
    void everyShippedRowSchedulesAsTheCatalogDoes() {
        EntityOptions options = EntityOptions.of("minecraft:test");
        for (String id : new String[] { PoseStyle.BIND, PoseStyle.IDLE, PoseStyle.STRIDE, PoseStyle.ANIMATED }) {
            PoseStyle resolved = StyleCatalog.BIND_ONLY.resolve(id, options);
            assertEquals(StyleCatalog.BIND_ONLY.stripTicksPerFrame(),
                StyleCatalog.BIND_ONLY.stripTicksPerFrame(resolved),
                "'" + id + "' resolves an empty component, so its schedule is the catalog's own");
        }
    }

    // ---- inForce propagation -------------------------------------------------------------------

    @Test
    @DisplayName("a gated-source row keeps its declared period through the narrowed rebuild")
    void aGatedSourceRowKeepsItsPeriodThroughInForce() {
        PoseStyle gated = new PoseStyle("breathe",
            Concurrent.newUnmodifiableList(
                new PoseStyle.StyleSource(MotionSource.TICK, Optional.empty()),
                new PoseStyle.StyleSource(MotionSource.SCROLL, Optional.of("charged"))),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(),
            Optional.empty(), Optional.of(DECLARED));
        StyleCatalog catalog = RegistrarFixtures.catalog(gated);

        StyleCatalog narrowed = catalog.inForce(false, gate -> false);
        PoseStyle kept = narrowed.styles().getFirst();
        assertEquals(1, kept.sources().size(), "the refused gate narrows the row");
        assertEquals(Optional.of(DECLARED), kept.periodTicks(),
            "and the rebuilt row still declares its own window");
        assertEquals(6, narrowed.stripTicksPerFrame(kept),
            "so the narrowed subject schedules at the declared step");
    }

    // ---- install propagation -------------------------------------------------------------------

    @Test
    @DisplayName("an install carries the declared period onto the appended catalog row")
    void anInstallCarriesTheDeclaredPeriod() {
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), EntityPose.NONE, StyleCatalog.BIND_ONLY)));
        registrar.add("minecraft:test", breatheStyle());

        StyleCatalog catalog = registrar.definitions().get("minecraft:test").styles();
        PoseStyle installed = catalog.byId("breathe").orElseThrow();
        assertEquals(Optional.of(DECLARED), installed.periodTicks(),
            "the appended row declares the authored window");
        assertEquals(6, catalog.stripTicksPerFrame(installed),
            "and schedules at it");
        assertTrue(installed.moves(), "a declared period rides a moving style");
        assertSame(installed.periodTicks(),
            catalog.inForce(false, gate -> true).byId("breathe").orElseThrow().periodTicks(),
            "a subject nothing narrows reads the very row");
    }

    // ---- the rendered strip --------------------------------------------------------------------

    @Test
    @DisplayName("a rendered strip holds each frame for the declared window's own tick span")
    void aRenderedStripHoldsTheDeclaredWindowsSpan() {
        assumeTrue(!EntityModelLoader.load().isEmpty(),
            "entity_models.json not present - run entityModels first");
        EntityRenderer renderer = StyleRegistrar.of(definitions(PlayerRig.entityRow()))
            .add(PlayerRig.ENTITY_ID, breatheStyle())
            .add(PlayerRig.ENTITY_ID, Poses.humanoid("sway")
                .head(head -> head.sway(Turn.ROLL, -8, 8))
                .build())
            .renderer(StubRendererContext.builder().everyTexture(PeriodFramingTest::sheet).build());

        ImageData declared = renderer.render(rendered("breathe"));
        assertEquals(StyleCatalog.STRIP_FRAMES, declared.getFrames().size(),
            "a moving style renders the whole strip");
        for (ImageFrame frame : declared.getFrames())
            assertEquals(300, frame.delayMs(),
                "every frame of the 48-tick window plays for six ticks");
        assertFalse(Arrays.equals(
                declared.getFrames().getFirst().pixels().data(),
                declared.getFrames().get(StyleCatalog.STRIP_FRAMES / 2).pixels().data()),
            "the excursion moves pixels between its rest and its peak");

        ImageData framed = renderer.render(rendered("sway"));
        assertEquals(StyleCatalog.STRIP_FRAMES, framed.getFrames().size(),
            "an undeclared moving row renders the same strip");
        for (ImageFrame frame : framed.getFrames())
            assertEquals(150, frame.delayMs(),
                "and its frames play for the catalog's three ticks");
    }

    // ---- fixtures ------------------------------------------------------------------------------

    /**
     * The slow breathe - one head sway under a 2.4 second window - compiled against the canonical
     * biped.
     */
    private static @NotNull PoseCompiler.Compiled breathe() {
        return PoseCompiler.compile(breatheStyle(), row(humanoid(), EntityPose.NONE));
    }

    /**
     * The built breathe style, before any compile.
     */
    private static @NotNull BuiltStyle breatheStyle() {
        return Poses.humanoid("breathe")
            .head(head -> head.sway(Turn.ROLL, -8, 8))
            .period(2.4)
            .build();
    }

    /**
     * The given row with its period component emptied and everything else carried as is.
     */
    private static @NotNull PoseStyle undeclared(@NotNull PoseStyle style) {
        return new PoseStyle(style.id(), style.sources(), style.drivers(), style.toggles(),
            style.age(), Optional.empty());
    }

    /**
     * Plain render options naming one style, sized small so the strip renders quickly.
     */
    private static @NotNull EntityOptions rendered(@NotNull String style) {
        return EntityOptions.builder()
            .entityId(PlayerRig.ENTITY_ID)
            .style(style)
            .output(OutputOptions.builder().canvasSize(96).supersample(1).antiAlias(false).build())
            .build();
    }

    /**
     * One opaque 64x64 sheet, so drawn geometry lands visible pixels.
     */
    private static @NotNull PixelBuffer sheet() {
        PixelBuffer sheet = PixelBuffer.create(64, 64);
        sheet.fill(0xFF6A8CAD);
        return sheet;
    }

    /**
     * The sway's near bound - what the sweep answers at both ends of its window.
     */
    private static double rest() {
        return (float) Math.toRadians(-8);
    }

    /**
     * The sway's far bound - what the sweep answers mid-window.
     */
    private static double peak() {
        return (float) Math.toRadians(8);
    }

}
