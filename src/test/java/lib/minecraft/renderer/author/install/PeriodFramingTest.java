package lib.minecraft.renderer.author.install;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.frame.ImageFrame;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleClock;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.Poses;
import lib.minecraft.renderer.author.Turn;
import lib.minecraft.renderer.author.compile.PoseCompiler;
import lib.minecraft.renderer.bake.pose.PoseStrips;
import lib.minecraft.renderer.call.request.EntityOptions;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.index.StripMemo;
import lib.minecraft.renderer.engine.frame.StripFit;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.fixture.RegistrarFixtures;
import lib.minecraft.renderer.vanilla.appearance.Flag;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static lib.minecraft.renderer.fixture.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.fixture.CompilerFixtures.row;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.definitions;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.entity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The per-style period's whole travel path - the builder verb lowered onto the compiled row, the
 * frame oracle resolving the row's own window over the caller's, the derived strip covering the
 * declared window, the rendered strip drawing that strip, and the narrowed-catalog rebuild keeping a
 * declared period where an appearance drops a gated source.
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
        assertEquals(StyleDriver.Wave.Plain.SWEEP, sway.wave(), "the sway still lowers to one sweep");
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
    @DisplayName("the declared window sets the derived strip - forty-seven frames for 48 ticks, twenty-four for 24")
    void theDeclaredWindowSetsTheDerivedStrip() {
        // The sway fits the fine strip either way, so it takes a tick a frame. Over 48 ticks it eases into its
        // rest slowly enough to come back within a degree a tick short of its window; over 24 it does not.
        Entity installed = installedBreathe();
        PoseStyle breathe = installed.styles().byId("breathe").orElseThrow();
        int period = installed.styles().periodTicks();
        StripMemo strips = new StripMemo();
        StripFit declared = PoseStrips.of(strips, installed, breathe, period);
        assertEquals(List.of(47, 1), List.of(declared.frames(), declared.ticksPerFrame()),
            "the sweep comes back to rest a tick short of its 48-tick window, a tick a frame");
        assertTrue(declared.seam() <= 1d, "and closes there, within a tolerance");
        StripFit framed = PoseStrips.of(strips, installed, undeclared(breathe), period);
        assertEquals(List.of(24, 1), List.of(framed.frames(), framed.ticksPerFrame()),
            "and the same row with the component empty closes at the catalog's 24 ticks");
    }

    @Test
    @DisplayName("every universal row is the still strip")
    void everyUniversalRowIsStill() {
        EntityOptions options = EntityOptions.of("minecraft:test");
        Entity subject = entity("minecraft:test", humanoid(), EntityPose.NONE, StyleCatalog.BIND_ONLY);
        for (String id : new String[] { PoseStyle.BIND, PoseStyle.IDLE, PoseStyle.STRIDE, PoseStyle.ANIMATED }) {
            PoseStyle resolved = StyleCatalog.BIND_ONLY
                .resolve(id, options.getAppearance()::applies, options.getEntityId());
            assertSame(StripFit.STILL, PoseStrips.of(new StripMemo(), subject, resolved, StyleCatalog.BIND_ONLY.periodTicks()),
                "'" + id + "' resolves a row with no source in force, so it draws one frame");
        }
    }

    // ---- inForce propagation -------------------------------------------------------------------

    @Test
    @DisplayName("a gated-source row keeps its declared period through the narrowed rebuild")
    void aGatedSourceRowKeepsItsPeriodThroughInForce() {
        PoseStyle gated = new PoseStyle("breathe",
            Concurrent.newUnmodifiableList(
                new PoseStyle.StyleSource(StyleClock.TICK, Optional.empty()),
                new PoseStyle.StyleSource(StyleClock.SCROLL, Optional.of(Flag.CHARGED))),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(),
            Optional.empty(), Optional.of(DECLARED));
        StyleCatalog catalog = RegistrarFixtures.catalog(gated);

        StyleCatalog narrowed = catalog.inForce(false, gate -> false);
        PoseStyle kept = narrowed.styles().getFirst();
        assertEquals(1, kept.sources().size(), "the refused gate narrows the row");
        assertEquals(Optional.of(DECLARED), kept.periodTicks(),
            "and the rebuilt row still declares its own window");
    }

    // ---- install propagation -------------------------------------------------------------------

    @Test
    @DisplayName("an install carries the declared period onto the appended catalog row")
    void anInstallCarriesTheDeclaredPeriod() {
        StyleCatalog catalog = installedBreathe().styles();
        PoseStyle installed = catalog.byId("breathe").orElseThrow();
        assertEquals(Optional.of(DECLARED), installed.periodTicks(),
            "the appended row declares the authored window");
        assertTrue(installed.moves(), "a declared period rides a moving style");
        assertSame(installed.periodTicks(),
            catalog.inForce(false, gate -> true).byId("breathe").orElseThrow().periodTicks(),
            "a subject nothing narrows reads the very row");
    }

    // ---- the rendered strip --------------------------------------------------------------------

    @Test
    @DisplayName("a rendered strip plays the declared window back to rest at a tick a frame")
    void aRenderedStripCoversTheDeclaredWindow() {
        assumeTrue(!EntityModelLoader.load().isEmpty(),
            "entity_models.json not present - run entityModels first");
        EntityRenderer renderer = new EntityRenderer(StyleRegistrar.of(definitions(PlayerRig.entityRow()))
            .add(PlayerRig.ENTITY_ID, breatheStyle())
            .add(PlayerRig.ENTITY_ID, Poses.humanoid("sway")
                .head(head -> head.sway(Turn.ROLL, -8, 8))
                .build())
            .context(RendererContext.builder().textures(textureId -> Possible.of(sheet())).build()));

        ImageData declared = renderer.render(rendered("breathe")).image();
        assertEquals(47, declared.getFrames().size(),
            "a moving style renders its whole derived strip - the 48-tick window back at rest a tick short, a tick a frame");
        for (ImageFrame frame : declared.getFrames())
            assertEquals(50, frame.delayMs(),
                "every frame of the 48-tick window plays for one tick");
        assertFalse(Arrays.equals(
                declared.getFrames().getFirst().pixels().data(),
                declared.getFrames().get(24).pixels().data()),
            "the excursion moves pixels between its rest at frame 0 and its peak at frame 24");

        ImageData framed = renderer.render(rendered("sway")).image();
        assertEquals(24, framed.getFrames().size(),
            "an undeclared moving row renders the strip its 24-tick window derives");
        for (ImageFrame frame : framed.getFrames())
            assertEquals(50, frame.delayMs(),
                "and its frames play for one tick too");
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
     * A bare biped carrying the breathe style, installed through the registrar.
     */
    private static @NotNull Entity installedBreathe() {
        return StyleRegistrar.of(definitions(entity("minecraft:test", humanoid(), EntityPose.NONE, StyleCatalog.BIND_ONLY)))
            .add("minecraft:test", breatheStyle())
            .definitions()
            .get("minecraft:test");
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
