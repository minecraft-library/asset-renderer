package lib.minecraft.renderer.asset;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.Drawn;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The subject-side halves of the style catalog's data model - the styles component's default, and
 * the drawn pairing.
 *
 * <p>The pairing is asserted by identity, mesh for mesh and pose for pose: the body first, each
 * overlay pass under its own pose, and a suppressed pass's no-hat alternate directly after the pass
 * it stands in for, under that pass's pose.
 */
@DisplayName("an entity's styles default and drawn pairing")
class EntityDrawnTest {

    @Test
    @DisplayName("a definition built without styles carries the bind-only catalog")
    void builderWithoutStylesAnswersBindOnly() {
        Entity entity = Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(new EntityMesh())
            .build();
        assertSame(StyleCatalog.BIND_ONLY, entity.styles());
    }

    @Test
    @DisplayName("a definition built from the id and mesh alone normalises everything else")
    void builderFromTheMinimalSetNormalisesTheRest() {
        // The id and the mesh are the two components the constructor cannot invent; the five it
        // normalises arrive unset and the definition still draws.
        EntityMesh body = new EntityMesh();
        Entity entity = Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(body)
            .build();

        ConcurrentList<Drawn> drawn = entity.drawn();
        assertEquals(1, drawn.size(), "the body alone");
        assertSame(body, drawn.getFirst().model());
        assertSame(EntityPose.NONE, drawn.getFirst().pose(),
            "an unset pose is the pose of a model that poses nothing");
        assertTrue(entity.blockOverlays().isEmpty(), "no block overlays");
        assertTrue(entity.members().isEmpty(), "and no canvas group");
    }

    @Test
    @DisplayName("drawn pairs the body, each pass and each no-hat alternate with its own pose")
    void drawnPairsEveryMeshWithItsOwnPose() {
        EntityMesh body = new EntityMesh();
        EntityMesh hatted = new EntityMesh();
        EntityMesh noHat = new EntityMesh();
        EntityMesh plain = new EntityMesh();
        EntityPose bodyPose = pose();
        EntityPose hattedPose = pose();
        EntityPose plainPose = pose();

        Entity subject = Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(body)
            .pose(bodyPose)
            .overlays(Concurrent.newUnmodifiableList(
                overlay(hatted, Optional.of(noHat), hattedPose),
                overlay(plain, Optional.empty(), plainPose)))
            .build();

        ConcurrentList<Drawn> drawn = subject.drawn();
        assertEquals(4, drawn.size(), "a body, two passes and one alternate");
        assertSame(body, drawn.getFirst().model(), "the body first");
        assertSame(bodyPose, drawn.getFirst().pose(), "under the subject's own pose");
        assertSame(hatted, drawn.get(1).model(), "then the first pass");
        assertSame(hattedPose, drawn.get(1).pose(), "under its own pose");
        assertSame(noHat, drawn.get(2).model(), "its alternate beside it");
        assertSame(hattedPose, drawn.get(2).pose(), "under the pose of the pass it stands in for");
        assertSame(plain, drawn.getLast().model(), "and the alternate-less pass alone");
        assertSame(plainPose, drawn.getLast().pose(), "under its own pose");
    }

    @Test
    @DisplayName("a subject with no overlays draws its body alone")
    void aBareSubjectDrawsItsBodyAlone() {
        EntityMesh body = new EntityMesh();
        EntityPose bodyPose = pose();
        Entity subject = Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(body)
            .pose(bodyPose)
            .overlays(Concurrent.newUnmodifiableList())
            .build();

        ConcurrentList<Drawn> drawn = subject.drawn();
        assertEquals(1, drawn.size());
        assertSame(body, drawn.getFirst().model());
        assertSame(bodyPose, drawn.getFirst().pose());
    }

    // ------------------------------------------------------------------------------------

    /** A pose that writes nothing, distinct per call so the pairing is assertable by identity. */
    private static @NotNull EntityPose pose() {
        return new EntityPose(Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableMap(),
            Concurrent.newUnmodifiableList(), Optional.empty());
    }

    private static Entity.@NotNull OverlayLayer overlay(
        @NotNull EntityMesh model, @NotNull Optional<EntityMesh> noHat,
        @NotNull EntityPose pose) {

        return new Entity.OverlayLayer(model, Optional.empty(), PassDeclaration.DEFAULT,
            0xFFFFFFFF, false, Optional.empty(), Optional.empty(), Optional.empty(), noHat, pose);
    }

}
