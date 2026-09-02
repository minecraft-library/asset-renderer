package lib.minecraft.renderer.tooling.geometry;

import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins for {@link GeometryManifest}'s registration contract: the minted key IS the dedupe
 * identity, so two requests naming one factory coordinate collapse onto the first subject's
 * provenance; each discriminator - grow, whole-mesh scale, a float parameter, a bound integer
 * parameter - mints its own key and splits the parse; and a simple-name collision across two
 * packages fails loudly rather than merging two meshes. Reads no client jar and no shipped
 * resource.
 */
@DisplayName("tooling GeometryManifest dedupe identity, discriminator keys and collision failure")
class GeometryManifestTest {

    @Test
    @DisplayName("dedupe identity IS the key; discriminators split parses; collisions fail loud")
    void manifestDedupeAndKeys() {
        GeometryManifest manifest = new GeometryManifest();
        GeometryRequest plain = GeometryRequest.body("a/b/WolfModel", "createBodyLayer", "minecraft:wolf", null, null, null, 1f);
        String key = manifest.register(plain);
        assertEquals("WolfModel#createBodyLayer", key);
        assertEquals(key, manifest.register(GeometryRequest.body("a/b/WolfModel", "createBodyLayer", "minecraft:sheep", null, null, null, 1f)),
            "same coordinate dedupes; first subject retained");
        assertEquals(1, manifest.size());
        assertEquals("minecraft:wolf", manifest.entries().get(key).subjectId(), "first-request provenance");

        String grown = manifest.register(GeometryRequest.overlay("a/b/WolfModel", "createBodyLayer", "minecraft:wolf", null, null, new float[]{0.25f, 0.25f, 0.25f}));
        assertEquals("WolfModel#createBodyLayer@grow=0.25", grown);
        String asymmetric = manifest.register(GeometryRequest.overlay("a/b/WolfModel", "createBodyLayer", "minecraft:wolf", null, null, new float[]{0.5f, 0.25f, 0.25f}));
        assertEquals("WolfModel#createBodyLayer@grow=0.5,0.25,0.25", asymmetric);
        String scaled = manifest.register(GeometryRequest.body("a/b/WolfModel", "createBodyLayer", "minecraft:wolf", null, null, null, 4.5f));
        assertEquals("WolfModel#createBodyLayer@scaled=4.5", scaled);
        String fparam = manifest.register(GeometryRequest.body("a/b/WolfModel", "createBodyLayer", "minecraft:wolf", null, null, 0.87f, 1f));
        assertEquals("WolfModel#createBodyLayer@fparam=0.87", fparam);
        String bound = manifest.register(GeometryRequest.equipment("a/b/WolfModel", "createBodyLayer", "(ZF)V", "minecraft:wolf", null, null, null, GeometryRequest.NO_GROW, 1f));
        assertEquals("WolfModel#createBodyLayer@iparam=0:0", bound);
        assertNotEquals(key, grown);
        assertEquals(6, manifest.size());

        // simple-name collision across packages fails loud, never merges meshes
        assertThrows(ToolingException.class,
            () -> manifest.register(GeometryRequest.body("other/pkg/WolfModel", "createBodyLayer", "minecraft:x", null, null, null, 1f)));
    }

    /** One request, varying only in what each case is about. */
    private static GeometryRequest request(
        Integer texWidth, float[] floats, float[] grow, GeometryRequest.PoseParam pose) {

        return new GeometryRequest("a/b/WolfModel", "createBodyLayer", "minecraft:wolf", YAxis.DOWN,
            texWidth, null, null, floats, grow, 1f, null, pose, null);
    }

    @Test
    @DisplayName("two requests minting one key must BE one request, or the second mesh is dropped in silence")
    void aKeyTwoRequestsMintIsRefusedWhereTheyDiffer() {
        // The key encodes no texture override, so these two are one key and two meshes: the entry
        // stamps texture_size from the override, and putIfAbsent keeps whichever registered first.
        GeometryManifest overridden = new GeometryManifest();
        overridden.register(request(null, null, GeometryRequest.NO_GROW, null));
        ToolingException refused = assertThrows(ToolingException.class,
            () -> overridden.register(request(32, null, GeometryRequest.NO_GROW, null)));
        assertTrue(refused.getMessage().contains("texWidthOverride"),
            "the refusal names the member that differs: " + refused.getMessage());

        // And the float table, whose PRESENCE selects the parser's walk mode where the key encodes
        // only a non-zero slot 0 - so a bound all-zero table and an absent one mint one key.
        GeometryManifest seeded = new GeometryManifest();
        seeded.register(request(null, null, GeometryRequest.NO_GROW, null));
        assertThrows(ToolingException.class,
            () -> seeded.register(request(null, new float[]{0f}, GeometryRequest.NO_GROW, null)));
    }

    @Test
    @DisplayName("a member the mesh is not a function of is not a difference")
    void theExcludedMembersStillDedupe() {
        GeometryManifest manifest = new GeometryManifest();
        String key = manifest.register(new GeometryRequest("a/b/WolfModel", "createBodyLayer",
            "minecraft:wolf", YAxis.DOWN, null, null, null, null, GeometryRequest.NO_GROW, 1f,
            null, null, null));
        assertEquals(key, manifest.register(new GeometryRequest("a/b/WolfModel", "createBodyLayer",
                "minecraft:sheep", YAxis.UP, null, null, null, null, GeometryRequest.NO_GROW, 1f,
                null, null, null)),
            "subjectId is provenance and yAxis reaches neither the parse nor the entry");
        assertEquals(1, manifest.size());
    }

    @Test
    @DisplayName("equal array contents are one request, which a record's own equality would deny")
    void arraysAreComparedByValueRatherThanByReference() {
        // Three components are arrays and a record compares one by reference, so the generated
        // equals answers "different" for every legitimate re-registration - which would refuse the
        // whole corpus rather than the collision this guards.
        GeometryManifest manifest = new GeometryManifest();
        String key = manifest.register(
            request(null, new float[]{0.87f}, new float[]{0.25f, 0.25f, 0.25f},
                new GeometryRequest.PoseParam(1, new float[]{0.5f, -0.5f, 0f})));
        assertEquals(key, manifest.register(
                request(null, new float[]{0.87f}, new float[]{0.25f, 0.25f, 0.25f},
                    new GeometryRequest.PoseParam(1, new float[]{0.5f, -0.5f, 0f}))),
            "same values in fresh arrays are the same request");
        assertEquals(1, manifest.size());
    }

    @Test
    @DisplayName("a bound pose is equal by the offset it seats, not by which array holds it")
    void poseParamComparesItsOffsetByValue() {
        assertEquals(new GeometryRequest.PoseParam(1, new float[]{0.5f, -0.5f, 0f}),
            new GeometryRequest.PoseParam(1, new float[]{0.5f, -0.5f, 0f}));
        assertEquals(new GeometryRequest.PoseParam(1, new float[]{0.5f, -0.5f, 0f}).hashCode(),
            new GeometryRequest.PoseParam(1, new float[]{0.5f, -0.5f, 0f}).hashCode());
        assertNotEquals(new GeometryRequest.PoseParam(1, new float[]{0.5f, -0.5f, 0f}),
            new GeometryRequest.PoseParam(1, new float[]{0.5f, 0.5f, 0f}));
        assertNotEquals(new GeometryRequest.PoseParam(1, new float[]{0.5f, -0.5f, 0f}),
            new GeometryRequest.PoseParam(2, new float[]{0.5f, -0.5f, 0f}));
    }

}
