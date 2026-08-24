package lib.minecraft.renderer.asset.appearance;

import lib.minecraft.renderer.asset.DyeColor;
import lib.minecraft.renderer.option.AppearanceOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Every sealed arm of {@link AppearanceGate} against the {@link AppearanceOptions} selection that fires
 * it and the selection that leaves it silent. A gate arm decides whether a shipped overlay or layer
 * row draws at all, so an arm answering the wrong way drops or adds a whole pass, and nine one-line
 * {@code test} bodies read alike enough that the differences between them are the part worth pinning:
 * {@link AppearanceGate.TintedGate} compares a resolved colour rather than asking whether a dye was
 * chosen, {@link AppearanceGate.FlagGate} answers a hard-coded token and treats every other one as
 * unset, {@link AppearanceGate.CollarColorGate} reads tameness as well as the dye, and
 * {@link AppearanceGate.AgeGate} fires unselected where {@link AppearanceGate.SizeGate} never does.
 *
 * <p>The arm table below is checked against {@code getPermittedSubclasses()}, so a tenth arm fails
 * this class rather than slipping through with no coverage.
 */
@DisplayName("AppearanceGate sealed arms")
class AppearanceGateTest {

    /**
     * The tint the shipped sheep wool undercoat row bakes, which is the colour {@link TintAxis#WOOL}
     * resolves {@link DyeColor.Vanilla#WHITE} to rather than that dye's own colour
     */
    private static final int UNDERCOAT_TINT = 0xFFE6E6E6;

    /** One sample per sealed arm, each paired with a firing and a non-firing appearance. */
    private static final List<ArmCase> ARMS = List.of(
        new ArmCase(new AppearanceGate.StateGate("tame"),
            AppearanceOptions.builder().state(Optional.of("tame")).build(),
            AppearanceOptions.defaults()),
        new ArmCase(new AppearanceGate.FlagGate("sheared", true),
            AppearanceOptions.builder().sheared(true).build(),
            AppearanceOptions.defaults()),
        new ArmCase(new AppearanceGate.ChargedGate(),
            AppearanceOptions.builder().charged(true).build(),
            AppearanceOptions.defaults()),
        new ArmCase(new AppearanceGate.TintedGate(Optional.of(TintAxis.WOOL), UNDERCOAT_TINT),
            AppearanceOptions.builder().tints(Map.of(TintAxis.WOOL, DyeColor.Vanilla.RED)).build(),
            AppearanceOptions.defaults()),
        new ArmCase(new AppearanceGate.EquipmentGate("saddle"),
            AppearanceOptions.builder().equipment(Map.of("saddle", "")).build(),
            AppearanceOptions.defaults()),
        new ArmCase(new AppearanceGate.MarkingsGate(),
            AppearanceOptions.builder().markings(HorseMarking.WHITE_DOTS).build(),
            AppearanceOptions.defaults()),
        new ArmCase(new AppearanceGate.CollarColorGate(),
            AppearanceOptions.builder().state(Optional.of("tame")).build(),
            AppearanceOptions.defaults()),
        new ArmCase(new AppearanceGate.AgeGate(Age.BABY),
            AppearanceOptions.builder().age(Age.BABY).build(),
            AppearanceOptions.defaults()),
        new ArmCase(new AppearanceGate.SizeGate(Size.SMALL),
            AppearanceOptions.builder().size(Optional.of(Size.SMALL)).build(),
            AppearanceOptions.defaults()));

    @Test
    @DisplayName("the arm table exercises every permitted subclass")
    void tableExercisesEveryPermittedArm() {
        Set<Class<?>> exercised = new HashSet<>();
        for (ArmCase arm : ARMS) exercised.add(arm.gate().getClass());
        Set<Class<?>> permitted = Set.of(AppearanceGate.class.getPermittedSubclasses());
        assertThat(exercised, is(equalTo(permitted)));
    }

    @Test
    @DisplayName("every arm fires for its own selection and stays silent for the default appearance")
    void everyArmFiresForItsOwnSelection() {
        for (ArmCase arm : ARMS) {
            assertThat(arm.gate() + " fires for its selection", arm.gate().test(arm.fires()), is(true));
            assertThat(arm.gate() + " stays silent for the default", arm.gate().test(arm.silent()), is(false));
        }
    }

    @Nested
    @DisplayName("TintedGate")
    class Tinted {

        @Test
        @DisplayName("stays silent while the axis carries no dye at all")
        void staysSilentWithNoDye() {
            AppearanceGate gate = new AppearanceGate.TintedGate(Optional.of(TintAxis.WOOL), UNDERCOAT_TINT);
            assertThat(gate.test(AppearanceOptions.defaults()), is(false));
        }

        @Test
        @DisplayName("stays silent for a dye that resolves to the row's own baked tint")
        void staysSilentForTheBakedTint() {
            // The one case a "fires whenever a dye is selected" reading would get wrong: white IS
            // selected, and the undercoat still must not draw, because white is what the row bakes.
            AppearanceGate gate = new AppearanceGate.TintedGate(Optional.of(TintAxis.WOOL), UNDERCOAT_TINT);
            AppearanceOptions white = AppearanceOptions.builder()
                .tints(Map.of(TintAxis.WOOL, DyeColor.Vanilla.WHITE))
                .build();
            assertThat(gate.test(white), is(false));
        }

        @Test
        @DisplayName("fires for a dye that resolves to anything else")
        void firesForADifferingDye() {
            AppearanceGate gate = new AppearanceGate.TintedGate(Optional.of(TintAxis.WOOL), UNDERCOAT_TINT);
            for (DyeColor.Vanilla dye : DyeColor.Vanilla.values()) {
                AppearanceOptions dyed = AppearanceOptions.builder().tints(Map.of(TintAxis.WOOL, dye)).build();
                assertThat(dye + " draws the undercoat unless it resolves to the baked tint",
                    gate.test(dyed), is(TintAxis.WOOL.resolve(dye) != UNDERCOAT_TINT));
            }
        }

        @Test
        @DisplayName("compares the axis resolve rather than the dye's own colour")
        void comparesTheAxisResolve() {
            // Wool alone routes through woolArgb, so a row baked at white's DYE colour is differed
            // from by white itself. Comparing dye.argb() would answer the opposite here.
            AppearanceGate wool = new AppearanceGate.TintedGate(Optional.of(TintAxis.WOOL), DyeColor.Vanilla.WHITE.argb());
            AppearanceOptions white = AppearanceOptions.builder()
                .tints(Map.of(TintAxis.WOOL, DyeColor.Vanilla.WHITE))
                .build();
            assertThat("wool resolves white to its wool colour, which differs from the dye colour baked here",
                wool.test(white), is(true));

            AppearanceGate collar = new AppearanceGate.TintedGate(Optional.of(TintAxis.COLLAR), DyeColor.Vanilla.RED.argb());
            AppearanceOptions red = AppearanceOptions.builder()
                .tints(Map.of(TintAxis.COLLAR, DyeColor.Vanilla.RED))
                .build();
            assertThat("every other axis resolves a dye to its own colour", collar.test(red), is(false));
        }

        @Test
        @DisplayName("reads only its own axis, not any dye the appearance carries")
        void readsOnlyItsOwnAxis() {
            AppearanceGate gate = new AppearanceGate.TintedGate(Optional.of(TintAxis.WOOL), UNDERCOAT_TINT);
            AppearanceOptions elsewhere = AppearanceOptions.builder()
                .tints(Map.of(TintAxis.COLLAR, DyeColor.Vanilla.LIME, TintAxis.BASE, DyeColor.Vanilla.CYAN))
                .build();
            assertThat(gate.test(elsewhere), is(false));
        }

        @Test
        @DisplayName("compares a custom dye by colour, so one matching the baked tint stays silent")
        void comparesACustomDyeByColour() {
            AppearanceGate gate = new AppearanceGate.TintedGate(Optional.of(TintAxis.WOOL), UNDERCOAT_TINT);
            AppearanceOptions same = AppearanceOptions.builder()
                .tints(Map.of(TintAxis.WOOL, DyeColor.of(0xE6E6E6)))
                .build();
            AppearanceOptions other = AppearanceOptions.builder()
                .tints(Map.of(TintAxis.WOOL, DyeColor.of(0x123456)))
                .build();
            assertThat("a custom dye equal to the baked tint is not a different colour", gate.test(same), is(false));
            assertThat(gate.test(other), is(true));
        }

        @Test
        @DisplayName("never fires with no axis, which is what a row naming an unowned token is built with")
        void neverFiresWithNoAxis() {
            // A tinted row whose tint_by resolves to no axis - blank, misspelled, or absent - is built
            // holding empty, so the gate it gets is one no selection can satisfy - silent rather than
            // unconditional.
            AppearanceOptions dyed = AppearanceOptions.builder()
                .tints(Map.of(TintAxis.WOOL, DyeColor.Vanilla.RED, TintAxis.COLLAR, DyeColor.Vanilla.RED))
                .build();
            assertThat(new AppearanceGate.TintedGate(Optional.empty(), UNDERCOAT_TINT).test(dyed), is(false));
        }

    }

    @Nested
    @DisplayName("FlagGate")
    class Flag {

        @Test
        @DisplayName("answers the sheared flag both ways round")
        void answersTheShearedFlag() {
            AppearanceOptions woolly = AppearanceOptions.defaults();
            AppearanceOptions shorn = AppearanceOptions.builder().sheared(true).build();
            assertThat(new AppearanceGate.FlagGate("sheared", false).test(woolly), is(true));
            assertThat(new AppearanceGate.FlagGate("sheared", false).test(shorn), is(false));
            assertThat(new AppearanceGate.FlagGate("sheared", true).test(woolly), is(false));
            assertThat(new AppearanceGate.FlagGate("sheared", true).test(shorn), is(true));
        }

        @Test
        @DisplayName("reads an unknown token as false rather than as unsatisfiable")
        void readsAnUnknownTokenAsFalse() {
            // Only "sheared" is wired, and every other token collapses to a constant false state. A row
            // gated on a token that is misspelt, renamed or newly emitted therefore does not go dark -
            // it draws unconditionally when it asks for false and never when it asks for true.
            AppearanceOptions shorn = AppearanceOptions.builder().sheared(true).build();
            assertThat("an unknown token asked for false renders whatever the appearance holds",
                new AppearanceGate.FlagGate("glowing", false).test(AppearanceOptions.defaults()), is(true));
            assertThat(new AppearanceGate.FlagGate("glowing", false).test(shorn), is(true));
            assertThat("an unknown token asked for true never renders",
                new AppearanceGate.FlagGate("glowing", true).test(AppearanceOptions.defaults()), is(false));
            assertThat(new AppearanceGate.FlagGate("glowing", true).test(shorn), is(false));
        }

        @Test
        @DisplayName("matches its token exactly, so a differently cased spelling is an unknown one")
        void matchesItsTokenExactly() {
            AppearanceOptions shorn = AppearanceOptions.builder().sheared(true).build();
            assertThat(new AppearanceGate.FlagGate("Sheared", true).test(shorn), is(false));
            assertThat(new AppearanceGate.FlagGate(" sheared", true).test(shorn), is(false));
        }

    }

    /**
     * One sealed arm alongside the two appearances that answer it opposite ways.
     *
     * @param gate the arm sample
     * @param fires an appearance the arm answers {@code true} for
     * @param silent an appearance the arm answers {@code false} for
     */
    private record ArmCase(AppearanceGate gate, AppearanceOptions fires, AppearanceOptions silent) {}

}
