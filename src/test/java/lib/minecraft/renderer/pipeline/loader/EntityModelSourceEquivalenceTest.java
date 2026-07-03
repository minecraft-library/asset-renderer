package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader.BlockOverlayLayer;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader.EntityDefinition;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader.OverlayLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * Confirms the runtime {@code v2} source (family form flattened by {@link EntityFamilyFlattener})
 * yields {@link EntityDefinition}s field-identical to the flat {@code v1} source. Together with the
 * JSON round-trip in {@code EntityModelsV2RoundTripTest} this pins byte-stability at the runtime
 * layer without standing up the render/parity harness: identical definitions rasterize to identical
 * pixels by construction.
 */
@DisplayName("EntityModelLoader v1 vs v2 source equivalence")
class EntityModelSourceEquivalenceTest {

    private static final String SOURCE_PROPERTY = "asset.entity.models";

    @Test
    @DisplayName("load() under v2 matches v1 across every entity's definition fields")
    void v2LoadMatchesV1() {
        ConcurrentMap<String, EntityDefinition> v1 = withSource("v1", EntityModelLoader::load);
        ConcurrentMap<String, EntityDefinition> v2 = withSource("v2", EntityModelLoader::load);

        assertThat("v1 loaded a non-empty entity set", v1.size(), greaterThan(0));
        assertThat("v2 has the same entity ids as v1", v2.keySet(), equalTo(v1.keySet()));

        for (String id : v1.keySet()) assertDefinitionsEqual(id, v1.get(id), v2.get(id));
    }

    @Test
    @DisplayName("v2 carries wolf wild/tame/angry state textures; v1 leaves them empty")
    void v2CarriesWolfStateTextures() {
        ConcurrentMap<String, EntityDefinition> v1 = withSource("v1", EntityModelLoader::load);
        ConcurrentMap<String, EntityDefinition> v2 = withSource("v2", EntityModelLoader::load);

        assertThat("v1 has no state textures", v1.get("minecraft:wolf_pale").stateTextures().isEmpty(), is(true));

        EntityDefinition paleV2 = v2.get("minecraft:wolf_pale");
        assertThat(paleV2.stateTextures().keySet(), containsInAnyOrder("wild", "tame", "angry"));
        assertThat(paleV2.stateTextures().get("wild"), is("wolf/wolf"));
        assertThat(paleV2.stateTextures().get("tame"), is("wolf/wolf_tame"));
        assertThat(paleV2.stateTextures().get("angry"), is("wolf/wolf_angry"));
        assertThat("wild state equals the default texture_ref",
            Optional.of(paleV2.stateTextures().get("wild")), equalTo(paleV2.textureRef()));

        EntityDefinition ashenV2 = v2.get("minecraft:wolf_ashen");
        assertThat(ashenV2.stateTextures().get("tame"), is("wolf/wolf_ashen_tame"));

        assertThat("a single-asset variant carries no state textures",
            v2.get("minecraft:cow_temperate").stateTextures().isEmpty(), is(true));
    }

    @Test
    @DisplayName("v2 carries wolf/cat collar textures; v1 and non-collar entities have none")
    void v2CarriesCollarTextures() {
        ConcurrentMap<String, EntityDefinition> v1 = withSource("v1", EntityModelLoader::load);
        ConcurrentMap<String, EntityDefinition> v2 = withSource("v2", EntityModelLoader::load);

        assertThat("v1 wolf has no collar", v1.get("minecraft:wolf_pale").collarTexture().isPresent(), is(false));
        assertThat(v2.get("minecraft:wolf_pale").collarTexture(), equalTo(Optional.of("wolf/wolf_collar")));
        assertThat("every wolf variant shares the family collar",
            v2.get("minecraft:wolf_ashen").collarTexture(), equalTo(Optional.of("wolf/wolf_collar")));
        assertThat(v2.get("minecraft:cat_black").collarTexture(), equalTo(Optional.of("cat/cat_collar")));
        assertThat("a non-collar entity has none", v2.get("minecraft:cow_temperate").collarTexture().isPresent(), is(false));
    }

    @Test
    @DisplayName("withoutBlockOverlays drops block overlays and preserves every other field")
    void withoutBlockOverlaysStripsOnlyBlockOverlays() {
        ConcurrentMap<String, EntityDefinition> defs = withSource("v1", EntityModelLoader::load);
        EntityDefinition snowGolem = defs.get("minecraft:snow_golem");
        assertThat("snow_golem has block overlays to drop", snowGolem.blockOverlays().isEmpty(), is(false));

        EntityDefinition stripped = snowGolem.withoutBlockOverlays();
        assertThat(stripped.blockOverlays().isEmpty(), is(true));
        assertThat(stripped.model(), is(snowGolem.model()));
        assertThat(stripped.textureRef(), equalTo(snowGolem.textureRef()));
        assertThat(stripped.overlays(), equalTo(snowGolem.overlays()));
        assertThat(stripped.baseTintArgb(), equalTo(snowGolem.baseTintArgb()));
        assertThat(stripped.stateTextures(), equalTo(snowGolem.stateTextures()));
    }

    /**
     * Asserts two definitions for the same id agree on every observable field (geometry proxy,
     * texture, overlays, block overlays, tint, yaw addend, scale).
     */
    private static void assertDefinitionsEqual(String id, EntityDefinition a, EntityDefinition b) {
        assertThat(id + " texture_ref", b.textureRef(), equalTo(a.textureRef()));
        assertThat(id + " base_tint", b.baseTintArgb(), equalTo(a.baseTintArgb()));
        assertThat(id + " setup_yaw_addend", b.setupYawAddend(), equalTo(a.setupYawAddend()));
        assertThat(id + " renderer_scale", b.rendererScale(), equalTo(a.rendererScale()));

        // Geometry proxy: same bone set + texture atlas dims (geometry_ref equality is already
        // pinned at the JSON layer; this catches a mis-wired model resolution at runtime).
        assertThat(id + " bones", b.model().getBones().keySet(), equalTo(a.model().getBones().keySet()));
        assertThat(id + " texture_width", b.model().getTextureWidth(), equalTo(a.model().getTextureWidth()));
        assertThat(id + " texture_height", b.model().getTextureHeight(), equalTo(a.model().getTextureHeight()));

        assertThat(id + " overlay count", b.overlays().size(), equalTo(a.overlays().size()));
        for (int i = 0; i < a.overlays().size(); i++) {
            OverlayLayer oa = a.overlays().get(i), ob = b.overlays().get(i);
            assertThat(id + " overlay[" + i + "] texture", ob.textureRef(), equalTo(oa.textureRef()));
            assertThat(id + " overlay[" + i + "] emissive", ob.emissive(), is(oa.emissive()));
            assertThat(id + " overlay[" + i + "] tint", ob.tintArgb(), equalTo(oa.tintArgb()));
            assertThat(id + " overlay[" + i + "] skipBounds", ob.skipBounds(), is(oa.skipBounds()));
        }

        assertThat(id + " block-overlay count", b.blockOverlays().size(), equalTo(a.blockOverlays().size()));
        for (int i = 0; i < a.blockOverlays().size(); i++) {
            BlockOverlayLayer ba = a.blockOverlays().get(i), bb = b.blockOverlays().get(i);
            assertThat(id + " block-overlay[" + i + "] block_id", bb.blockId(), equalTo(ba.blockId()));
            assertThat(id + " block-overlay[" + i + "] attached_bone", bb.attachedBone(), equalTo(ba.attachedBone()));
            assertThat(id + " block-overlay[" + i + "] transform count", bb.transforms().size(), equalTo(ba.transforms().size()));
        }
    }

    /** Runs {@code body} with the {@value #SOURCE_PROPERTY} property set, restoring it afterwards. */
    private static <T> T withSource(String value, Supplier<T> body) {
        String previous = System.getProperty(SOURCE_PROPERTY);
        System.setProperty(SOURCE_PROPERTY, value);
        try {
            return body.get();
        } finally {
            if (previous == null) System.clearProperty(SOURCE_PROPERTY);
            else System.setProperty(SOURCE_PROPERTY, previous);
        }
    }
}
