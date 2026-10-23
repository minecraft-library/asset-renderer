package lib.minecraft.renderer.bake.armor;

import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.vanilla.equipment.ArmorSlot;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The order a worn shell's parts are walked in, which is the order a coplanar tie between two of them
 * is decided by.
 *
 * <p>The walk takes a shell's bones in the order its mesh holds them, and the table holds each shell in
 * the order vanilla's baked slot model draws it. Every armour set fans out of one base mesh, so a slot
 * draws its parts in the order that mesh's root iterates them. Three sets are registered through a
 * whole-mesh {@code MeshTransformer.scaling} - giant, husk and wither skeleton - and the scaling
 * rebuilds the root with a {@code putAll} copy, which iterates the six humanoid parts in eight slots:
 * {@code head, right_arm, left_leg, body, left_arm, right_leg}. Each of the three is dressed in a mesh
 * of its own, written in that order. The other sets keep the sixteen-slot root,
 * {@code head, right_arm, left_leg, left_arm, right_leg, body}.
 */
@DisplayName("a worn shell walks its parts in vanilla's draw order")
class ShellIndexTest {

    @Test
    @DisplayName("the walk takes every shell's bones in the order its mesh holds them")
    void theWalkReadsTheMeshInOrder() {
        List<Shell> shells = new ArrayList<>();
        EntityModelLoader.load().forEach((id, entity) -> entity.humanoidArmor().ifPresent(shell -> {
            shells.add(shell);
            shell.alternate().ifPresent(alternate -> shells.add(alternate.shell()));
        }));
        assertThat("the corpus dresses wearers", shells.isEmpty(), equalTo(false));

        for (Shell shell : shells) {
            List<String> held = shell.mesh()
                .getBones()
                .entrySet()
                .stream()
                .filter(entry -> !entry.getValue().getCubes().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
            List<String> walked = ShellIndex.of(shell)
                .parts()
                .stream()
                .map(WornBox::trace)
                .distinct()
                .toList();
            assertThat("the walk follows the mesh", walked, equalTo(held));
        }
    }

    @Test
    @DisplayName("a set registered through a whole-mesh scaling draws the body between the arms and between the legs")
    void aScaledSetDrawsItsCopiedRoot() {
        for (String wearer : List.of("minecraft:giant", "minecraft:husk", "minecraft:wither_skeleton")) {
            Shell shell = shellOf(wearer);
            assertThat(wearer + " chestplate", drawn(shell, ArmorSlot.CHESTPLATE),
                equalTo(List.of("right_arm", "body", "left_arm")));
            assertThat(wearer + " leggings", drawn(shell, ArmorSlot.LEGGINGS),
                equalTo(List.of("left_leg", "body", "right_leg")));
            assertThat(wearer + " is dressed in a mesh of its own", shell.mesh(),
                not(equalTo(shellOf("minecraft:zombie").mesh())));
        }
    }

    @Test
    @DisplayName("an unscaled set draws the body last")
    void anUnscaledSetDrawsTheBodyLast() {
        for (String wearer : List.of("minecraft:zombie", "minecraft:skeleton", "minecraft:armor_stand")) {
            Shell shell = shellOf(wearer);
            assertThat(wearer + " chestplate", drawn(shell, ArmorSlot.CHESTPLATE),
                equalTo(List.of("right_arm", "left_arm", "body")));
            assertThat(wearer + " leggings", drawn(shell, ArmorSlot.LEGGINGS),
                equalTo(List.of("left_leg", "right_leg", "body")));
        }
    }

    /** The shell a wearer is dressed in, read through the loaded index. */
    private static @NotNull Shell shellOf(@NotNull String wearer) {
        return EntityModelLoader.load().get(wearer).humanoidArmor().orElseThrow();
    }

    /** The bones one slot's armour draws, in the order the walk emits their cubes. */
    private static @NotNull List<String> drawn(@NotNull Shell shell, @NotNull ArmorSlot slot) {
        return ShellIndex.of(shell)
            .parts()
            .stream()
            .filter(WornBox.Mesh.class::isInstance)
            .map(WornBox.Mesh.class::cast)
            .filter(part -> part.slots().contains(slot))
            .map(WornBox.Mesh::trace)
            .distinct()
            .toList();
    }

}
