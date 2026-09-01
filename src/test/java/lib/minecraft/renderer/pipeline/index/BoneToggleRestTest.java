package lib.minecraft.renderer.pipeline.index;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which way a bone toggle points, derived rather than declared beside it.
 *
 * <p>A toggle names the state a subject is NOT resting in - a hornless goat, a turtle carrying an
 * egg - so which bones it shows and which it hides is decided by what the subject rests as. That is
 * the mesh's own answer: a bone stands at a rest visibility and names the selection that flips it,
 * and reading the direction off the bone that renders is what keeps it from drifting.
 *
 * <p>It HAD drifted, on whether a bee rests with its sting, which is why there is one answer left.
 * The model table names no direction because it no longer names a toggle at all; what a subject
 * rests without is written where it renders.
 *
 * <p>A bone nothing can ever draw is absent from the mesh rather than standing hidden in it, so
 * "rests without" reads two ways here on purpose: an evoker's arms are gone, and an armour stand's
 * are present and not drawn, because a selection can ask for the second and nothing can ask for the
 * first.
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
    @DisplayName("is the only place which way is written down, the model table naming no toggle at all")
    void theShippedTableDeclaresNoSide() {
        Map<String, String> declared = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : models.entrySet())
            for (String member : boneMembers(entry.getValue().getAsJsonObject()))
                if ("toggles".equals(member) || "undrawn".equals(member))
                    declared.put(entry.getKey(), member);

        assertEquals(Map.of(), declared,
            "what a subject rests without is the mesh's answer, so no row restates it");

        int toggled = 0;
        for (Entity entity : entities.values())
            for (EntityModelData.Bone bone : entity.model().getBones().values())
                if (bone.getToggle() != null) toggled++;
        assertFalse(toggled == 0, "the shipped meshes are expected to name selections, not skip past");
    }

    @Test
    @DisplayName("leaves a bone a selection can ask for standing hidden, and drops one nothing can")
    void theMeshCarriesWhatASelectionCanStillReach() {
        // An armour stand rests armless, and its arms toggle can ask for them - so they stand in the
        // mesh, not drawn. The hat nothing ever draws is gone: no selection names it.
        assertEquals(List.of("left_arm", "right_arm"), restingHidden("minecraft:armor_stand"),
            "a stand rests armless, and a selection can put the arms back");
        assertFalse(hasBone("minecraft:armor_stand", "hat"),
            "the hat nothing ever draws is absent rather than hidden");

        // An evoker rests with its arms crossed and declares no toggle, so nothing can ask for the
        // pair it hangs and the mesh does not carry them.
        for (String bone : List.of("hat", "left_arm", "right_arm"))
            assertFalse(hasBone("minecraft:evoker", bone),
                "an evoker's " + bone + " is drawn by nothing and asked for by nothing");
        assertEquals(List.of(), restingHidden("minecraft:evoker"),
            "so it rests hiding nothing - what it rests without simply is not there");

        // A frog's croak sac is the third shape: drawn by nothing at rest, and asked for by a
        // selection - so it is KEPT and hidden rather than dropped. Its gate is an animation state
        // rather than a boolean field, which is the one place the two shapes are told apart, so it
        // is here to catch a generator that stopped reading that gate and dropped the bone again.
        assertTrue(hasBone("minecraft:frog", "croaking_body"),
            "a frog keeps the sac a croak selection draws");
        assertEquals(List.of("croaking_body"), restingHidden("minecraft:frog"),
            "and rests it hidden, which is the frog no croak has been started on");

        assertEquals(List.of(), restingHidden("minecraft:goat"),
            "a goat rests with everything its toggles flip");
    }

    @Test
    @DisplayName("points a goat at its horns and a donkey away from its chest")
    void theTwoDirectionsBothArrive() {
        // One of each, because a toggle that answered one way for everything would pass a test that
        // only looked at the other: a goat rests WITH the bones its toggle hides, and a donkey rests
        // WITHOUT the ones its toggle shows.
        assertEquals(true, restsDrawn("minecraft:goat", "horn"), "a goat rests with its horns");
        assertEquals(false, restsDrawn("minecraft:donkey", "chest"), "a donkey rests without its chest");
        assertEquals(true, restsDrawn("minecraft:armor_stand", "base_plate"), "a stand rests on its plate");
        assertEquals(false, restsDrawn("minecraft:armor_stand", "arms"), "and rests without its arms");
    }

    @Test
    @DisplayName("selecting one moves the bones it names, in whichever direction it rests")
    void aSelectedToggleMovesItsBones() {
        // A toggle that answered its own resting side would resolve to the mesh it started from and
        // read as working. So what is asserted is the CHANGE, per direction, rather than the state
        // after.
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
        assertTrue(hasBone("minecraft:bee", "stinger"), "the resting mesh carries the sting");
        assertEquals(List.of(), restingHidden("minecraft:bee"),
            "and rests drawing it, the model drawing it on a bee that has not stung");
    }

    // ------------------------------------------------------------------------------------

    /**
     * The bones one subject's resting mesh carries but does not draw, sorted - WHICH bones rest
     * hidden is this test's subject, and the mesh's own order is pinned where it is load-bearing,
     * by {@code PoseKitTest}.
     */
    private static @NotNull List<String> restingHidden(@NotNull String id) {
        List<String> hidden = new ArrayList<>();
        mesh(id).getBones().forEach((name, bone) -> {
            if (!bone.isVisible()) hidden.add(name);
        });
        return hidden.stream().sorted().toList();
    }

    /** Whether one subject's resting mesh carries a bone at all, drawn or not. */
    private static boolean hasBone(@NotNull String id, @NotNull String bone) {
        return mesh(id).getBones().containsKey(bone);
    }

    /** Whether the bones one named selection moves are drawn where the subject rests. */
    private static boolean restsDrawn(@NotNull String id, @NotNull String toggle) {
        for (EntityModelData.Bone bone : mesh(id).getBones().values())
            if (toggle.equals(bone.getToggle())) return bone.isVisible();
        throw new AssertionError(id + " is expected to name a '" + toggle + "' selection");
    }

    /** That selecting a toggle puts one of its bones on the other side of the mesh than it rests. */
    private static void assertToggleMoves(
        @NotNull String id, @NotNull String toggle, @NotNull String bone) {

        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        boolean atRest = entity.model().getBones().get(bone).isVisible();
        boolean selected = entity.resolve(AppearanceOptions.builder().toggles(Set.of(toggle)).build())
            .model().getBones().get(bone).isVisible();
        assertEquals(!atRest, selected,
            id + " '" + toggle + "' is expected to move " + bone + ", which rests " + (atRest ? "drawn" : "hidden"));
    }

    private static @NotNull EntityModelData mesh(@NotNull String id) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        return entity.model();
    }

    /** The member names one row's {@code bones} node carries, empty where it has none. */
    private static @NotNull List<String> boneMembers(@NotNull JsonObject row) {
        JsonObject bones = row.getAsJsonObject("bones");
        return bones == null ? List.of() : List.copyOf(bones.keySet());
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
