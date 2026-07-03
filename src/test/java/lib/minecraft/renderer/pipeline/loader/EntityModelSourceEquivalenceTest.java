package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader.BlockOverlayLayer;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader.EntityDefinition;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader.OverlayLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
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
