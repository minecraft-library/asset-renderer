package lib.minecraft.renderer.option;

import lib.minecraft.renderer.option.spec.DyeColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * The two things a {@link TintAxis} computes rather than declares - the colour a selected
 * {@link DyeColor} draws as on that axis, and the {@code tint_by} token table that finds the axis.
 *
 * <p>{@link TintAxis#WOOL} is the only axis that overrides {@link TintAxis#resolve}, sending the dye
 * through {@link DyeColor#woolArgb()} instead of {@link DyeColor#argb()}, and that override is what
 * keeps the renderer free of a per-token branch. The claim under test is the wiring, not the wool
 * table itself: the axis has to reach that table, and the four remaining axes have to miss it. Both
 * halves are asserted over all sixteen vanilla dyes, so an override moved onto the wrong constant
 * fails here rather than in a render.
 *
 * <p>{@link TintAxis#ofToken(String)} is a linear scan over five tokens that answers empty for a null
 * and for anything it does not hold, including a token differing only in case - the tokens are the
 * keys the shipped model form writes, so a case-folding "fix" would start accepting spellings the
 * tooling never emits.
 */
@DisplayName("TintAxis dye resolution and token table")
class TintAxisTest {

    @Nested
    @DisplayName("axis colour")
    class AxisColour {

        /** Asserts the wool axis hands back the sheep table's colour and never the dye's own. */
        @Test
        @DisplayName("wool resolves through the sheep table for every vanilla dye")
        void woolResolvesThroughTheSheepTable() {
            for (DyeColor.Vanilla dye : DyeColor.Vanilla.values()) {
                assertThat(dye.name(), TintAxis.WOOL.resolve(dye), is(equalTo(dye.woolArgb())));
                // No vanilla dye's wool colour coincides with its own, so the override is reached for
                // all sixteen - a WOOL that had lost its body would fail on every one of them.
                assertThat(dye.name(), TintAxis.WOOL.resolve(dye), is(not(equalTo(dye.argb()))));
            }
        }

        /** Asserts white wool is vanilla's outright replacement rather than the three-quarter scale. */
        @Test
        @DisplayName("white wool is the outright override, not the scaled dye")
        void whiteWoolIsTheOutrightOverride() {
            assertThat(TintAxis.WOOL.resolve(DyeColor.Vanilla.WHITE), is(equalTo(0xFFE6E6E6)));
            // 0xF9FFFE put through the same three-quarter floor as the other fifteen. WHITE is the one
            // row vanilla replaces outright, so this value must never be what the axis answers.
            assertThat(TintAxis.WOOL.resolve(DyeColor.Vanilla.WHITE), is(not(equalTo(0xFFBABFBE))));
            assertThat(TintAxis.BASE.resolve(DyeColor.Vanilla.WHITE), is(equalTo(0xFFF9FFFE)));
        }

        /** Asserts the four non-wool axes tint by the dye's own colour. */
        @Test
        @DisplayName("every other axis tints by the dye's own colour")
        void everyOtherAxisTintsByTheDyeColour() {
            for (TintAxis axis : TintAxis.values()) {
                if (axis == TintAxis.WOOL) continue;

                for (DyeColor.Vanilla dye : DyeColor.Vanilla.values())
                    assertThat(axis + " / " + dye, axis.resolve(dye), is(equalTo(dye.argb())));
            }
        }

        /** Asserts a custom dye reaches wool by delegation, so the axis holds no scale of its own. */
        @Test
        @DisplayName("a custom dye reaches wool unscaled, the axis delegating rather than scaling")
        void customDyeReachesWoolUnscaled() {
            DyeColor custom = DyeColor.of(0x123456);
            assertThat(TintAxis.WOOL.resolve(custom), is(equalTo(0xFF123456)));
            assertThat(TintAxis.WOOL.resolve(custom), is(equalTo(TintAxis.COLLAR.resolve(custom))));
        }

    }

    @Nested
    @DisplayName("token table")
    class TokenTable {

        /** Asserts the five tokens the model form can name each find their own axis. */
        @Test
        @DisplayName("resolves each of the five tokens to its axis")
        void resolvesEachTokenToItsAxis() {
            assertThat(TintAxis.ofToken("base_color"), is(Optional.of(TintAxis.BASE)));
            assertThat(TintAxis.ofToken("pattern_color"), is(Optional.of(TintAxis.PATTERN)));
            assertThat(TintAxis.ofToken("wool_color"), is(Optional.of(TintAxis.WOOL)));
            assertThat(TintAxis.ofToken("collar_color"), is(Optional.of(TintAxis.COLLAR)));
            assertThat(TintAxis.ofToken("equipment_color"), is(Optional.of(TintAxis.EQUIPMENT)));
        }

        /** Asserts every axis finds itself through the token it carries. */
        @Test
        @DisplayName("round-trips every axis through its own token")
        void roundTripsEveryAxisThroughItsToken() {
            for (TintAxis axis : TintAxis.values())
                assertThat(axis.name(), TintAxis.ofToken(axis.token()), is(Optional.of(axis)));
        }

        /**
         * Asserts no two axes share a token, which the linear scan would otherwise resolve to whichever
         * is declared first.
         */
        @Test
        @DisplayName("gives every axis a token no other axis holds")
        void everyAxisHoldsADistinctToken() {
            List<String> tokens = Arrays.stream(TintAxis.values()).map(TintAxis::token).toList();
            assertThat("one token per axis", Set.copyOf(tokens).size(), is(equalTo(tokens.size())));
        }

        /** Asserts a null, an empty, an unknown and a differently-cased token all answer empty. */
        @Test
        @DisplayName("answers empty for a null, unknown or differently-cased token")
        void answersEmptyForAnythingElse() {
            assertThat(TintAxis.ofToken(null), is(Optional.<TintAxis>empty()));
            assertThat(TintAxis.ofToken(""), is(Optional.<TintAxis>empty()));
            assertThat(TintAxis.ofToken("wool"), is(Optional.<TintAxis>empty()));
            // The comparison is String.equals, so the table is case-sensitive by construction.
            assertThat(TintAxis.ofToken("WOOL_COLOR"), is(Optional.<TintAxis>empty()));
        }

    }

}
