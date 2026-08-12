package lib.minecraft.renderer.engine.raster;

import dev.simplified.image.pixel.BlendMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of the {@link PassDeclaration#DEFAULT default pass} every body, block and item draws
 * through, and of the independence of the three flags it carries.
 * <p>
 * {@code emissive}, {@code writesDepth} and {@code sorted} are three separate things vanilla declares -
 * a shader define, a depth-stencil state and a sort-on-upload flag - and one entity can split them
 * across its own two passes, an eyes pass that does not write depth beside a body pass that does.
 * Inferring any one of them from another loses exactly the passes that disagree, so what is pinned
 * here is that all eight combinations are constructible, distinct and read back unaltered. A future
 * normalization - forcing the sort off when nothing writes depth, say - collapses two of the eight and
 * fails here.
 * <p>
 * {@link PassDeclaration#withEmissive} carries a derived-value contract of its own: it returns
 * {@code this} when the flag already matches, so a caller re-declaring a pass that is already shaded
 * allocates nothing. That short-circuit is asserted by identity, which is what makes it a contract
 * rather than an implementation detail.
 */
@DisplayName("Pass declaration")
class PassDeclarationTest {

    @Test
    @DisplayName("DEFAULT is shaded, source-over, fully opaque, depth-writing and unsorted")
    void defaultIsShadedSourceOverOpaqueDepthWritingAndUnsorted() {
        assertThat(PassDeclaration.DEFAULT.emissive(), equalTo(false));
        assertThat(PassDeclaration.DEFAULT.blend(), equalTo(BlendMode.NORMAL));
        assertThat(PassDeclaration.DEFAULT.alpha(), equalTo(1f));
        assertThat(PassDeclaration.DEFAULT.writesDepth(), equalTo(true));
        assertThat(PassDeclaration.DEFAULT.sorted(), equalTo(false));
    }

    @Test
    @DisplayName("DEFAULT equals the declaration written out by hand")
    void defaultEqualsTheHandWrittenDeclaration() {
        assertThat(PassDeclaration.DEFAULT,
            equalTo(new PassDeclaration(false, BlendMode.NORMAL, 1f, true, false)));
    }

    @Test
    @DisplayName("all eight combinations of the three flags are distinct and read back unaltered")
    void theThreeFlagsAreIndependent() {
        Set<PassDeclaration> distinct = new HashSet<>();
        for (boolean emissive : new boolean[] {false, true}) {
            for (boolean writesDepth : new boolean[] {false, true}) {
                for (boolean sorted : new boolean[] {false, true}) {
                    PassDeclaration pass =
                        new PassDeclaration(emissive, BlendMode.NORMAL, 1f, writesDepth, sorted);
                    String where = emissive + "/" + writesDepth + "/" + sorted;
                    assertThat(where, pass.emissive(), equalTo(emissive));
                    assertThat(where, pass.writesDepth(), equalTo(writesDepth));
                    assertThat(where, pass.sorted(), equalTo(sorted));
                    distinct.add(pass);
                }
            }
        }
        assertThat(distinct.size(), equalTo(8));
    }

    @Test
    @DisplayName("one subject's two passes disagree on emissive and on the depth write together")
    void twoPassesOfOneSubjectSplitEmissiveFromTheDepthWrite() {
        // The shape vanilla actually ships: a full-bright overlay that leaves the depth buffer alone,
        // beside a shaded pass that writes it. Neither flag can be read off the other.
        PassDeclaration eyes = new PassDeclaration(true, BlendMode.NORMAL, 1f, false, false);
        PassDeclaration body = new PassDeclaration(false, BlendMode.NORMAL, 1f, true, false);

        assertThat(eyes.emissive(), equalTo(true));
        assertThat(eyes.writesDepth(), equalTo(false));
        assertThat(body.emissive(), equalTo(false));
        assertThat(body.writesDepth(), equalTo(true));
        assertThat(eyes, not(equalTo(body)));
    }

    @Test
    @DisplayName("blend and alpha are carried independently of the three flags")
    void blendAndAlphaRideAlongsideTheFlagsRatherThanFollowingThem() {
        PassDeclaration additive = new PassDeclaration(true, BlendMode.ADD, 0.25f, true, true);

        assertThat(additive.blend(), equalTo(BlendMode.ADD));
        assertThat(additive.alpha(), equalTo(0.25f));
        assertThat(additive.emissive(), equalTo(true));
        assertThat(additive.writesDepth(), equalTo(true));
        assertThat(additive.sorted(), equalTo(true));

        PassDeclaration cutout = new PassDeclaration(false, BlendMode.REPLACE, 1f, true, false);
        assertThat(cutout.blend(), equalTo(BlendMode.REPLACE));
        assertThat(cutout, not(equalTo(PassDeclaration.DEFAULT)));
    }

    @Test
    @DisplayName("withEmissive returns the same instance when the flag already matches")
    void withEmissiveShortCircuitsOnAMatchingFlag() {
        assertThat(PassDeclaration.DEFAULT.withEmissive(false), sameInstance(PassDeclaration.DEFAULT));

        PassDeclaration lit = new PassDeclaration(true, BlendMode.ADD, 0.25f, false, true);
        assertThat(lit.withEmissive(true), sameInstance(lit));
    }

    @Test
    @DisplayName("withEmissive rewrites only the emissive flag")
    void withEmissiveKeepsTheOtherFourComponents() {
        PassDeclaration source = new PassDeclaration(false, BlendMode.ADD, 0.25f, false, true);
        PassDeclaration derived = source.withEmissive(true);

        assertThat(derived, not(sameInstance(source)));
        assertThat(derived.emissive(), equalTo(true));
        assertThat(derived.blend(), equalTo(source.blend()));
        assertThat(derived.alpha(), equalTo(source.alpha()));
        assertThat(derived.writesDepth(), equalTo(source.writesDepth()));
        assertThat(derived.sorted(), equalTo(source.sorted()));
    }

}
