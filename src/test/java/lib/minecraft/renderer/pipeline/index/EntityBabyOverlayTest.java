package lib.minecraft.renderer.pipeline.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity.OverlayLayer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.TextureSize;
import lib.minecraft.renderer.option.Age;
import lib.minecraft.renderer.option.EntityAppearance;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Baby overlay-form contract tests for {@link EntityIndexBuilder}, exercised on a hand-built raw model
 * so the age delta is pinned independently of any shipped entity's rows. The load-bearing case is the
 * base-coordinate the derived rows materialise against: a baby overlay must inherit the {@code age.baby}
 * mesh, because that is what keeps the depth-clearance inflate and the derived bounds skip.
 */
@DisplayName("EntityIndexBuilder baby overlay forms")
class EntityBabyOverlayTest {

    private static final String ENTITY = "minecraft:test_villager";
    private static final String CONTROL = "minecraft:test_sheep";
    private static final String ADULT_COORD = "TestVillagerModel#createBodyModel";
    private static final String BABY_COORD = "BabyTestVillagerModel#createBodyModel";

    private static Diagnostics diagnostics() {
        return Diagnostics.root("test", Diagnostics.Output.NONE, null);
    }

    /**
     * A villager-shaped mesh: {@code head} carries {@code hat} / {@code hat_rim} / {@code nose} beside an
     * untouched {@code body}, plus one bone unique to this mesh so the two meshes are distinguishable by
     * their bone sets alone.
     */
    private static EntityModelData mesh(String uniqueBone) {
        LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>();
        bones.put("body", bone(new Vector3f(0f, 1f, 0f), null));
        bones.put("head", bone(new Vector3f(0f, 2f, 0f), null));
        bones.put("hat", bone(new Vector3f(0f, 3f, 0f), "head"));
        bones.put("hat_rim", bone(new Vector3f(0f, 4f, 0f), "hat"));
        bones.put("nose", bone(new Vector3f(0f, 5f, 0f), "head"));
        bones.put(uniqueBone, bone(new Vector3f(0f, 6f, 0f), "body"));
        return new EntityModelData(TextureSize.DEFAULT, 0f, Concurrent.adoptLinkedMap(bones), false);
    }

    private static EntityModelData.Bone bone(Vector3f pivot, String parent) {
        return new EntityModelData.Bone(pivot, EulerRotation.NONE, EulerRotation.NONE, 1f,
            Concurrent.newList(new EntityModelData.Cube()), parent);
    }

    private static Map<String, EntityModelData> geometries() {
        Map<String, EntityModelData> geometries = new LinkedHashMap<>();
        geometries.put(ADULT_COORD, mesh("jacket"));
        geometries.put(BABY_COORD, mesh("bb_main"));
        return geometries;
    }

    /** The {@code age} axis both fixture families share - an adult baseline plus a distinct baby mesh. */
    private static RawAxes ageAxes(String texture, String babyTexture) {
        Map<String, RawAgeOption> options = new LinkedHashMap<>();
        options.put("adult", new RawAgeOption(ADULT_COORD, texture));
        options.put("baby", new RawAgeOption(BABY_COORD, babyTexture));
        return new RawAxes(null, new RawAgeAxis(options), null, null, null);
    }

    /**
     * The villager-shaped family: a {@code type} pass carrying a baby delta, a {@code profession} pass
     * carrying none, plus the decorations a baby drops wholesale (a block overlay, a dyed collar, an
     * equipment layer).
     */
    private static RawModel villagerFamily() {
        RawOverlay type = new RawOverlay(ADULT_COORD, "minecraft:textures/entity/villager/type/plains.png",
            null, "head", null, null, "type", null, null, false, null,
            new RawOverlayBaby("minecraft:textures/entity/villager/baby/plains.png", "head"));
        RawOverlay profession = new RawOverlay(ADULT_COORD, null,
            null, null, null, null, "profession", null, null, true, null, null);
        RawLayer collar = new RawLayer("collar", null,
            new RawLayerOverlay("minecraft:textures/entity/villager/villager_collar.png", null, null, null, null, null));
        RawLayer equipment = new RawLayer(null, new RawLayerWhen("saddle"),
            new RawLayerOverlay(null, ADULT_COORD, null, "pig_saddle", Map.of("saddle", "minecraft:saddle"), "saddle"));
        return new RawModel(null, null, List.of(type, profession),
            List.of(new RawBlockOverlay("minecraft:red_mushroom_block", null, List.of(), false)),
            List.of(collar, equipment),
            ageAxes("minecraft:textures/entity/villager/villager.png", "minecraft:textures/entity/villager/villager_baby.png"),
            null);
    }

    /** The control family: a baby mesh and an overlay, but no age delta on it. */
    private static RawModel controlFamily() {
        RawOverlay wool = new RawOverlay(ADULT_COORD, "minecraft:textures/entity/sheep/sheep_wool.png",
            null, null, null, "wool_color", null, null, null, false, null, null);
        return new RawModel(null, null, List.of(wool), null, null,
            ageAxes("minecraft:textures/entity/sheep/sheep.png", "minecraft:textures/entity/sheep/sheep_baby.png"),
            null);
    }

    private static ConcurrentMap<String, Entity> assemble() {
        Map<String, RawModel> models = new LinkedHashMap<>();
        models.put(ENTITY, villagerFamily());
        models.put(CONTROL, controlFamily());
        return EntityIndexBuilder.assemble(geometries(), new RawEntityModelsFile(models), diagnostics());
    }

    @Test
    @DisplayName("a baby form materialises on the baby mesh, keeping its depth clearance and bounds skip")
    void babyFormMaterialisesOnTheBabyMesh() {
        // The trap: deriving the baby row against the ADULT coordinate the row names would flip
        // sameGeometry false, dropping the depth-clearance inflate (the robe z-fights the baby body) and
        // un-setting the derived bounds skip (the pass re-enters the canvas union and moves the baby
        // canvas). Both are observable here as skipBounds plus the mesh the pass inherited.
        Entity villager = assemble().get(ENTITY);
        List<OverlayLayer> baby = villager.axes().babyOverlays();
        assertThat("only the pass carrying a baby form survives", baby.size(), is(1));

        OverlayLayer robe = baby.getFirst();
        assertThat("the baby pass keeps its bounds skip", robe.skipBounds(), is(true));
        assertThat("the baby pass substitutes the baby texture", robe.textureRef(), is(Optional.of("villager/baby/plains")));
        assertThat("the baby pass inherits the row's texture axis", robe.textureBy(), is(Optional.of("type")));
        assertThat("the baby pass materialises on the baby mesh",
            robe.model().getBones().keySet(), hasItems("bb_main"));
        assertThat("the baby pass never touches the adult mesh",
            robe.model().getBones().containsKey("jacket"), is(false));
        assertThat("the adult pass still materialises on the adult mesh",
            villager.overlays().getFirst().model().getBones().keySet(), hasItems("jacket"));
    }

    @Test
    @DisplayName("the baby form's cleared-bone root strips the head subtree off the baby mesh")
    void babyFormClearsTheHeadSubtreeOnTheBabyMesh() {
        OverlayLayer robe = assemble().get(ENTITY).axes().babyOverlays().getFirst();
        assertThat("the baby pass carries an alternate mesh", robe.noHatModel().isPresent(), is(true));

        EntityModelData stripped = robe.noHatModel().get();
        for (String name : new String[]{"head", "hat", "hat_rim", "nose"})
            assertThat("the baby alternate empties '" + name + "'", stripped.getBones().get(name).getCubes().isEmpty(), is(true));
        assertThat("the baby alternate keeps the body", stripped.getBones().get("body").getCubes().isEmpty(), is(false));
        assertThat("the baby alternate is cut from the baby mesh", stripped.getBones().keySet(), hasItems("bb_main"));
    }

    @Test
    @DisplayName("an overlay declaring no baby form is absent from the baby list")
    void anOverlayWithoutABabyFormDropsOut() {
        // This is what gates the villager profession + profession_level passes off a baby: they carry no
        // age delta, so they are structurally absent rather than filtered by an isBaby gate downstream.
        ConcurrentMap<String, Entity> defs = assemble();
        assertThat("the adult list still carries both passes", defs.get(ENTITY).overlays().size(), is(2));
        assertThat("the profession pass is on the adult list",
            defs.get(ENTITY).overlays().getLast().textureBy(), is(Optional.of("profession")));
        assertThat("the baby list carries the type pass alone",
            defs.get(ENTITY).axes().babyOverlays().stream().map(OverlayLayer::textureBy).toList(),
            contains(Optional.of("type")));
        assertThat("a family whose overlays declare no age delta has an empty baby list",
            defs.get(CONTROL).axes().babyOverlays(), is(empty()));
    }

    @Test
    @DisplayName("resolve on a baby substitutes the baby overlays and still drops block overlays / collar / equipment")
    void babyResolveSubstitutesTheBabyOverlays() {
        ConcurrentMap<String, Entity> defs = assemble();
        Entity resolved = defs.get(ENTITY).resolve(EntityAppearance.builder().age(Age.BABY).build());
        assertThat("the baby renders the baby mesh", resolved.model(), sameInstance(defs.get(ENTITY).axes().babyModel().orElseThrow()));
        assertThat("the baby draws the baby overlay list", resolved.overlays(), is(defs.get(ENTITY).axes().babyOverlays()));
        assertThat("the baby still drops block overlays", resolved.blockOverlays(), is(empty()));
        assertThat("the baby still drops the collar", resolved.layers().collar().isPresent(), is(false));
        assertThat("the baby still drops equipment", resolved.layers().equipment(), is(empty()));

        Entity adult = defs.get(ENTITY).resolve(EntityAppearance.builder().build());
        assertThat("the adult list is untouched by the substitution", adult.overlays().size(), is(2));
        assertThat("the adult still draws its block overlay", adult.blockOverlays().size(), is(1));
        assertThat("the adult still carries its collar", adult.layers().collar().isPresent(), is(true));
        assertThat("the adult still carries its equipment", adult.layers().equipment().size(), is(1));
        assertThat("the adult and baby overlay lists differ", adult.overlays(), not(is(resolved.overlays())));
    }

    @Test
    @DisplayName("resolve on a baby with no baby form draws no overlay at all")
    void babyResolveWithoutABabyFormDrawsNothing() {
        Entity control = assemble().get(CONTROL);
        assertThat("the adult draws its overlay", control.resolve(EntityAppearance.builder().build()).overlays().size(), is(1));
        assertThat("the baby drops it, exactly as before the baby list existed",
            control.resolve(EntityAppearance.builder().age(Age.BABY).build()).overlays(), is(empty()));
    }
}
