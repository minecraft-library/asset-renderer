package lib.minecraft.renderer.vanilla.appearance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * The twelve texture refs {@link TropicalFishPattern} builds in its constructor, spelled out one by
 * one.
 *
 * <p>This is the one option enum that computes its texture path rather than carrying it as a
 * constant, so the whole set is a single format string over a shape letter and an index. A slip in
 * either operand renames all twelve at once, and the renderer takes the result straight from
 * {@link TropicalFishPattern#overlayTexture()} onto the pattern pass, where a missing texture drops
 * silently rather than raising. Pinning the literal refs is the only thing that separates the twelve
 * files vanilla ships from twelve plausible-looking names.
 *
 * <p>The twelve literals pin the shape split with the refs: the six
 * {@link TropicalFishPattern.Shape#SMALL} patterns index one to six under the {@code a} letter and
 * the six {@link TropicalFishPattern.Shape#LARGE} do the same under {@code b}, so a collision, a wrong
 * index, a changed letter or a pattern moved to the other shape renames at least one of them.
 */
@DisplayName("TropicalFishPattern computed texture refs")
class TropicalFishPatternTest {

    @Nested
    @DisplayName("texture refs")
    class TextureRefs {

        /** Asserts the six small-body patterns name the {@code tropical_a} textures in index order. */
        @Test
        @DisplayName("the small body's six patterns name the tropical_a textures")
        void smallBodyPatternsNameTheTropicalATextures() {
            assertThat(TropicalFishPattern.KOB.overlayTexture(), is(equalTo("fish/tropical_a_pattern_1")));
            assertThat(TropicalFishPattern.SUNSTREAK.overlayTexture(), is(equalTo("fish/tropical_a_pattern_2")));
            assertThat(TropicalFishPattern.SNOOPER.overlayTexture(), is(equalTo("fish/tropical_a_pattern_3")));
            assertThat(TropicalFishPattern.DASHER.overlayTexture(), is(equalTo("fish/tropical_a_pattern_4")));
            assertThat(TropicalFishPattern.BRINELY.overlayTexture(), is(equalTo("fish/tropical_a_pattern_5")));
            assertThat(TropicalFishPattern.SPOTTY.overlayTexture(), is(equalTo("fish/tropical_a_pattern_6")));
        }

        /** Asserts the six large-body patterns name the {@code tropical_b} textures in index order. */
        @Test
        @DisplayName("the large body's six patterns name the tropical_b textures")
        void largeBodyPatternsNameTheTropicalBTextures() {
            assertThat(TropicalFishPattern.FLOPPER.overlayTexture(), is(equalTo("fish/tropical_b_pattern_1")));
            assertThat(TropicalFishPattern.STRIPEY.overlayTexture(), is(equalTo("fish/tropical_b_pattern_2")));
            assertThat(TropicalFishPattern.GLITTER.overlayTexture(), is(equalTo("fish/tropical_b_pattern_3")));
            assertThat(TropicalFishPattern.BLOCKFISH.overlayTexture(), is(equalTo("fish/tropical_b_pattern_4")));
            assertThat(TropicalFishPattern.BETTY.overlayTexture(), is(equalTo("fish/tropical_b_pattern_5")));
            assertThat(TropicalFishPattern.CLAYFISH.overlayTexture(), is(equalTo("fish/tropical_b_pattern_6")));
        }

    }

    @Nested
    @DisplayName("name lookup")
    class NameLookup {

        /** Asserts a pattern name resolves whatever its case. */
        @Test
        @DisplayName("resolves a pattern name in any case")
        void resolvesAPatternNameInAnyCase() {
            assertThat(TropicalFishPattern.ofName("kob"), is(TropicalFishPattern.KOB));
            assertThat(TropicalFishPattern.ofName("KOB"), is(TropicalFishPattern.KOB));
            assertThat(TropicalFishPattern.ofName("Clayfish"), is(TropicalFishPattern.CLAYFISH));
        }

        /** Asserts a null, an empty and an unknown name all answer null rather than raising. */
        @Test
        @DisplayName("answers null for a null, empty or unknown name")
        void answersNullForAnythingElse() {
            assertThat(TropicalFishPattern.ofName(null), is(nullValue()));
            assertThat(TropicalFishPattern.ofName(""), is(nullValue()));
            assertThat(TropicalFishPattern.ofName("mackerel"), is(nullValue()));
        }

    }

}
