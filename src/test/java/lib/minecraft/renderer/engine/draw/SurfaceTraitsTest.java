package lib.minecraft.renderer.engine.draw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of the two shipped {@link SurfaceTraits} constants, pinned component by component.
 * <p>
 * The record carries four {@code boolean} components in a row before its {@link PassDeclaration}, so
 * every call site is a positional run of the same type and a transposition of two of them compiles
 * silently. That is why the record is deliberately kept to one constructor. It leaves the shipped
 * constants themselves unguarded, though - swapping {@code translucent} for {@code glinted} in
 * {@link SurfaceTraits#WORN_SHELL} would still compile, would still yield a two-sided surface, and
 * would move the enchantment foil off the armour and onto nothing. These tests read each component
 * back by name, so a transposition anywhere in the two constants fails here rather than in a render.
 * Pinning all five components of both also pins the two bits armour geometry differs from block
 * geometry by, the cull and the glint.
 */
@DisplayName("Shipped surface traits")
class SurfaceTraitsTest {

    @Test
    @DisplayName("OPAQUE_BODY is culled, opaque, unglinted, directionally lit and on the default pass")
    void opaqueBodyIsCulledOpaqueAndUnglinted() {
        assertThat(SurfaceTraits.OPAQUE_BODY.cullBackFaces(), equalTo(true));
        assertThat(SurfaceTraits.OPAQUE_BODY.translucent(), equalTo(false));
        assertThat(SurfaceTraits.OPAQUE_BODY.glinted(), equalTo(false));
        assertThat(SurfaceTraits.OPAQUE_BODY.directionalLight(), equalTo(true));
        assertThat(SurfaceTraits.OPAQUE_BODY.pass(), sameInstance(PassDeclaration.DEFAULT));
    }

    @Test
    @DisplayName("WORN_SHELL is two-sided, opaque, glinted, directionally lit and on the default pass")
    void wornShellIsTwoSidedAndGlinted() {
        assertThat(SurfaceTraits.WORN_SHELL.cullBackFaces(), equalTo(false));
        assertThat(SurfaceTraits.WORN_SHELL.translucent(), equalTo(false));
        assertThat(SurfaceTraits.WORN_SHELL.glinted(), equalTo(true));
        assertThat(SurfaceTraits.WORN_SHELL.directionalLight(), equalTo(true));
        assertThat(SurfaceTraits.WORN_SHELL.pass(), sameInstance(PassDeclaration.DEFAULT));
    }

    @Test
    @DisplayName("withCullBackFaces rewrites only the cull flag")
    void withCullBackFacesKeepsTheOtherFourComponents() {
        SurfaceTraits derived = SurfaceTraits.WORN_SHELL.withCullBackFaces(true);

        assertThat(derived.cullBackFaces(), equalTo(true));
        assertThat(derived.translucent(), equalTo(SurfaceTraits.WORN_SHELL.translucent()));
        assertThat(derived.glinted(), equalTo(SurfaceTraits.WORN_SHELL.glinted()));
        assertThat(derived.directionalLight(), equalTo(SurfaceTraits.WORN_SHELL.directionalLight()));
        assertThat(derived.pass(), sameInstance(SurfaceTraits.WORN_SHELL.pass()));
    }

    @Test
    @DisplayName("withCullBackFaces returns itself when the flag already matches")
    void withCullBackFacesReturnsItselfOnAMatchingValue() {
        // The same identity semantics as the neighbouring PassDeclaration#withEmissive, so a caller
        // holding either kind of declaration can compare by identity and a no-op wither allocates
        // nothing in the per-triangle loops that call it.
        assertThat(SurfaceTraits.OPAQUE_BODY.withCullBackFaces(true),
            sameInstance(SurfaceTraits.OPAQUE_BODY));
        assertThat(SurfaceTraits.WORN_SHELL.withCullBackFaces(SurfaceTraits.WORN_SHELL.cullBackFaces()),
            sameInstance(SurfaceTraits.WORN_SHELL));

        // A genuine change still builds a new value.
        assertThat(SurfaceTraits.OPAQUE_BODY.withCullBackFaces(false),
            not(sameInstance(SurfaceTraits.OPAQUE_BODY)));
    }

    @Test
    @DisplayName("withDirectionalLight rewrites only the directional-light flag")
    void withDirectionalLightKeepsTheOtherFourComponents() {
        SurfaceTraits derived = SurfaceTraits.WORN_SHELL.withDirectionalLight(false);

        assertThat(derived.directionalLight(), equalTo(false));
        assertThat(derived.cullBackFaces(), equalTo(SurfaceTraits.WORN_SHELL.cullBackFaces()));
        assertThat(derived.translucent(), equalTo(SurfaceTraits.WORN_SHELL.translucent()));
        assertThat(derived.glinted(), equalTo(SurfaceTraits.WORN_SHELL.glinted()));
        assertThat(derived.pass(), sameInstance(SurfaceTraits.WORN_SHELL.pass()));
    }

    @Test
    @DisplayName("withDirectionalLight returns itself when the flag already matches")
    void withDirectionalLightReturnsItselfOnAMatchingValue() {
        // The same identity semantics withCullBackFaces has, so the two withers chain without either
        // of them allocating on the pass-through leg.
        assertThat(SurfaceTraits.OPAQUE_BODY.withDirectionalLight(true),
            sameInstance(SurfaceTraits.OPAQUE_BODY));
        assertThat(SurfaceTraits.WORN_SHELL.withDirectionalLight(SurfaceTraits.WORN_SHELL.directionalLight()),
            sameInstance(SurfaceTraits.WORN_SHELL));

        assertThat(SurfaceTraits.OPAQUE_BODY.withDirectionalLight(false),
            not(sameInstance(SurfaceTraits.OPAQUE_BODY)));
    }

    @Test
    @DisplayName("the two withers reach the flag they name and no other")
    void withersDoNotCollide() {
        // The two flags they write sit two positions apart in a run of four booleans, so a wither
        // wired to the wrong slot still compiles and still returns a plausible value. Composing both
        // onto one base names all four components once and pins that they landed where they were sent.
        SurfaceTraits derived = SurfaceTraits.OPAQUE_BODY.withCullBackFaces(false).withDirectionalLight(false);

        assertThat(derived.cullBackFaces(), equalTo(false));
        assertThat(derived.translucent(), equalTo(false));
        assertThat(derived.glinted(), equalTo(false));
        assertThat(derived.directionalLight(), equalTo(false));
    }

}
