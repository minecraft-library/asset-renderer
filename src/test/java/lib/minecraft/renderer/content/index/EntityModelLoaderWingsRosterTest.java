package lib.minecraft.renderer.content.index;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.request.AppearanceOptions;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Cross-check of the roster the wings gate depends on: the entities {@link EntityModelLoader} marks
 * with {@link Entity.Layers#wings()} must be exactly the rows whose vanilla renderer builds a
 * {@code WingsLayer}, so an elytra selection draws on those and on nothing else.
 * <p>
 * The roster is not the armour one: the giant's renderer builds a {@code HumanoidArmorLayer} and no
 * {@code WingsLayer}, so it carries a shell and no wings.
 */
@DisplayName("wings roster")
class EntityModelLoaderWingsRosterTest {

    /** The rows whose vanilla renderer builds a {@code WingsLayer}; the player rig sets its own */
    private static final Set<String> EXPECTED = Set.of(
        "minecraft:armor_stand", "minecraft:bogged", "minecraft:drowned", "minecraft:husk",
        "minecraft:parched", "minecraft:piglin", "minecraft:piglin_brute", "minecraft:skeleton",
        "minecraft:stray", "minecraft:wither_skeleton", "minecraft:zombie",
        "minecraft:zombie_villager", "minecraft:zombified_piglin");

    @Test
    @DisplayName("wings entities are exactly the vanilla WingsLayer wearers")
    void rosterMatches() {
        ConcurrentMap<String, Entity> index = EntityModelLoader.load();
        Set<String> flagged = new TreeSet<>();
        index.forEach((id, entity) -> {
            if (entity.layers().wings()) flagged.add(id);
        });
        assertThat(flagged, is(new TreeSet<>(EXPECTED)));
    }

    @Test
    @DisplayName("the giant wears armour and no wings")
    void giantWearsArmourAndNoWings() {
        Entity giant = EntityModelLoader.load().get("minecraft:giant");
        assertThat(giant.humanoidArmor().isPresent(), is(true));
        assertThat(giant.layers().wings(), is(false));
    }

    @Test
    @DisplayName("every form of a wings row keeps the wings - its baby form, and the row an appearance resolves at either age")
    void everyFormOfAWingsRowKeepsTheWings() {
        ConcurrentMap<String, Entity> index = EntityModelLoader.load();
        AppearanceOptions adult = AppearanceOptions.builder().build();
        AppearanceOptions baby = AppearanceOptions.builder().age(Age.BABY).build();
        Set<String> babyForms = new TreeSet<>();
        for (String id : EXPECTED) {
            Entity row = index.get(id);
            row.axes().baby().ifPresent(form -> {
                babyForms.add(id);
                assertThat(id + " baby form", form.layers().wings(), is(true));
            });
            assertThat(id + " resolved as an adult", adult.resolve(row).layers().wings(), is(true));
            assertThat(id + " resolved as a baby", baby.resolve(row).layers().wings(), is(true));
        }
        assertThat("the roster carries baby forms to check", babyForms.isEmpty(), is(false));
    }

}
