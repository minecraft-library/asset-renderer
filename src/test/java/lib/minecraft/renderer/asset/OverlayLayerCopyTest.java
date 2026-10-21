package lib.minecraft.renderer.asset;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.pixel.BlendMode;
import lib.minecraft.renderer.asset.Entity.OverlayLayer;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.vanilla.appearance.AppearanceGate;
import lib.minecraft.renderer.vanilla.appearance.Flag;
import lib.minecraft.renderer.vanilla.appearance.TextureAxis;
import lib.minecraft.renderer.vanilla.appearance.TintAxis;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two copies an overlay pass is taken through - a pose swapped in for the one it shares with the
 * body, and one posed frame's meshes and declaration - each replacing what it names and carrying
 * every other component across.
 *
 * <p>The opacity is the component a positional rebuild loses in silence: a copy re-listing the
 * components through a constructor that predates it hands back a pass whose opacity is empty and whose
 * declaration still holds the loaded at-rest alpha, so the pass draws at that one value on every frame
 * and nothing fails.
 */
@DisplayName("an overlay pass's copies keep every component they do not replace")
class OverlayLayerCopyTest {

    @Test
    @DisplayName("a pose swap and a posed frame each keep the opacity and every other component")
    void everyCopyKeepsTheOpacity() {
        OverlayLayer pass = everyComponentSet();
        assertTrue(pass.opacity().isPresent(), "the fixture carries an opacity to lose");

        EntityPose swapped = pose();
        OverlayLayer repointed = pass.withPose(swapped);
        assertSame(swapped, repointed.pose(), "the swap carries the pose it names");
        assertSame(pass.opacity(), repointed.opacity(), "and the opacity the pass declared");
        assertEquals(new OverlayLayer(pass.model(), pass.textureRef(), pass.pass(), pass.tintArgb(),
                pass.skipBounds(), pass.tintBy(), pass.textureBy(), pass.gate(), pass.noHatModel(), swapped,
                pass.textureScroll(), pass.opacity()), repointed,
            "and nothing else moves");

        EntityMesh posedMesh = mesh("posed");
        Optional<EntityMesh> posedNoHat = Optional.of(mesh("posed_no_hat"));
        PassDeclaration frameDeclaration = pass.pass().withAlpha(0.125f);
        OverlayLayer posed = pass.posedAs(posedMesh, posedNoHat, frameDeclaration);
        assertSame(posedMesh, posed.model(), "the frame carries its posed mesh");
        assertSame(posedNoHat, posed.noHatModel(), "its posed alternate");
        assertSame(frameDeclaration, posed.pass(), "and the declaration it draws under");
        assertSame(pass.opacity(), posed.opacity(), "and the opacity the pass declared");
        assertEquals(new OverlayLayer(posedMesh, pass.textureRef(), frameDeclaration, pass.tintArgb(),
                pass.skipBounds(), pass.tintBy(), pass.textureBy(), pass.gate(), posedNoHat, pass.pose(),
                pass.textureScroll(), pass.opacity()), posed,
            "and nothing else moves");
    }

    // ------------------------------------------------------------------------------------

    /** A pass whose every component differs from what a delegating constructor would supply. */
    private static @NotNull OverlayLayer everyComponentSet() {
        return new OverlayLayer(mesh("body"), Optional.of("test/pass"),
            new PassDeclaration(true, BlendMode.ADD, 0.25f, false, true, true), 0xFF336699, true,
            TintAxis.findByToken("wool_color"), Optional.of(TextureAxis.PATTERN),
            Optional.of(new AppearanceGate.Selected(Flag.CHARGED, true)), Optional.of(mesh("no_hat")), pose(),
            Optional.of(new Vector2f(0.02f, 0f)), Optional.of(new PoseExpr.Input("ageInTicks")));
    }

    /** A mesh of one cubeless bone, named so two fixtures are told apart by value as well as identity. */
    private static @NotNull EntityMesh mesh(@NotNull String bone) {
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put(bone, new EntityMesh.Bone());
        return mesh;
    }

    /** A pose that writes nothing, distinct per call so a swap is assertable by identity. */
    private static @NotNull EntityPose pose() {
        return new EntityPose(Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableMap(),
            Concurrent.newUnmodifiableList(), Optional.empty());
    }

}
