package lib.minecraft.renderer.engine.raster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of the two shipped {@link SurfaceTraits} constants, pinned component by component.
 * <p>
 * The record carries three {@code boolean} components in a row before its {@link PassDeclaration}, so
 * every call site is a positional triple of the same type and a transposition of two of them compiles
 * silently. That is why the record is deliberately kept to one constructor. It leaves the shipped
 * constants themselves unguarded, though - swapping {@code translucent} for {@code glinted} in
 * {@link SurfaceTraits#WORN_SHELL} would still compile, would still yield a two-sided surface, and
 * would move the enchantment foil off the armour and onto nothing. These tests read each component
 * back by name, so a transposition anywhere in the two constants fails here rather than in a render.
 * <p>
 * The difference between the two constants is also load-bearing and stated in prose: armour geometry
 * differs from block geometry by two bits and not by a code path. That is asserted here as a bit
 * count rather than left to the reader.
 */
@DisplayName("Shipped surface traits")
class SurfaceTraitsTest {

    @Test
    @DisplayName("OPAQUE_BODY is culled, opaque, unglinted and on the default pass")
    void opaqueBodyIsCulledOpaqueAndUnglinted() {
        assertThat(SurfaceTraits.OPAQUE_BODY.cullBackFaces(), equalTo(true));
        assertThat(SurfaceTraits.OPAQUE_BODY.translucent(), equalTo(false));
        assertThat(SurfaceTraits.OPAQUE_BODY.glinted(), equalTo(false));
        assertThat(SurfaceTraits.OPAQUE_BODY.pass(), sameInstance(PassDeclaration.DEFAULT));
    }

    @Test
    @DisplayName("WORN_SHELL is two-sided, opaque, glinted and on the default pass")
    void wornShellIsTwoSidedAndGlinted() {
        assertThat(SurfaceTraits.WORN_SHELL.cullBackFaces(), equalTo(false));
        assertThat(SurfaceTraits.WORN_SHELL.translucent(), equalTo(false));
        assertThat(SurfaceTraits.WORN_SHELL.glinted(), equalTo(true));
        assertThat(SurfaceTraits.WORN_SHELL.pass(), sameInstance(PassDeclaration.DEFAULT));
    }

    @Test
    @DisplayName("the two constants differ in exactly the cull and glint bits")
    void wornShellDiffersFromOpaqueBodyInExactlyTwoBits() {
        SurfaceTraits body = SurfaceTraits.OPAQUE_BODY;
        SurfaceTraits shell = SurfaceTraits.WORN_SHELL;

        assertThat(shell.cullBackFaces(), not(equalTo(body.cullBackFaces())));
        assertThat(shell.glinted(), not(equalTo(body.glinted())));
        assertThat(shell.translucent(), equalTo(body.translucent()));
        assertThat(shell.pass(), sameInstance(body.pass()));

        // Restated as a count so a third differing bit fails here even if the three assertions above
        // are left alone.
        int differing = (shell.cullBackFaces() == body.cullBackFaces() ? 0 : 1)
            + (shell.translucent() == body.translucent() ? 0 : 1)
            + (shell.glinted() == body.glinted() ? 0 : 1)
            + (shell.pass().equals(body.pass()) ? 0 : 1);
        assertThat(differing, equalTo(2));
    }

    @Test
    @DisplayName("withCullBackFaces rewrites only the cull flag")
    void withCullBackFacesKeepsTheOtherThreeComponents() {
        SurfaceTraits derived = SurfaceTraits.WORN_SHELL.withCullBackFaces(true);

        assertThat(derived.cullBackFaces(), equalTo(true));
        assertThat(derived.translucent(), equalTo(SurfaceTraits.WORN_SHELL.translucent()));
        assertThat(derived.glinted(), equalTo(SurfaceTraits.WORN_SHELL.glinted()));
        assertThat(derived.pass(), sameInstance(SurfaceTraits.WORN_SHELL.pass()));
    }

    @Test
    @DisplayName("withCullBackFaces builds a fresh equal value even when the flag already matches")
    void withCullBackFacesAllocatesEvenWhenTheFlagAlreadyMatches() {
        // Observed, and the opposite of the neighbouring PassDeclaration#withEmissive, which returns
        // itself on a matching value. Anything comparing traits by identity rather than by equality
        // sees a different instance here, so the comparison has to be equality.
        SurfaceTraits same = SurfaceTraits.OPAQUE_BODY.withCullBackFaces(true);

        assertThat(same, equalTo(SurfaceTraits.OPAQUE_BODY));
        assertThat(same, not(sameInstance(SurfaceTraits.OPAQUE_BODY)));
    }

}
