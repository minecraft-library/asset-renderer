package lib.minecraft.renderer.pipeline.index;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.engine.kit.PoseEvaluator;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Which way a bone toggle points, read off the pose rather than off the member beside it.
 *
 * <p>A toggle names the state a subject is NOT resting in - a hornless goat, a turtle carrying an
 * egg - so which bones it shows and which it hides is decided by what the subject rests as. That is
 * something the model already says: a goat's horns draw because {@code hasLeftHorn} is what a goat's
 * render state is built holding. Reading it there rather than from a second declaration is what
 * keeps the two from drifting.
 *
 * <p>They HAD drifted. The shipped {@code default} agrees with the derived answer for every toggle
 * but the bee's, which is recorded here rather than smoothed over: it is a real disagreement about
 * whether a bee rests with its sting, and the pose is the side with the evidence.
 */
@DisplayName("a bone toggle's resting side")
class BoneToggleRestTest {

    /** Where the shipped declaration this is compared against lives. */
    private static final @NotNull String MODELS = "/lib/minecraft/renderer/entity_models.json";

    /**
     * The one toggle whose shipped declaration disagrees with what the model says.
     *
     * <p>{@code BeeRenderState} builds {@code hasStinger} at one, so a bee rests WITH its sting and
     * a toggle named for it hides one. The shipped member says the opposite, which made selecting
     * the toggle re-add a bone that was never removed - a toggle that did nothing.
     */
    private static final @NotNull String DRIFTED = "minecraft:bee/stinger";

    private static ConcurrentMap<String, Entity> entities;
    private static JsonObject models;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
        models = read().getAsJsonObject("models");
    }

    @Test
    @DisplayName("is what the model rests as, and the shipped member agrees everywhere but the bee")
    void theDerivedSideIsTheDeclaredOne() {
        Map<String, String> disagreed = new TreeMap<>();
        int compared = 0;

        for (Map.Entry<String, JsonElement> entry : models.entrySet()) {
            JsonObject bones = entry.getValue().getAsJsonObject().getAsJsonObject("bones");
            JsonObject toggles = bones == null ? null : bones.getAsJsonObject("toggles");
            if (toggles == null) continue;

            Entity entity = entities.get(entry.getKey());
            assertNotNull(entity, entry.getKey() + " is expected to load");

            for (Map.Entry<String, JsonElement> toggle : toggles.entrySet()) {
                Entity.BoneToggle held = entity.boneToggles().get(toggle.getKey());
                if (held == null) continue;
                boolean declared = toggle.getValue().getAsJsonObject().get("default").getAsBoolean();
                compared++;
                if (held.defaultVisible() != declared)
                    disagreed.put(entry.getKey() + "/" + toggle.getKey(),
                        "declared " + declared + ", the model rests " + held.defaultVisible());
            }
        }

        assertFalse(compared == 0, "the shipped toggles are expected to be walked, not skipped past");
        assertEquals(Map.of(DRIFTED, "declared false, the model rests true"), disagreed,
            "every toggle but the recorded one is derived to the side its shipped member declares");
    }

    @Test
    @DisplayName("points a goat at its horns and a donkey away from its chest")
    void theTwoDirectionsBothArrive() {
        // One of each, because a toggle that answered one way for everything would pass a test that
        // only looked at the other: a goat rests WITH the bones its toggle hides, and a donkey rests
        // WITHOUT the ones its toggle shows.
        assertEquals(true, toggleOf("minecraft:goat", "horn"), "a goat rests with its horns");
        assertEquals(false, toggleOf("minecraft:donkey", "chest"), "a donkey rests without its chest");
        assertEquals(true, toggleOf("minecraft:armor_stand", "base_plate"), "a stand rests on its plate");
        assertEquals(false, toggleOf("minecraft:armor_stand", "arms"), "and rests without its arms");
    }

    @Test
    @DisplayName("selecting one moves the bones it names, in whichever direction it rests")
    void aSelectedToggleMovesItsBones() {
        // A toggle that answered its own resting side would resolve to the mesh it started from and
        // read as working: the bones come back present because they were never removed. So what is
        // asserted is the CHANGE, per direction, rather than the state after.
        assertToggleMoves("minecraft:goat", "horn", "left_horn");
        assertToggleMoves("minecraft:donkey", "chest", "left_chest");
        assertToggleMoves("minecraft:armor_stand", "arms", "left_arm");
    }

    @Test
    @DisplayName("the bee rests without a bone its own model draws, which is why its toggle moves nothing")
    void theBeeRestsWithoutABoneItsModelDraws() {
        // The bee is the one subject where the mesh and the model disagree, and it is pinned rather
        // than smoothed over because the disagreement is the reason its toggle does nothing.
        //
        // BeeRenderState builds hasStinger at one, so AdultBeeModel draws the sting on a bee that
        // has not stung - which is every bee this renderer builds. The shipped hidden list strips
        // that bone anyway, so the resting mesh is missing something the model says is drawn, and a
        // toggle derived from the model then asks to remove a bone that was never there.
        //
        // It costs no pixels today: the sting is a single zero-width plane cube and the sweep gives
        // the bee identical coverage either way. The fix is to the hidden list rather than to
        // anything here, and it is deliberately not bundled with this change.
        Entity bee = entities.get("minecraft:bee");
        assertNotNull(bee, "minecraft:bee is expected to load");

        assertEquals(true, PoseEvaluator.drawsAtRest(bee.pose(), bee.model(), "stinger"),
            "the model draws the sting on a bee that has not stung");
        assertFalse(bee.model().getBones().containsKey("stinger"),
            "and the resting mesh does not carry it, which is the disagreement");
    }

    // ------------------------------------------------------------------------------------

    /** That selecting a toggle puts one of its bones on the other side of the mesh than it rests. */
    private static void assertToggleMoves(
        @NotNull String id, @NotNull String toggle, @NotNull String bone) {

        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        boolean atRest = entity.model().getBones().containsKey(bone);
        boolean selected = entity.resolve(AppearanceOptions.builder().toggles(Set.of(toggle)).build())
            .model().getBones().containsKey(bone);
        assertEquals(!atRest, selected,
            id + " '" + toggle + "' is expected to move " + bone + ", which rests " + (atRest ? "drawn" : "absent"));
    }

    private static boolean toggleOf(@NotNull String id, @NotNull String toggle) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        Entity.BoneToggle held = entity.boneToggles().get(toggle);
        assertNotNull(held, id + " is expected to declare a '" + toggle + "' toggle");
        return held.defaultVisible();
    }

    private static @NotNull JsonObject read() {
        try (InputStream source = BoneToggleRestTest.class.getResourceAsStream(MODELS)) {
            assertNotNull(source, "the shipped entity models are expected on the classpath");
            return new Gson().fromJson(
                new InputStreamReader(source, StandardCharsets.UTF_8), JsonObject.class);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read " + MODELS, error);
        }
    }

}
