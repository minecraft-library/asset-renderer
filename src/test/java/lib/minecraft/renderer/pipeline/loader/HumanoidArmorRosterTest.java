package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Cross-checks the roster the worn-armor gate depends on: the entities classified
 * {@code humanoidArmor} ({@code armor_type: "humanoid"}) must be exactly the vanilla
 * {@code HumanoidArmorLayer} wearers, so gating the armor render feature on the flag never drops
 * armor from an entity vanilla actually arms.
 */
@DisplayName("humanoidArmor roster")
class HumanoidArmorRosterTest {

    /** The 14 vanilla HumanoidArmorLayer wearers (player is rendered separately by PlayerRenderer). */
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
            if (entity.humanoidArmor()) flagged.add(id);
        });
        assertThat(flagged, is(new TreeSet<>(EXPECTED)));
    }

}
