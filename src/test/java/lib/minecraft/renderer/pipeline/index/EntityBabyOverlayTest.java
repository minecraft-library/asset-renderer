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

    // ---------------------------------------------------------------------------------------
    // Fixture rows.
    //
    // Every raw record is constructed in exactly ONE place below, one argument per line with the
    // JSON member it fills named beside it, and every call site is argument-free. The raw records
    // are positional and overwhelmingly nullable, so a fixture that spells its nulls inline
    // absorbs a reshaped record silently whenever the arity still happens to line up - which is
    // how these rows rode through a `layers[]` reshape untouched and unnoticed. Labelling each
    // slot makes a reshape land visibly, in one place per record.
    // ---------------------------------------------------------------------------------------

    /** The {@code age} axis both fixture families share - an adult baseline plus a distinct baby mesh. */
    private static RawAxes ageAxes(String texture, String babyTexture) {
        Map<String, RawAgeOption> options = new LinkedHashMap<>();
        options.put("adult", new RawAgeOption(ADULT_COORD, texture, 0f));    // geometry, texture, y_shift
        options.put("baby", new RawAgeOption(BABY_COORD, babyTexture, 0f));  // geometry, texture, y_shift
        return new RawAxes(
            null,                     // variant
            new RawAgeAxis(options),  // age
            null,                     // shape
            null,                     // size
            null);                    // state
    }

    /** The {@code type} pass - a category overlay carrying a baby delta that clears the head subtree. */
    private static RawOverlay typePass() {
        return new RawOverlay(
            ADULT_COORD,                                           // geometry
            "minecraft:textures/entity/villager/type/plains.png",  // texture
            null,                                                  // retain_bones
            "head",                                                // no_hat_root
            null,                                                  // tint
            null,                                                  // tint_by
            "type",                                                // texture_by
            null,                                                  // grow
            null,                                                  // pipeline
            false,                                                 // skip_bounds
            null,                                                  // when
            typeBabyDelta());                                      // baby
    }

    /** The {@code type} pass's age delta - the baby texture plus the subtree its baby form clears. */
    private static RawOverlayBaby typeBabyDelta() {
        return new RawOverlayBaby(
            null,                                                  // geometry
            "minecraft:textures/entity/villager/baby/plains.png",  // texture
            "head",                                                // no_hat_root
            null);                                                 // grow
    }

    /** The {@code profession} pass - no age delta, so a baby drops it structurally. */
    private static RawOverlay professionPass() {
        return new RawOverlay(
            ADULT_COORD,   // geometry
            null,          // texture
            null,          // retain_bones
            null,          // no_hat_root
            null,          // tint
            null,          // tint_by
            "profession",  // texture_by
            null,          // grow
            null,          // pipeline
            true,          // skip_bounds
            null,          // when
            null);         // baby
    }

    /** The control family's wool pass - tinted by an axis rather than textured by one, and no age delta. */
    private static RawOverlay woolPass() {
        return new RawOverlay(
            ADULT_COORD,                                       // geometry
            "minecraft:textures/entity/sheep/sheep_wool.png",  // texture
            null,                                              // retain_bones
            null,                                              // no_hat_root
            null,                                              // tint
            "wool_color",                                      // tint_by
            null,                                              // texture_by
            null,                                              // grow
            null,                                              // pipeline
            false,                                             // skip_bounds
            null,                                              // when
            null);                                             // baby
    }

    /** The dyed-collar layer - one of the decorations a baby drops wholesale. */
    private static RawLayer collarLayer() {
        return new RawLayer(
            "collar",  // id
            null,      // when
            new RawLayerOverlay(
                "minecraft:textures/entity/villager/villager_collar.png",  // texture
                null,                                                      // geometry
                null,                                                      // grow
                null,                                                      // scaled
                null,                                                      // baby
                null,                                                      // layer_type
                null,                                                      // material_assets
                null));                                                    // default_material
    }

    /** The saddle equipment layer - the other decoration a baby drops wholesale. */
    private static RawLayer equipmentLayer() {
        return new RawLayer(
            null,                        // id
            new RawLayerWhen("saddle"),  // when
            new RawLayerOverlay(
                null,                                  // texture
                ADULT_COORD,                           // geometry
                null,                                  // grow
                null,                                  // scaled
                null,                                  // baby
                "pig_saddle",                          // layer_type
                Map.of("saddle", "minecraft:saddle"),  // material_assets
                "saddle"));                            // default_material
    }

    /** The block overlay a baby drops wholesale. */
    private static RawBlockOverlay mushroomOverlay() {
        return new RawBlockOverlay(
            "minecraft:red_mushroom_block",  // block
            null,                            // attached_bone
            List.of(),                       // transforms
            false);                          // selectable
    }

    /**
     * The villager-shaped family: a {@code type} pass carrying a baby delta, a {@code profession} pass
     * carrying none, plus the decorations a baby drops wholesale (a block overlay, a dyed collar, an
     * equipment layer).
     */
    private static RawModel villagerFamily() {
        return new RawModel(
            null,                                      // render
            null,                                      // bones
            List.of(typePass(), professionPass()),     // overlays
            List.of(mushroomOverlay()),                // block_overlays
            List.of(collarLayer(), equipmentLayer()),  // layers
            ageAxes("minecraft:textures/entity/villager/villager.png",
                "minecraft:textures/entity/villager/villager_baby.png"),  // axes
            null);                                     // group_of
    }

    /** The control family: a baby mesh and an overlay, but no age delta on it. */
    private static RawModel controlFamily() {
        return new RawModel(
            null,                 // render
            null,                 // bones
            List.of(woolPass()),  // overlays
            null,                 // block_overlays
            null,                 // layers
            ageAxes("minecraft:textures/entity/sheep/sheep.png",
                "minecraft:textures/entity/sheep/sheep_baby.png"),  // axes
            null);                // group_of
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
    @DisplayName("a baby delta's own grow inflates the baby mesh, overriding the row's adult grow")
    void babyDeltaGrowOverridesTheRowGrow() {
        // The trader llama dresses its baby in a caparison inflated by 0.2 where the adult's inflates by
        // 0.5, so the baby delta must carry that grow rather than inherit the row's. A null baby grow
        // still inherits (the villager robe), which the other cases here already cover.
        RawOverlay decor = new RawOverlay(
            ADULT_COORD,                                                        // geometry
            "minecraft:textures/entity/equipment/llama_body/trader_llama.png",  // texture
            null,                                                               // retain_bones
            null,                                                               // no_hat_root
            null,                                                               // tint
            null,                                                               // tint_by
            null,                                                               // texture_by
            new Vector3f(0.5f, 0.5f, 0.5f),                                     // grow (adult caparison)
            null,                                                               // pipeline
            true,                                                               // skip_bounds
            null,                                                               // when
            new RawOverlayBaby(
                null,                                                                    // geometry
                "minecraft:textures/entity/equipment/llama_body/trader_llama_baby.png",  // texture
                null,                                                                    // no_hat_root
                new Vector3f(0.2f, 0.2f, 0.2f)));                                        // grow (baby caparison)
        Map<String, RawModel> models = new LinkedHashMap<>();
        models.put(ENTITY, new RawModel(
            null,                                        // render
            null,                                        // bones
            List.of(decor),                              // overlays
            null,                                        // block_overlays
            null,                                        // layers
            ageAxes("minecraft:textures/entity/llama/llama_creamy.png",
                "minecraft:textures/entity/llama/llama_creamy_baby.png"),  // axes
            null));                                      // group_of
        Entity llama = EntityIndexBuilder.assemble(geometries(), new RawEntityModelsFile(models), diagnostics()).get(ENTITY);

        assertThat("the adult decor inflates by its row grow",
            firstCubeGrow(llama.overlays().getFirst().model()), is(0.5f));
        assertThat("the baby decor inflates by its own grow, not the row's 0.5",
            firstCubeGrow(llama.axes().babyOverlays().getFirst().model()), is(0.2f));
    }

    /** The uniform grow baked into the first cube of an overlay's materialised mesh. */
    private static float firstCubeGrow(EntityModelData model) {
        for (EntityModelData.Bone bone : model.getBones().values())
            if (!bone.getCubes().isEmpty())
                return bone.getCubes().getFirst().getGrow().x();
        throw new AssertionError("overlay mesh has no cube to read a grow from");
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
