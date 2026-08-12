package lib.minecraft.renderer.tooling.geometry;

import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

}
