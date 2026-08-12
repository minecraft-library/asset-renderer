package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Cross-check of the roster the worn-armor gate depends on: the entities
 * {@link EntityModelLoader} gives an {@link Entity#humanoidArmor} shell must be exactly the vanilla
 * {@code HumanoidArmorLayer} wearers, so gating the armor render feature on the shell never drops
 * armor from an entity vanilla actually arms.
 * <p>
 * Being armored IS carrying a resolved shell, with no separate classification a wearer could hold
 * while its mesh failed to resolve, so a tooling walk that stops naming a wearer's armor set, or a
 * geometry reference that stops resolving, drops that wearer off the roster here.
 */
@DisplayName("humanoidArmor roster")
class HumanoidArmorRosterTest {

    /** The vanilla {@code HumanoidArmorLayer} wearers; {@link PlayerRenderer} arms the player */
    private static final Set<String> EXPECTED = Set.of(
        "minecraft:armor_stand", "minecraft:bogged", "minecraft:drowned", "minecraft:giant",
        "minecraft:husk", "minecraft:parched", "minecraft:piglin", "minecraft:piglin_brute",
        "minecraft:skeleton", "minecraft:stray", "minecraft:wither_skeleton", "minecraft:zombie",
        "minecraft:zombie_villager", "minecraft:zombified_piglin");

    @Test
    @DisplayName("humanoidArmor entities are exactly the vanilla HumanoidArmorLayer wearers")
    void rosterMatches() {
        ConcurrentMap<String, Entity> index = EntityModelLoader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));
        Set<String> flagged = new TreeSet<>();
        index.forEach((id, entity) -> {
            if (entity.humanoidArmor().isPresent()) flagged.add(id);
        });
        assertThat(flagged, is(new TreeSet<>(EXPECTED)));
    }

}
