package lib.minecraft.renderer;

import lib.minecraft.renderer.asset.Entity.OverlayLayer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.appearance.Villager;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pack.MCMeta.Villager.Hat;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.raster.PassDeclaration;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.support.StubRendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Truth table for the villager robe pass' mesh select - the predicate deciding whether the type / robe
 * overlay draws its full mesh or the head-stripped alternate, so a profession hat is never stacked on
 * top of a type hat - together with the two refs that feed it: the texture the pass DRAWS and the
 * sidecar its type hat flag is READ from, which a baby splits apart.
 */
@DisplayName("Villager hat mesh-select rule")
class EntityRendererVillagerHatTest {

    @Test
    @DisplayName("a hatless profession always draws the full mesh")
    void hatlessProfessionNeverSuppresses() {
        assertThat(EntityRenderer.useFullModel(Hat.NONE, Hat.NONE), is(true));
        assertThat(EntityRenderer.useFullModel(Hat.NONE, Hat.PARTIAL), is(true));
        assertThat(EntityRenderer.useFullModel(Hat.NONE, Hat.FULL), is(true));
    }

    @Test
    @DisplayName("a partial-hat profession suppresses only over a full-hat type")
    void partialProfessionSuppressesOnlyOverAFullType() {
        assertThat(EntityRenderer.useFullModel(Hat.PARTIAL, Hat.NONE), is(true));
        assertThat(EntityRenderer.useFullModel(Hat.PARTIAL, Hat.PARTIAL), is(true));
        assertThat(EntityRenderer.useFullModel(Hat.PARTIAL, Hat.FULL), is(false));
    }

    @Test
    @DisplayName("a full-hat profession always suppresses, whatever the type wears")
    void fullProfessionAlwaysSuppresses() {
        assertThat(EntityRenderer.useFullModel(Hat.FULL, Hat.NONE), is(false));
        assertThat(EntityRenderer.useFullModel(Hat.FULL, Hat.PARTIAL), is(false));
        assertThat(EntityRenderer.useFullModel(Hat.FULL, Hat.FULL), is(false));
    }

    /**
     * Lists which of the truth table's rows vanilla can actually reach, as an inventory rather than as
     * added coverage - the three tests above already assert every cell of the table.
     */
    @Test
    @DisplayName("the vanilla-reachable rows: plains/none and plains/butcher keep the robe head, desert/butcher and plains/farmer strip it")
    void vanillaReachableRows() {
        // plains ships no sidecar (type NONE); desert and snow ship hat=full. The professions shipping a
        // sidecar are farmer / fisherman / fletcher / librarian / shepherd (full) and butcher (partial).
        assertThat("plains villager, no profession", EntityRenderer.useFullModel(Hat.NONE, Hat.NONE), is(true));
        assertThat("plains butcher keeps the headband over a hatless type", EntityRenderer.useFullModel(Hat.PARTIAL, Hat.NONE), is(true));
        assertThat("desert butcher drops the turban", EntityRenderer.useFullModel(Hat.PARTIAL, Hat.FULL), is(false));
        assertThat("plains farmer's straw hat stands alone", EntityRenderer.useFullModel(Hat.FULL, Hat.NONE), is(false));
    }

    @Test
    @DisplayName("the baby robe pass draws the baby directory but reads its type hat flag off the adult type sidecar")
    void babyTypePassReadsTheAdultHatSidecar() {
        // Vanilla reads the type hat off a hardcoded "type" token before it tests the age, and only the
        // drawn texture swaps to baby/. The baby/ directory ships no sidecars, so reading the flag off the
        // drawn ref would silently yield NONE and stop desert / snow suppressing a baby's robe head.
        OverlayLayer babyPass = pass("type", "villager/baby/plains");
        AppearanceOptions baby = AppearanceOptions.builder().age(Age.BABY).villagerType(Villager.Type.DESERT).build();
        Optional<String> drawn = EntityRenderer.resolveOverlayTextureRef(babyPass, baby, "villager");
        assertThat("the baby pass draws the baby directory", drawn, is(Optional.of("villager/baby/desert")));
        assertThat("its hat flag still comes from the adult type sidecar",
            EntityRenderer.typeHatTextureRef(babyPass, baby, "villager", drawn), is(Optional.of("villager/type/desert")));

        OverlayLayer adultPass = pass("type", "villager/type/plains");
        AppearanceOptions adult = AppearanceOptions.builder().villagerType(Villager.Type.DESERT).build();
        Optional<String> adultDrawn = EntityRenderer.resolveOverlayTextureRef(adultPass, adult, "villager");
        assertThat("the adult pass draws the type directory", adultDrawn, is(Optional.of("villager/type/desert")));
        assertThat("and its hat ref recomputes the very ref it drew",
            EntityRenderer.typeHatTextureRef(adultPass, adult, "villager", adultDrawn), is(adultDrawn));
    }

    @Test
    @DisplayName("the drawn robe directory follows the pass, never the appearance's age")
    void theRobeDirectoryFollowsThePass() {
        // The baby robe's UV layout belongs to the baby mesh, so the directory swap must be inseparable
        // from the mesh swap. An adult pass rendered under a baby appearance - what a type-overlay entity
        // with no age.baby geometry would hit - keeps the adult robe rather than binding baby texels onto
        // adult cubes.
        AppearanceOptions baby = AppearanceOptions.builder().age(Age.BABY).villagerType(Villager.Type.SNOW).build();
        assertThat("an adult pass keeps the type directory for a baby appearance",
            EntityRenderer.resolveOverlayTextureRef(pass("type", "villager/type/plains"), baby, "villager"),
            is(Optional.of("villager/type/snow")));
        assertThat("a baby pass keeps the baby directory for an adult appearance",
            EntityRenderer.resolveOverlayTextureRef(pass("type", "villager/baby/plains"),
                AppearanceOptions.builder().villagerType(Villager.Type.SNOW).build(), "villager"),
            is(Optional.of("villager/baby/snow")));
    }

    @Test
    @DisplayName("a pass off the type axis reads its hat flag from its own resolved ref")
    void aNonTypePassKeepsItsOwnRef() {
        Optional<String> own = Optional.of("villager/profession/farmer");
        assertThat(EntityRenderer.typeHatTextureRef(pass("profession", "villager/profession/none"),
            AppearanceOptions.builder().age(Age.BABY).build(), "villager", own), is(own));
    }

    @Test
    @DisplayName("the hat flag is read off the entity-qualified sidecar, and every absence reads NONE")
    void villagerHatReadsTheEntityQualifiedSidecar() {
        RendererContext context = new MetaContext(StubRendererContext.builder().build(), Map.of(
            "minecraft:entity/villager/type/desert", villagerMeta(Hat.FULL),
            "minecraft:entity/villager/profession/butcher", villagerMeta(Hat.PARTIAL),
            "minecraft:entity/villager/type/plains", MCMeta.EMPTY,
            "villager/type/savanna", villagerMeta(Hat.FULL)));

        assertThat("a declared full hat reads through",
            EntityRenderer.villagerHat(context, Optional.of("villager/type/desert")), is(Hat.FULL));
        assertThat("a declared partial hat reads through",
            EntityRenderer.villagerHat(context, Optional.of("villager/profession/butcher")), is(Hat.PARTIAL));
        assertThat("a sidecar carrying no villager section reads NONE",
            EntityRenderer.villagerHat(context, Optional.of("villager/type/plains")), is(Hat.NONE));
        assertThat("a texture shipping no sidecar at all reads NONE",
            EntityRenderer.villagerHat(context, Optional.of("villager/type/taiga")), is(Hat.NONE));
        assertThat("an axis that selected no ref reads NONE",
            EntityRenderer.villagerHat(context, Optional.empty()), is(Hat.NONE));
        // The ref is qualified before the lookup, so a sidecar keyed by the bare ref is never the one
        // found - which is what stops an unqualified id resolving a texture from another directory.
        assertThat("the ref is qualified with minecraft:entity/ before the lookup",
            EntityRenderer.villagerHat(context, Optional.of("villager/type/savanna")), is(Hat.NONE));
    }

    /**
     * A sidecar declaring a {@code villager} hat flag and nothing else, which is the shape the robe and
     * profession textures ship.
     *
     * @param hat the hat flag the sidecar declares
     * @return the sidecar carrying it
     */
    private static MCMeta villagerMeta(Hat hat) {
        return new MCMeta(new ResourceId("minecraft", "entity"), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of(new MCMeta.Villager(hat)));
    }

    /**
     * A context answering sidecar lookups from a seeded map and forwarding everything else, so the test
     * states the one seam the hat read crosses.
     *
     * @param delegate the context every other lookup forwards to
     * @param metas the texture id to sidecar bindings this context can answer
     */
    private record MetaContext(@NotNull RendererContext delegate, @NotNull Map<String, MCMeta> metas)
        implements RendererContext.Forwarding {

        /** {@inheritDoc} */
        @Override
        public @NotNull Optional<MCMeta> findMeta(@NotNull String textureId) {
            return Optional.ofNullable(this.metas.get(textureId));
        }

    }

    /**
     * A bare overlay carrying the {@code texture_by} axis the ref resolution keys off plus the baked
     * texture ref the robe directory is read from.
     *
     * @param textureBy the pass' texture axis
     * @param textureRef the pass' baked texture ref
     * @return the overlay the ref resolution reads
     */
    private static OverlayLayer pass(String textureBy, String textureRef) {
        return new OverlayLayer(new EntityModelData(), Optional.of(textureRef), PassDeclaration.DEFAULT,
            0xFFFFFFFF, true, Optional.empty(), Optional.of(textureBy),
            Optional.empty(), Optional.empty(), EntityPose.NONE);
    }

}
