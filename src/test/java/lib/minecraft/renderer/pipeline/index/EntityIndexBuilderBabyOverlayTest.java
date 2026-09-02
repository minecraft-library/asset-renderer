package lib.minecraft.renderer.pipeline.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity.OverlayLayer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.appearance.TextureAxis;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.TextureSize;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * coordinate a derived row draws: a baby overlay naming no mesh of its own must draw the
 * {@code age.baby} mesh, because that is what keeps its derived bounds skip.
 */
@DisplayName("EntityIndexBuilder baby overlay forms")
class EntityIndexBuilderBabyOverlayTest {

    private static final String ENTITY = "minecraft:test_villager";
    private static final String CONTROL = "minecraft:test_sheep";
    private static final String ADULT_COORD = "TestVillagerModel#createBodyModel";
    private static final String BABY_COORD = "BabyTestVillagerModel#createBodyModel";
    /** The meshes a suppressed pass draws, which the tooling derives and the row names. */
    private static final String ADULT_CLEARED = ADULT_COORD + "@cleared=head";
    private static final String BABY_CLEARED = BABY_COORD + "@cleared=head";
    /** The meshes a deformed pass draws, likewise derived and named rather than built here. */
    private static final String ADULT_DECOR = ADULT_COORD + "@inflate=0.5";
    private static final String BABY_DECOR = BABY_COORD + "@inflate=0.2";
    /** The subtree a suppressed pass's mesh is cut back over. */
    private static final Set<String> HEAD_SUBTREE = Set.of("head", "hat", "hat_rim", "nose");

    /**
     * A villager-shaped mesh: {@code head} carries {@code hat} / {@code hat_rim} / {@code nose} beside an
     * untouched {@code body}, plus one bone unique to this mesh so the two meshes are distinguishable by
     * their bone sets alone.
     */
    private static EntityModelData mesh(String uniqueBone) {
        return mesh(uniqueBone, 0f);
    }

    /** The same mesh with one deformation on every cube, standing for a mesh the tooling inflated. */
    private static EntityModelData mesh(String uniqueBone, float grow) {
        LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>();
        bones.put("body", bone(new Vector3f(0f, 1f, 0f), null, grow));
        bones.put("head", bone(new Vector3f(0f, 2f, 0f), null, grow));
        bones.put("hat", bone(new Vector3f(0f, 3f, 0f), "head", grow));
        bones.put("hat_rim", bone(new Vector3f(0f, 4f, 0f), "hat", grow));
        bones.put("nose", bone(new Vector3f(0f, 5f, 0f), "head", grow));
        bones.put(uniqueBone, bone(new Vector3f(0f, 6f, 0f), "body", grow));
        return new EntityModelData(TextureSize.DEFAULT, Concurrent.adoptLinkedMap(bones), false);
    }

    private static EntityModelData.Bone bone(Vector3f pivot, String parent, float grow) {
        EntityModelData.Cube cube = new EntityModelData.Cube(
            Vector3f.ZERO, Vector3f.ZERO, new Vector2f(0f, 0f), new Vector3f(grow, grow, grow), false,
            Vector3f.ZERO, EulerRotation.NONE, Concurrent.newMap());
        return new EntityModelData.Bone(pivot, EulerRotation.NONE, EulerRotation.NONE, 1f,
            Concurrent.newList(cube), parent);
    }

    /** One mesh with the {@code head} subtree emptied - what the tooling derives a suppressed pass onto. */
    private static EntityModelData cleared(EntityModelData source) {
        LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>();
        source.getBones().forEach((name, bone) -> bones.put(name,
            HEAD_SUBTREE.contains(name) ? bone.withCubes(Concurrent.adoptList(new ArrayList<>())) : bone));
        return new EntityModelData(source.getTextureSize(), Concurrent.adoptLinkedMap(bones), source.isCull());
    }

    private static Map<String, EntityModelData> geometries() {
        Map<String, EntityModelData> geometries = new LinkedHashMap<>();
        geometries.put(ADULT_COORD, mesh("jacket"));
        geometries.put(BABY_COORD, mesh("bb_main"));
        geometries.put(ADULT_CLEARED, cleared(mesh("jacket")));
        geometries.put(BABY_CLEARED, cleared(mesh("bb_main")));
        geometries.put(ADULT_DECOR, mesh("jacket", 0.5f));
        geometries.put(BABY_DECOR, mesh("bb_main", 0.2f));
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
        Map<String, RawOption> options = new LinkedHashMap<>();
        options.put("adult", new RawOption(ADULT_COORD, texture, null, null, null, null, null, null));    // geometry, texture
        options.put("baby", new RawOption(BABY_COORD, babyTexture, null, null, null, null, null, null));  // geometry, texture
        return new RawAxes(
            null,                     // variant
            new RawAxis(null, options),  // age
            null,                     // shape
            null,                     // size
            null);                    // state
    }

    /** The {@code type} pass - a category overlay carrying a baby delta with a suppressed form. */
    private static RawOverlay typePass() {
        return new RawOverlay(
            ADULT_COORD,                                           // geometry
            ADULT_CLEARED,                                         // no_hat_geometry
            "villager/type/plains",  // texture
            null,                                                  // tint
            null,                                                  // tint_by
            "type",                                                // texture_by
            null,                                                  // pipeline
            null,                                                  // texture_scroll
            false,                                                 // skip_bounds
            null,                                                  // when
            typeBabyDelta());                                      // baby
    }

    /** The {@code type} pass's age delta - the baby texture plus the mesh its suppressed form draws. */
    private static RawOverlayBaby typeBabyDelta() {
        return new RawOverlayBaby(
            null,                                                  // geometry
            BABY_CLEARED,                                          // no_hat_geometry
            "villager/baby/plains");  // texture
    }

    /** The {@code profession} pass - no age delta, so a baby drops it structurally. */
    private static RawOverlay professionPass() {
        return new RawOverlay(
            ADULT_COORD,   // geometry
            null,          // no_hat_geometry
            null,          // texture
            null,          // tint
            null,          // tint_by
            "profession",  // texture_by
            null,          // pipeline
            null,          // texture_scroll
            true,          // skip_bounds
            null,          // when
            null);         // baby
    }

    /** The control family's wool pass - tinted by an axis rather than textured by one, and no age delta. */
    private static RawOverlay woolPass() {
        return new RawOverlay(
            ADULT_COORD,                                       // geometry
            null,                                              // no_hat_geometry
            "sheep/sheep_wool",  // texture
            null,                                              // tint
            "wool_color",                                      // tint_by
            null,                                              // texture_by
            null,                                              // pipeline
            null,                                              // texture_scroll
            false,                                             // skip_bounds
            null,                                              // when
            null);                                             // baby
    }

    /** The saddle equipment row - the other decoration a baby drops wholesale. */
    private static RawEquipmentRow equipmentRow() {
        return new RawEquipmentRow(
            "saddle",                              // slot
            ADULT_COORD,                           // geometry
            "pig_saddle",                          // layer_type
            Map.of("saddle", "minecraft:saddle"),  // material_assets
            "saddle");                             // default_material
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
     * carrying none, plus the decorations a baby drops wholesale (a block overlay, an equipment
     * layer).
     */
    private static RawModel villagerFamily() {
        return new RawModel(
            null,                                      // renderer
            null,                                      // render
            List.of(typePass(), professionPass()),     // overlays
            List.of(mushroomOverlay()),                // block_overlays
            null,                                      // armor
            List.of(equipmentRow()),                   // equipment
            ageAxes("villager/villager",
                "villager/villager_baby"),  // axes
            null,                                      // members
            null);                                     // styles
    }

    /** The control family: a baby mesh and an overlay, but no age delta on it. */
    private static RawModel controlFamily() {
        return new RawModel(
            null,                 // renderer
            null,                 // render
            List.of(woolPass()),  // overlays
            null,                 // block_overlays
            null,                 // armor
            null,                 // equipment
            ageAxes("sheep/sheep",
                "sheep/sheep_baby"),  // axes
            null,                 // members
            null);                // styles
    }

    private static ConcurrentMap<String, Entity> assemble() {
        Map<String, RawModel> models = new LinkedHashMap<>();
        models.put(ENTITY, villagerFamily());
        models.put(CONTROL, controlFamily());
        return EntityIndexBuilder.assemble(geometries(), new RawEntityModelsFile(models, null), Map.of());
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
        assertThat("the baby pass inherits the row's texture axis", robe.textureBy(), is(Optional.of(TextureAxis.TYPE)));
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
    @DisplayName("a baby delta naming its own mesh draws that one, not the mesh the row draws")
    void babyDeltaDrawsTheMeshItNames() {
        // The trader llama dresses its baby in a caparison deformed by 0.2 where the adult's is deformed
        // by 0.5, and the two are meshes rather than amounts. So the delta names the baby one and the
        // row's own mesh must not reach it. A delta naming none draws the age.baby mesh (the villager
        // robe), which the other cases here already cover.
        RawOverlay decor = new RawOverlay(
            ADULT_DECOR,                                                        // geometry
            null,                                                               // no_hat_geometry
            "equipment/llama_body/trader_llama",  // texture
            null,                                                               // tint
            null,                                                               // tint_by
            null,                                                               // texture_by
            null,                                                               // pipeline
            null,                                                               // texture_scroll
            true,                                                               // skip_bounds
            null,                                                               // when
            new RawOverlayBaby(
                BABY_DECOR,                                                              // geometry
                null,                                                                    // no_hat_geometry
                "equipment/llama_body/trader_llama_baby"));  // texture
        Map<String, RawModel> models = new LinkedHashMap<>();
        models.put(ENTITY, new RawModel(
            null,                                        // renderer
            null,                                        // render
            List.of(decor),                              // overlays
            null,                                        // block_overlays
            null,                                        // armor
            null,                                        // equipment
            ageAxes("llama/llama_creamy",
                "llama/llama_creamy_baby"),  // axes
            null,                                        // members
            null));                                      // styles
        Entity llama = EntityIndexBuilder.assemble(geometries(), new RawEntityModelsFile(models, null), Map.of()).get(ENTITY);

        assertThat("the adult decor draws the mesh its row names",
            firstCubeGrow(llama.overlays().getFirst().model()), is(0.5f));
        assertThat("the baby decor draws the mesh its delta names, not the row's",
            firstCubeGrow(llama.axes().babyOverlays().getFirst().model()), is(0.2f));
        assertThat("the baby decor is cut from the baby mesh",
            llama.axes().babyOverlays().getFirst().model().getBones().keySet(), hasItems("bb_main"));
    }

    /** The uniform grow baked into the first cube of the mesh an overlay draws. */
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
            defs.get(ENTITY).overlays().getLast().textureBy(), is(Optional.of(TextureAxis.PROFESSION)));
        assertThat("the baby list carries the type pass alone",
            defs.get(ENTITY).axes().babyOverlays().stream().map(OverlayLayer::textureBy).toList(),
            contains(Optional.of(TextureAxis.TYPE)));
        assertThat("a family whose overlays declare no age delta has an empty baby list",
            defs.get(CONTROL).axes().babyOverlays(), is(empty()));
    }

    @Test
    @DisplayName("resolve on a baby substitutes the baby overlays and still drops block overlays / equipment")
    void babyResolveSubstitutesTheBabyOverlays() {
        ConcurrentMap<String, Entity> defs = assemble();
        Entity resolved = defs.get(ENTITY).resolve(AppearanceOptions.builder().age(Age.BABY).build());
        assertThat("the baby renders the baby mesh", resolved.model(), sameInstance(defs.get(ENTITY).axes().babyModel().orElseThrow()));
        assertThat("the baby draws the baby overlay list", resolved.overlays(), is(defs.get(ENTITY).axes().babyOverlays()));
        assertThat("the baby still drops block overlays", resolved.blockOverlays(), is(empty()));
        assertThat("the baby still drops equipment", resolved.layers().equipment(), is(empty()));

        Entity adult = defs.get(ENTITY).resolve(AppearanceOptions.builder().build());
        assertThat("the adult list is untouched by the substitution", adult.overlays().size(), is(2));
        assertThat("the adult still draws its block overlay", adult.blockOverlays().size(), is(1));
        assertThat("the adult still carries its equipment", adult.layers().equipment().size(), is(1));
        assertThat("the adult and baby overlay lists differ", adult.overlays(), not(is(resolved.overlays())));
    }

    @Test
    @DisplayName("resolve on a baby with no baby form draws no overlay at all")
    void babyResolveWithoutABabyFormDrawsNothing() {
        Entity control = assemble().get(CONTROL);
        assertThat("the adult draws its overlay", control.resolve(AppearanceOptions.builder().build()).overlays().size(), is(1));
        assertThat("the baby drops it, exactly as before the baby list existed",
            control.resolve(AppearanceOptions.builder().age(Age.BABY).build()).overlays(), is(empty()));
    }
}
