package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity.OverlayLayer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.appearance.AppearanceGate;
import lib.minecraft.renderer.asset.appearance.CopperWeathering;
import lib.minecraft.renderer.asset.appearance.Flag;
import lib.minecraft.renderer.asset.appearance.TextureAxis;
import lib.minecraft.renderer.asset.appearance.TintAxis;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Load-contract tests for {@link EntityModelLoader}, exercising the family read of
 * {@code entity_models.json} directly. Load-bearing canaries: the wolf
 * variant/state texture join, the baby three-source texture chain, the dyed-collar presence, the
 * option-encoded variant coat map + resolver fold, the depth-clearance auto-skip on a
 * base-mesh-inheriting overlay, the head-stripped alternate mesh on a category pass, the villager
 * baby robe pass, the equipment material-to-asset table, and the per-entity saddle layers of the two
 * renderers shared by two entities each.
 */
@DisplayName("EntityModelLoader native load")
class EntityModelLoaderTest {

    @Test
    @DisplayName("every subject is in a state, and it is the one its texture ref reads")
    void everySubjectCarriesAStateAxis() {
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        assertThat("the corpus loaded", defs.size(), greaterThan(0));
        for (Entity definition : defs.values()) {
            String where = definition.id().id();
            Entity.Axis<String, String> state = definition.axes().state();
            assertThat(where + " names the state it is in", state.declared().isPresent(), is(true));
            // The axis' own invariant, asserted on the corpus rather than trusted: a declared option
            // the options do not carry would make textureRef() answer empty for a subject that has
            // a texture, which renders as a subject with no skin rather than as an error.
            assertThat(where + " declares one of its own options",
                state.options().keySet(), hasItem(state.declared().get()));
            assertThat(where + " reads its texture ref out of the state it is in",
                definition.textureRef(), is(state.select(state.declared().get())));
            assertThat(where + " names a base texture", definition.textureRef().isPresent(), is(true));
        }
    }

    @Test
    @DisplayName("the tropical fish carries both its bodies as forms of one axis")
    void shapeFormsAreWholeDefinitions() {
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        Entity fish = defs.get("minecraft:tropical_fish");
        Entity.Axis<String, Entity> shape = fish.axes().shape();
        assertThat("the fish names the shape it is", shape.declared(), is(Optional.of("small")));
        assertThat("the declared shape is one of its own", shape.options().keySet(),
            hasItems("small", Entity.SHAPE_LARGE));
        // The declared form is the row AS A LEAF - same mesh and texture, its own shape axis empty -
        // rather than the row itself, on the same terms the variant axis already holds its default.
        Entity small = shape.select("small").orElseThrow();
        assertThat("the declared form draws the row's mesh", small.model(), sameInstance(fish.model()));
        assertThat("the declared form draws the row's texture", small.textureRef(), is(fish.textureRef()));
        assertThat("the declared form is a leaf", small.axes().shape().options(), is(anEmptyMap()));

        Entity large = shape.select(Entity.SHAPE_LARGE).orElseThrow();
        assertThat("the large body is its own mesh", large.model(), not(sameInstance(fish.model())));
        assertThat("the large body draws its own base texture",
            large.textureRef(), is(Optional.of("fish/tropical_b")));
        assertThat("the large body carries the passes cloned onto it", large.overlays(), not(empty()));
        // A form is a leaf: carrying a shape axis of its own would let a resolve re-fold forever, and
        // would make "which shape am I" answerable two ways.
        assertThat("a shape form carries no shape axis", large.axes().shape().options(), is(anEmptyMap()));

        // Every other subject has no shape axis at all, so nothing else can be swapped by a pattern.
        for (Entity definition : defs.values())
            if (!definition.id().id().equals("minecraft:tropical_fish"))
                assertThat(definition.id() + " has no shape axis",
                    definition.axes().shape().options(), is(anEmptyMap()));
    }

    @Test
    @DisplayName("the copper golem oxidises through its state axis, and only it does")
    void weatheringIsAState() {
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        Entity golem = defs.get("minecraft:copper_golem");
        for (CopperWeathering weathering : CopperWeathering.values())
            assertThat("the " + weathering + " body is the state that selects it",
                weathering.stateKey().flatMap(golem.axes().state()::select)
                    .orElseGet(() -> golem.textureRef().orElseThrow()),
                is(weathering.baseTexture()));
        // The unaffected body is the state the subject is already in rather than a fourth entry, so
        // asking for it by key answers nothing - which is what makes the default render fall through
        // to the base state instead of selecting an alternate equal to it.
        assertThat("unaffected names no alternate", CopperWeathering.UNAFFECTED.stateKey(), is(Optional.empty()));
        // A subject that does not weather carries no oxidation state, so a weathering selection lands
        // on nothing rather than repainting a cow in copper.
        for (Entity definition : defs.values()) {
            if (definition.id().id().equals("minecraft:copper_golem")) continue;
            assertThat(definition.id() + " carries no oxidation state",
                definition.axes().state().options().keySet(),
                not(hasItem(CopperWeathering.OXIDIZED.stateKey().orElseThrow())));
        }
    }

    @Test
    @DisplayName("wolf base texture ref equals the default variant option's wild texture")
    void wolfWildEqualsTextureRef() {
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        Entity pale = coat(defs, "minecraft:wolf", "pale");
        assertThat(pale.axes().state().options().get("wild"), is("wolf/wolf"));
        assertThat("wild state equals the default texture_ref",
            Optional.of(pale.axes().state().options().get("wild")), equalTo(pale.textureRef()));
        assertThat(pale.axes().state().options().keySet(), hasItems("wild", "tame", "angry"));
        assertThat(coat(defs, "minecraft:wolf", "ashen").axes().state().options().get("tame"), is("wolf/wolf_ashen_tame"));
    }

    @Test
    @DisplayName("baby texture resolves via the three-source chain (variant baby_texture / isBaby binding / <adult>_baby)")
    void babyThreeSourceChain() {
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        // 1) variant-table per-option baby_texture (per coat sub-definition)
        assertThat(coat(defs, "minecraft:cow", "temperate").axes().state().options().get("baby"), is("cow/cow_temperate_baby"));
        assertThat(coat(defs, "minecraft:cow", "warm").axes().state().options().get("baby"), is("cow/cow_warm_baby"));
        assertThat("cow carries a distinct baby mesh", coat(defs, "minecraft:cow", "temperate").axes().babyModel().isPresent(), is(true));
        // 2) non-variant entity sources its baby texture from the age.baby.texture (isBaby) binding
        assertThat(defs.get("minecraft:sheep").axes().state().options().get("baby"), is("sheep/sheep_baby"));
        // 3) enum-variant fallback to the <adult>_baby naming convention; the base row is the default coat
        assertThat(defs.get("minecraft:axolotl").axes().state().options().get("baby"), is("axolotl/axolotl_lucy_baby"));
    }

    @Test
    @DisplayName("the dyed-collar overlay row is carried for wolf + cat, absent elsewhere")
    void collarPresence() {
        // The collar renders only while a collar colour resolves - the row's collared gate - and the
        // load contract pins the row that gate rides: the wearer's own mesh under the collar texture,
        // tinted from collar_color. A bare wolf / cat resolves no collar tint, so the gate drops the
        // row at resolve and no collar band draws.
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        OverlayLayer wolf = collarRow(coat(defs, "minecraft:wolf", "pale"));
        assertThat(wolf.textureRef(), equalTo(Optional.of("wolf/wolf_collar")));
        assertThat("the band tints from the collar axis", wolf.tintBy(), equalTo(Optional.of(TintAxis.COLLAR)));
        assertThat("every wolf variant shares the family collar row",
            collarRow(coat(defs, "minecraft:wolf", "ashen")).textureRef(), equalTo(Optional.of("wolf/wolf_collar")));
        assertThat(collarRow(coat(defs, "minecraft:cat", "black")).textureRef(), equalTo(Optional.of("cat/cat_collar")));
        assertThat("a non-collar entity has none",
            defs.get("minecraft:cow").overlays().stream().anyMatch(EntityModelLoaderTest::isCollarRow), is(false));
    }

    /** The overlay pass gated on {@link Flag#COLLARED} - the dyed-collar row. */
    private static OverlayLayer collarRow(Entity entity) {
        return entity.overlays().stream().filter(EntityModelLoaderTest::isCollarRow).findFirst()
            .orElseThrow(() -> new AssertionError("entity '" + entity.id() + "' has no collar row"));
    }

    /** Whether an overlay pass is the dyed-collar row. */
    private static boolean isCollarRow(OverlayLayer overlay) {
        return overlay.gate().filter(gate -> gate instanceof AppearanceGate.Selected selected
            && selected.option() == Flag.COLLARED).isPresent();
    }

    @Test
    @DisplayName("option-encoded variant family loads one base row + a coat map the resolver fold selects")
    void variantOptionEncoding() {
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        Entity cow = defs.get("minecraft:cow");
        assertThat("one base row, no coat pseudo-ids", cow != null && defs.get("minecraft:cow_cold") == null, is(true));
        assertThat("the coat map carries every option", cow.axes().variant().options().keySet(), hasItems("cold", "temperate", "warm"));

        // The base IS the default (temperate) coat; the resolver fold swaps it to the selected coat.
        // cow_cold uses the horned coldcow mesh + cold texture, so selecting it changes both.
        assertThat("the base row is the default coat", cow.textureRef(), is(cow.axes().variant().options().get("temperate").textureRef()));
        Entity resolvedCold = cow.resolve(AppearanceOptions.builder().variant(Optional.of("cold")).build());
        assertThat("selecting cold swaps to the cold coat texture", resolvedCold.textureRef(), is(cow.axes().variant().options().get("cold").textureRef()));
        assertThat("the cold coat differs from the default", resolvedCold.textureRef(), not(cow.textureRef()));
        assertThat("selecting cold swaps to the cold coat mesh", resolvedCold.model(), sameInstance(cow.axes().variant().options().get("cold").model()));

        // Canvas-group membership is baked onto Entity.members: an option-encoded variant model with no
        // cross-entity group is a singleton (empty members); a genuine group_of group carries the
        // self-inclusive member list identically on every member.
        assertThat("an option-encoded variant model with no cross-group carries no members", cow.members(), is(empty()));
        assertThat("a plain singleton entity carries no members", defs.get("minecraft:sheep").members(), is(empty()));
        assertThat("a group_of group is self-inclusive on every member",
            defs.get("minecraft:camel").members(), containsInAnyOrder("minecraft:camel", "minecraft:camel_husk"));
        assertThat("the group member list is identical on every member",
            defs.get("minecraft:camel_husk").members(), equalTo(defs.get("minecraft:camel").members()));
    }

    @Test
    @DisplayName("the worn-armor shell is joined off the layers armor row")
    void humanoidArmorFromLayersRow() {
        // The armor row lives under `layers`: the reader joins its geometry reference against the
        // geometry table (absence IS none). Which entities land on the roster is asserted exhaustively
        // by EntityModelLoaderArmorRosterTest; what the joined shell carries is asserted here.
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        assertThat("the derived accessor reads the layers row",
            defs.get("minecraft:zombie").layers().humanoidArmor().isPresent(), is(true));

        // The shell is the mesh vanilla hands the wearer, not the wearer's own: the boxes must be the
        // armor unwrap's 64x32 tree, and the two layer deformations must travel with it.
        Shell armor = defs.get("minecraft:zombie").humanoidArmor().orElseThrow();
        assertThat("the shell carries the armor atlas width", armor.mesh().getTextureWidth(), is(64));
        assertThat("the shell carries the armor atlas height", armor.mesh().getTextureHeight(), is(32));
        assertThat("the leggings deformation rides the row", armor.innerGrow(), equalTo(new Vector3f(0.5f, 0.5f, 0.5f)));
        assertThat("the outer deformation rides the row", armor.outerGrow(), equalTo(new Vector3f(1f, 1f, 1f)));
        // The shell is ungrown: a leg's own CubeDeformation.extend(-0.1) is all its cube carries, so the
        // slot's deformation is summed onto it at render rather than baked in twice.
        assertThat("the shell's legs carry only their own extend",
            armor.mesh().getBones().get("right_leg").getCubes().getFirst().getGrow(),
            equalTo(new Vector3f(-0.1f, -0.1f, -0.1f)));
        // The piglin's set differs from the generic one in nothing but its outer deformation, so the two
        // share one geometry entry - the dedupe the collapsed shape buys.
        assertThat("the piglin shares the generic shell mesh",
            defs.get("minecraft:piglin").humanoidArmor().orElseThrow().mesh(), sameInstance(armor.mesh()));
        assertThat("the piglin's own outer deformation still rides its row",
            defs.get("minecraft:piglin").humanoidArmor().orElseThrow().outerGrow(),
            equalTo(new Vector3f(1.02f, 1.02f, 1.02f)));
    }

    @Test
    @DisplayName("a base-mesh-inheriting grow-less overlay is auto-skipped from canvas bounds")
    void depthClearanceOnGeometryInheritance() {
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        // enderman eyes re-submit the base mesh (same geometry coordinate) with no tint - our auto-emitted
        // depth-clearance inflate wins the coplanar tie, and the overlay is excluded from canvas bounds
        // because the base already covers its silhouette. Keyed on the overlay inheriting the base mesh,
        // not on ref-equality of the model object.
        OverlayLayer eyes = defs.get("minecraft:enderman").overlays().getFirst();
        assertThat("emissive eyes overlay", eyes.pass().emissive(), is(true));
        assertThat("base-mesh-inheriting grow-less overlay skips bounds", eyes.skipBounds(), is(true));

        // The sheep wool layer uses a DISTINCT geometry (SheepFurModel) and carries no depth-clearance, so
        // it contributes to bounds; the same-mesh undercoat (SheepModel) is skipped.
        List<OverlayLayer> sheep = defs.get("minecraft:sheep").overlays();
        assertThat("sheep declares undercoat + wool overlays", sheep.size(), greaterThan(1));
        assertThat("same-mesh wool undercoat skips bounds", sheep.getFirst().skipBounds(), is(true));
        assertThat("distinct-mesh wool layer contributes to bounds", sheep.get(1).skipBounds(), is(false));
    }

    @Test
    @DisplayName("the villager robe pass stays same-geometry, so its depth clearance and bounds skip hold")
    void villagerRobePassKeepsDepthClearance() {
        // The robe pass carries an alternate head-stripped mesh, derived from the materialised mesh rather
        // than from a second geometry coordinate. Expressing it as its own coordinate would flip
        // sameGeometry false, dropping the depth-clearance inflate (z-fighting the base body) and
        // un-setting the derived bounds skip - which moves the villager AND wandering_trader canvas.
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        assertThat("villager type pass skips bounds", defs.get("minecraft:villager").overlays().getFirst().skipBounds(), is(true));
        assertThat("zombie villager type pass skips bounds", defs.get("minecraft:zombie_villager").overlays().getFirst().skipBounds(), is(true));
    }

    @Test
    @DisplayName("a baby overlay list is built per declared baby form, never copied off the adult one")
    void babyOverlayListOnlyWhereDeclared() {
        // The baby list is assembled from the rows that declare a `baby` node, not cloned from the adult
        // list. The sheep is the case that can tell those apart: it carries TWO adult passes and vanilla
        // gives only one of them a baby form, because SheepWoolUndercoatLayer.submit returns outright on a
        // baby while SheepWoolLayer swaps to its baby mesh. A list that fell back would dress a lamb in the
        // undercoat vanilla never draws on it.
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        Entity sheep = defs.get("minecraft:sheep");
        assertThat("the sheep has a baby mesh", sheep.axes().babyModel().isPresent(), is(true));
        assertThat("the sheep carries an undercoat pass and a wool pass", sheep.overlays().size(), is(2));
        assertThat("only the wool pass has a baby form", sheep.axes().babyOverlays().size(), is(1));
        assertThat("the baby wool binds the baby wool texture",
            sheep.axes().babyOverlays().getFirst().textureRef(), is(Optional.of("sheep/sheep_wool_baby")));
        assertThat("the baby wool keeps the row's dye axis",
            sheep.axes().babyOverlays().getFirst().tintBy(), is(Optional.of(TintAxis.WOOL)));

        assertThat("a family with no baby mesh at all carries no baby list",
            defs.get("minecraft:wandering_trader").axes().babyOverlays(), is(empty()));
    }

    @Test
    @DisplayName("the drowned's baby outer layer binds its own mesh, not an inflate of the baby body")
    void drownedBabyOuterLayerBindsItsOwnMesh() {
        // End-to-end canary over the shipped resource. Vanilla bakes DROWNED_BABY_OUTER_LAYER as its own
        // LayerDefinition, and BabyZombieModel#createBodyLayer hardcodes its two head cubes' deformations
        // instead of driving them off the parameter - so the shell is NOT the baby body grown by the row's
        // 0.25 and the delta has to name a mesh. Inheriting the row's grow instead would land the adult
        // inflate a second time on top of the baby factory's own.
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        Entity drowned = defs.get("minecraft:drowned");
        assertThat("the adult outer layer is the adult shell",
            drowned.overlays().getFirst().textureRef(), is(Optional.of("zombie/drowned_outer_layer")));

        List<OverlayLayer> babyPasses = drowned.axes().babyOverlays();
        assertThat("the drowned ships exactly one baby outer pass", babyPasses.size(), is(1));
        OverlayLayer shell = babyPasses.getFirst();
        assertThat("the baby pass binds the baby outer texture",
            shell.textureRef(), is(Optional.of("zombie/drowned_outer_layer_baby")));
        assertThat("the baby shell is a mesh of its own, not the baby body",
            shell.model(), is(not(sameInstance(drowned.axes().babyModel().orElseThrow()))));
        assertThat("the baby shell stands proud, so it contributes to canvas bounds",
            shell.skipBounds(), is(false));
    }

    @Test
    @DisplayName("the trader llama ships a baby caparison, bound to the baby mesh and the baby decor texture")
    void traderLlamaShipsBabyCaparison() {
        // End-to-end canary over the shipped resource: vanilla dresses a baby trader llama in a distinct
        // baby caparison, and the parity harness renders no babies, so a lost `baby` decor node would
        // silently strip it with the suite still green. The adult decor stays the adult caparison.
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        Entity llama = defs.get("minecraft:trader_llama");
        assertThat("the trader llama has a baby mesh", llama.axes().babyModel().isPresent(), is(true));
        assertThat("the adult decor is the adult caparison",
            llama.overlays().getFirst().textureRef(), is(Optional.of("equipment/llama_body/trader_llama")));

        List<OverlayLayer> babyPasses = llama.axes().babyOverlays();
        assertThat("the trader llama ships exactly one baby decor pass", babyPasses.size(), is(1));
        OverlayLayer caparison = babyPasses.getFirst();
        assertThat("the baby pass binds the baby caparison texture",
            caparison.textureRef(), is(Optional.of("equipment/llama_body/trader_llama_baby")));
        assertThat("the baby pass keeps the decor bounds skip", caparison.skipBounds(), is(true));
        assertThat("the baby caparison materialises on the baby mesh, not the adult one",
            caparison.model().getBones().keySet(), is(llama.axes().babyModel().get().getBones().keySet()));
    }

    @Test
    @DisplayName("both villagers ship a baby type pass, bound to the baby mesh and the baby robe texture")
    void babyTypePassIsShippedForBothVillagers() {
        // End-to-end canary over the shipped resource: the tooling must emit the `baby` node on the type
        // pass and the loader must bind it into the baby overlay list. Nothing else covers it - the parity
        // harness renders no babies, so a lost node or an unbound delta would silently strip the robe off
        // every baby villager with the suite still green.
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        assertShippedBabyTypePass(defs, "minecraft:villager", "villager", "bb_main");
        assertShippedBabyTypePass(defs, "minecraft:zombie_villager", "zombie_villager", "nose");
    }

    /**
     * Asserts a villager family ships exactly one baby overlay pass - the type pass - bound to the baby
     * robe texture, materialised on the {@code age.baby} mesh rather than the adult one, keeping the
     * bounds skip its same-geometry depth clearance derives, and carrying the head-stripped alternate mesh
     * cut from that same baby mesh.
     *
     * @param defs the loaded index
     * @param entityId the villager family
     * @param texturePrefix the entity texture prefix the robe ref is qualified with
     * @param babyOnlyBone a bone the baby mesh carries and the adult mesh does not
     */
    private static void assertShippedBabyTypePass(
        ConcurrentMap<String, Entity> defs,
        String entityId,
        String texturePrefix,
        String babyOnlyBone
    ) {
        Entity entity = defs.get(entityId);
        List<OverlayLayer> babyPasses = entity.axes().babyOverlays();
        assertThat(entityId + " ships exactly one baby overlay pass", babyPasses.size(), is(1));
        assertThat(entityId + " the baby pass is the type pass",
            babyPasses.stream().map(OverlayLayer::textureBy).toList(), contains(Optional.of(TextureAxis.TYPE)));

        OverlayLayer robe = babyPasses.getFirst();
        assertThat(entityId + " the baby pass binds the baby robe texture",
            robe.textureRef(), is(Optional.of(texturePrefix + "/baby/plains")));
        // The sameGeometry trap: materialising the derived row against the ADULT coordinate the row names
        // would drop the depth-clearance inflate and un-set the derived bounds skip, z-fighting the baby
        // body and moving the baby canvas.
        assertThat(entityId + " the baby pass keeps its bounds skip", robe.skipBounds(), is(true));

        EntityModelData babyMesh = entity.axes().babyModel()
            .orElseThrow(() -> new AssertionError("entity '" + entityId + "' ships no baby mesh"));
        assertThat(entityId + " the baby pass materialises on the baby mesh",
            Set.copyOf(robe.model().getBones().keySet()), equalTo(Set.copyOf(babyMesh.getBones().keySet())));
        assertThat(entityId + " '" + babyOnlyBone + "' is a baby-mesh bone the pass carries",
            robe.model().getBones().containsKey(babyOnlyBone), is(true));
        assertThat(entityId + " the adult type pass carries no baby-mesh bone",
            categoryPass(defs, entityId, "type").model().getBones().containsKey(babyOnlyBone), is(false));

        assertThat(entityId + " the baby pass carries the head-stripped alternate mesh", robe.noHatModel().isPresent(), is(true));
        EntityModelData stripped = robe.noHatModel().get();
        for (String bone : List.of("head", "hat", "hat_rim", "nose")) {
            assertThat(entityId + " '" + bone + "' owns cubes on the baby pass mesh",
                robe.model().getBones().get(bone).getCubes().isEmpty(), is(false));
            assertThat(entityId + " '" + bone + "' is emptied on the baby alternate mesh",
                stripped.getBones().get(bone).getCubes().isEmpty(), is(true));
        }
        assertThat(entityId + " the baby alternate keeps the body off the head subtree",
            stripped.getBones().get("body").getCubes().isEmpty(), is(false));
        assertThat(entityId + " the baby alternate is cut from the baby mesh",
            Set.copyOf(stripped.getBones().keySet()), equalTo(Set.copyOf(babyMesh.getBones().keySet())));
    }

    @Test
    @DisplayName("only the type pass carries the head-stripped alternate mesh, and it empties exactly the head subtree")
    void categoryPassCarriesHeadStrippedAlternateMesh() {
        // End-to-end canary over the shipped resource: the tooling must emit the cleared-bone key on the
        // type pass and the loader must derive the alternate mesh from it. A regenerated resource that
        // lost the key, or a loader that stopped deriving the mesh, fails here rather than silently
        // rendering a hat through a hat-bearing profession's headwear.
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        assertHeadStrippedTypePass(defs, "minecraft:villager", List.of("head", "hat", "hat_rim", "nose"));
        assertHeadStrippedTypePass(defs, "minecraft:zombie_villager", List.of("head", "hat", "hat_rim"));
    }

    /**
     * Asserts a category-pass family carries its alternate mesh on the {@code type} pass alone, that the
     * mesh empties every named head-subtree bone while the body keeps its cubes, and that the bone
     * hierarchy is otherwise untouched.
     *
     * @param defs the loaded index
     * @param entityId the category-pass entity
     * @param clearedBones the head-subtree bones the alternate mesh must empty
     */
    private static void assertHeadStrippedTypePass(
        ConcurrentMap<String, Entity> defs,
        String entityId,
        List<String> clearedBones
    ) {
        OverlayLayer typePass = categoryPass(defs, entityId, "type");
        assertThat(entityId + " type pass carries an alternate mesh", typePass.noHatModel().isPresent(), is(true));
        assertThat(entityId + " profession pass carries no alternate mesh",
            categoryPass(defs, entityId, "profession").noHatModel().isPresent(), is(false));
        assertThat(entityId + " profession_level pass carries no alternate mesh",
            categoryPass(defs, entityId, "profession_level").noHatModel().isPresent(), is(false));

        EntityModelData full = typePass.model();
        EntityModelData stripped = typePass.noHatModel().get();
        for (String bone : clearedBones) {
            assertThat(entityId + " '" + bone + "' is on the pass mesh", full.getBones().containsKey(bone), is(true));
            assertThat(entityId + " '" + bone + "' owns cubes on the pass mesh", full.getBones().get(bone).getCubes().isEmpty(), is(false));
            assertThat(entityId + " '" + bone + "' is emptied on the alternate mesh", stripped.getBones().get(bone).getCubes().isEmpty(), is(true));
        }
        assertThat(entityId + " body is off the head subtree and keeps its cubes",
            stripped.getBones().get("body").getCubes().isEmpty(), is(false));

        assertThat(entityId + " the alternate mesh keeps every bone",
            Set.copyOf(stripped.getBones().keySet()), equalTo(Set.copyOf(full.getBones().keySet())));
        for (String bone : full.getBones().keySet()) {
            assertThat(entityId + " '" + bone + "' keeps its pivot",
                stripped.getBones().get(bone).getPivot(), equalTo(full.getBones().get(bone).getPivot()));
            assertThat(entityId + " '" + bone + "' keeps its parent",
                stripped.getBones().get(bone).getParent(), equalTo(full.getBones().get(bone).getParent()));
        }
    }

    @Test
    @DisplayName("equipment layers ship their render layer and material asset table, mapping every material to an existing asset")
    void equipmentLayersShipTheirMaterialAssetTable() {
        // End-to-end canary over the shipped resource: the tooling must emit `layer_type` and the
        // `material_assets` table, and the loader must decode both. A material mapped to the wrong asset
        // id resolves to no equipment layers and silently drops the texture, and the parity harness
        // equips nothing, so an inert table would leave the suite green with every saddle untextured.
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();

        // The three shapes the table exists for: the shared saddle asset, the identity-named armor
        // tier, and the llama carpet whose asset name is NOT its material name.
        assertEquipmentAsset(defs, "minecraft:pig", "saddle", LayerType.PIG_SADDLE, "saddle", "minecraft:saddle");
        assertEquipmentAsset(defs, "minecraft:horse", "body", LayerType.HORSE_BODY, "leather", "minecraft:leather");
        assertEquipmentAsset(defs, "minecraft:llama", "body", LayerType.LLAMA_BODY, "white", "minecraft:white_carpet");
        assertEquipmentAsset(defs, "minecraft:llama", "body", LayerType.LLAMA_BODY, "red", "minecraft:red_carpet");
        assertEquipmentAsset(defs, "minecraft:trader_llama", "body", LayerType.LLAMA_BODY, "trader_llama", "minecraft:trader_llama");
        assertEquipmentAsset(defs, "minecraft:happy_ghast", "body", LayerType.HAPPY_GHAST_BODY, "white_harness", "minecraft:white_harness");
        assertEquipmentAsset(defs, "minecraft:wolf", "body", LayerType.WOLF_BODY, "armadillo_scute", "minecraft:armadillo_scute");
        // The nautilus body offers no leather tier, so its default must be one of the tiers it does ship.
        assertEquipmentAsset(defs, "minecraft:nautilus", "body", LayerType.NAUTILUS_BODY, "copper", "minecraft:copper");
        assertDefaultMaterial(defs, "minecraft:nautilus", "body", "copper");
        assertDefaultMaterial(defs, "minecraft:horse", "body", "leather");
        assertDefaultMaterial(defs, "minecraft:trader_llama", "body", "white");

        // Every shipped equipment layer must resolve its own declared default - a default outside the
        // table is exactly the silent-drop failure this table exists to prevent.
        for (Entity definition : defs.values())
            for (Entity.EquipmentOverlay equipment : definition.layers().equipment())
                assertThat(definition.id() + " equipment layer '" + equipment.layerType().getId()
                        + "' resolves the material a caller names none for",
                    equipment.assetFor("").isPresent(), is(true));
    }

    @Test
    @DisplayName("saddle layers whose renderer parameterises them ship per-entity, never crossed between the entities sharing that renderer")
    void parameterisedSaddleLayersShipPerEntity() {
        // End-to-end canary over the shipped resource: DonkeyRenderer (donkey + mule) and
        // UndeadHorseRenderer (skeleton_horse + zombie_horse) each take their saddle's render layer and
        // mesh as constructor parameters, so both come from the entity's own renderer registration. A
        // resolver keyed off the renderer CLASS instead of the entity would silently give both members of
        // a pair the same layer and the same mesh, and the parity harness saddles nothing, so the suite
        // would stay green with a mule wearing a donkey's saddle.
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();

        assertEquipmentAsset(defs, "minecraft:donkey", "saddle", LayerType.DONKEY_SADDLE, "saddle", "minecraft:saddle");
        assertEquipmentAsset(defs, "minecraft:mule", "saddle", LayerType.MULE_SADDLE, "saddle", "minecraft:saddle");
        assertEquipmentAsset(defs, "minecraft:skeleton_horse", "saddle", LayerType.SKELETON_HORSE_SADDLE, "saddle", "minecraft:saddle");
        assertEquipmentAsset(defs, "minecraft:zombie_horse", "saddle", LayerType.ZOMBIE_HORSE_SADDLE, "saddle", "minecraft:saddle");

        // The undead horses keep the body-armor layer they already had - it resolves from the renderer's
        // own statics, so the parameterised saddle must be an addition, never a replacement.
        assertEquipmentAsset(defs, "minecraft:skeleton_horse", "body", LayerType.HORSE_BODY, "iron", "minecraft:iron");
        assertEquipmentAsset(defs, "minecraft:zombie_horse", "body", LayerType.HORSE_BODY, "iron", "minecraft:iron");

        // Distinct meshes where vanilla registers distinct ones: the donkey's saddle is baked at 0.87 and
        // the mule's at 0.92, which lands as a different body pivot.
        assertThat("donkey and mule saddles are separately baked meshes",
            equipmentLayer(defs, "minecraft:donkey", "saddle").model().getBones().get("body").getPivot(),
            is(not(equalTo(equipmentLayer(defs, "minecraft:mule", "saddle").model().getBones().get("body").getPivot()))));

        // ... and one shared mesh where vanilla registers the same one: both undead horses bake the
        // unscaled EquineSaddleModel, so they must join on a single geometry entry rather than duplicate
        // it. Read at a bone rather than at the mesh: each layer's resting strip copies the bone map, so
        // the two hold different maps over the same bones.
        assertThat("skeleton and zombie horse saddles share one baked mesh",
            equipmentLayer(defs, "minecraft:skeleton_horse", "saddle").model().getBones().get("body"),
            sameInstance(equipmentLayer(defs, "minecraft:zombie_horse", "saddle").model().getBones().get("body")));
    }

    /** The bones each saddle draws only while something is riding, by the entity wearing it. */
    private static final Map<String, List<String>> RIDDEN_BONES = Map.of(
        "minecraft:camel", List.of("reins"),
        "minecraft:camel_husk", List.of("reins"),
        "minecraft:donkey", List.of("left_saddle_line", "right_saddle_line"),
        "minecraft:mule", List.of("left_saddle_line", "right_saddle_line"),
        "minecraft:horse", List.of("left_saddle_line", "right_saddle_line"),
        "minecraft:skeleton_horse", List.of("left_saddle_line", "right_saddle_line"),
        "minecraft:zombie_horse", List.of("left_saddle_line", "right_saddle_line"));

    @Test
    @DisplayName("a saddle rests without the reins it draws only while ridden, and the toggle puts them back")
    void saddleReinsRestUndrawn() {
        // A layer is posed by a model class of its own, which is not always the one that baked its
        // mesh: every equine saddle is posed by EquineSaddleModel while a donkey's is baked by
        // DonkeyModel. That class writes its reins' visibility from isRidden, which a render state is
        // built holding false, so a resting saddle carries the mesh's other bones and not those, and
        // only a selection puts them back. Reading the baking class instead answers the wearer's chest
        // gate for a mesh with reins.
        ConcurrentMap<String, Entity> defs = EntityModelLoader.load();
        for (Map.Entry<String, List<String>> wearer : RIDDEN_BONES.entrySet()) {
            String entityId = wearer.getKey();
            Entity.EquipmentOverlay saddle = equipmentLayer(defs, entityId, "saddle");
            assertThat(entityId + " saddle rests with its own strap",
                saddle.model().getBones().get("saddle").isVisible(), is(true));
            // Carried and not drawn rather than absent: a selection can ask for them, so the mesh
            // keeps them standing at rest visibility rather than dropping them.
            for (String bone : wearer.getValue())
                assertThat(entityId + " saddle rests without '" + bone + "'",
                    saddle.model().getBones().get(bone).isVisible(), is(false));

            Entity ridden = defs.get(entityId).resolve(AppearanceOptions.builder()
                .equipment(Map.of("saddle", "saddle")).toggles(Set.of("ridden")).build());
            EntityModelData riddenSaddle = ridden.layers().equipment().stream()
                .filter(overlay -> overlay.slot().equals("saddle"))
                .findFirst().orElseThrow().model();
            for (String bone : wearer.getValue())
                assertThat(entityId + " draws '" + bone + "' while ridden",
                    riddenSaddle.getBones().get(bone).isVisible(), is(true));
        }
    }

    /**
     * Asserts a shipped equipment layer carries the expected render layer and maps a material to an
     * equipment asset id.
     *
     * @param defs the loaded index
     * @param entityId the entity owning the layer
     * @param slot the equipment slot the layer is gated on
     * @param layerType the render layer the layer must declare
     * @param material the material to resolve
     * @param assetId the equipment asset id that material must name
     */
    private static void assertEquipmentAsset(
        ConcurrentMap<String, Entity> defs,
        String entityId,
        String slot,
        LayerType layerType,
        String material,
        String assetId
    ) {
        Entity.EquipmentOverlay equipment = equipmentLayer(defs, entityId, slot);
        assertThat(entityId + " '" + slot + "' render layer", equipment.layerType(), is(layerType));
        assertThat(entityId + " '" + slot + "' material '" + material + "' asset",
            equipment.assetFor(material).map(ResourceId::id), is(Optional.of(assetId)));
    }

    /**
     * Asserts a shipped equipment layer's default material - the one a render gets by selecting the
     * slot without naming a material.
     *
     * @param defs the loaded index
     * @param entityId the entity owning the layer
     * @param slot the equipment slot the layer is gated on
     * @param material the material the layer must default to
     */
    private static void assertDefaultMaterial(
        ConcurrentMap<String, Entity> defs,
        String entityId,
        String slot,
        String material
    ) {
        Entity.EquipmentOverlay equipment = equipmentLayer(defs, entityId, slot);
        // The default is carried as its ASSET under the unselected key rather than as the name of
        // another key, so what is assertable is that naming nothing and naming the material land on
        // the same asset - which is the whole of what the name was ever read for.
        assertThat(entityId + " '" + slot + "' resolves the same asset blank as by name",
            equipment.assetFor(""), is(equipment.assetFor(material)));
        assertThat(entityId + " '" + slot + "' names " + material + " as a material of its own",
            equipment.assetFor(material).isPresent(), is(true));
    }

    /** The equipment layer of an entity gated on {@code slot}. */
    private static Entity.EquipmentOverlay equipmentLayer(
        ConcurrentMap<String, Entity> defs, String entityId, String slot) {
        return defs.get(entityId)
            .layers()
            .equipment()
            .stream()
            .filter(overlay -> slot.equals(overlay.slot()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("entity '" + entityId + "' has no '" + slot + "' equipment layer"));
    }

    /** The overlay pass of an entity whose texture axis token is {@code textureBy}. */
    private static OverlayLayer categoryPass(ConcurrentMap<String, Entity> defs, String entityId, String textureBy) {
        return defs.get(entityId)
            .overlays()
            .stream()
            .filter(overlay -> overlay.textureBy().map(TextureAxis::token).filter(textureBy::equals).isPresent())
            .findFirst()
            .orElseThrow(() -> new AssertionError("entity '" + entityId + "' has no '" + textureBy + "' category pass"));
    }

    /** The option-encoded coat sub-definition for a variant family's option. */
    private static Entity coat(ConcurrentMap<String, Entity> defs, String familyId, String option) {
        return defs.get(familyId).axes().variant().options().get(option);
    }
}
