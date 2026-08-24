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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which way a bone toggle points, read off the pose rather than off the member beside it.
 *
 * <p>A toggle names the state a subject is NOT resting in - a hornless goat, a turtle carrying an
 * egg - so which bones it shows and which it hides is decided by what the subject rests as. That is
 * something the model already says: a goat's horns draw because {@code hasLeftHorn} is what a goat's
 * render state is built holding. Reading it there rather than from a second declaration is what
 * keeps the two from drifting.
 *
 * <p>They HAD drifted, on whether a bee rests with its sting, which is why only one of them is left:
 * the shipped table declares which bones a toggle flips and says nothing about which way, and the
 * {@code hidden} list beside it keeps only the bones nothing ever draws.
 */
@DisplayName("a bone toggle's resting side")
class BoneToggleRestTest {

    /** Where the shipped declaration this is compared against lives. */
    private static final @NotNull String MODELS = "/lib/minecraft/renderer/entity_models.json";

    private static ConcurrentMap<String, Entity> entities;
    private static JsonObject models;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
        models = read().getAsJsonObject("models");
    }

    @Test
    @DisplayName("is the only place which way is written down, the shipped table saying only which bones")
    void theShippedTableDeclaresNoSide() {
        Map<String, String> declared = new TreeMap<>();
        int walked = 0;

        for (Map.Entry<String, JsonElement> entry : models.entrySet()) {
            JsonObject bones = entry.getValue().getAsJsonObject().getAsJsonObject("bones");
            JsonObject toggles = bones == null ? null : bones.getAsJsonObject("toggles");
            if (toggles == null) continue;

            for (Map.Entry<String, JsonElement> toggle : toggles.entrySet()) {
                walked++;
                for (String member : toggle.getValue().getAsJsonObject().keySet())
                    if (!"bones".equals(member))
                        declared.put(entry.getKey() + "/" + toggle.getKey(), member);
            }
        }

        assertFalse(walked == 0, "the shipped toggles are expected to be walked, not skipped past");
        assertEquals(Map.of(), declared, "a toggle names its bones and nothing about which way");
    }

    @Test
    @DisplayName("leaves the hidden list holding only bones no pose ever draws")
    void theHiddenListKeepsOnlyWhatNothingDraws() {
        // The other half of having one answer: a bone a state gates is not hidden here, because the
        // pose says whether it rests drawn. What is left is the bones nothing speaks for at all.
        Map<String, String> hidden = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : models.entrySet()) {
            JsonObject bones = entry.getValue().getAsJsonObject().getAsJsonObject("bones");
            JsonElement list = bones == null ? null : bones.get("hidden");
            if (list == null) continue;
            for (JsonElement bone : list.getAsJsonArray())
                hidden.merge(bone.getAsString(), entry.getKey(), (a, b) -> a + ", " + b);
        }
        assertEquals(Set.of("hat"), hidden.keySet(),
            "every remaining hidden bone is one nothing ever draws: " + hidden);
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
    @DisplayName("gives a bee the sting its own model draws, which the hidden list used to take away")
    void theBeeRestsWithTheStingItsModelDraws() {
        // The subject the two answers disagreed about. BeeRenderState builds hasStinger at one, so
        // AdultBeeModel draws the sting on a bee that has not stung - which is every bee this
        // renderer builds - and the hidden list stripped the bone anyway.
        //
        // It costs no pixels either way: the sting is a single zero-width plane cube, and the
        // vanilla reference is byte-identical whether the harness pins the bone drawn or hidden.
        // What it costs is the ability to say which of two answers was right, which is the whole
        // reason there is now one.
        Entity bee = entities.get("minecraft:bee");
        assertNotNull(bee, "minecraft:bee is expected to load");

        assertEquals(true, PoseEvaluator.drawsAtRest(bee.pose(), bee.model(), "stinger"),
            "the model draws the sting on a bee that has not stung");
        assertTrue(bee.model().getBones().containsKey("stinger"), "and the resting mesh carries it");
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
