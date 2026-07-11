package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ColorMapLoader} against the bundled {@code color_maps.json} snapshot: the native
 * read decodes the three biome colormaps, the mapping helper guards a {@code null} row list (the
 * historical NPE fix), and an unknown {@link ColorMap.Type} is skipped with a diagnostic.
 */
class ColorMapLoaderTest {

    private static @NotNull Diagnostics diagnostics() {
        return Diagnostics.root("test", Diagnostics.Output.NONE, null);
    }

    @Test
    @DisplayName("native load decodes the three bundled biome colormaps with non-empty pixels")
    void loadsThreeColormaps() {
        ConcurrentMap<ColorMap.Type, ColorMap> maps = ColorMapLoader.load();

        assertEquals(3, maps.size());
        assertTrue(maps.containsKey(ColorMap.Type.GRASS));
        assertTrue(maps.containsKey(ColorMap.Type.FOLIAGE));
        assertTrue(maps.containsKey(ColorMap.Type.DRY_FOLIAGE));
        assertTrue(maps.get(ColorMap.Type.GRASS).pixels().length > 0, "decoded pixels must be non-empty");
    }

    @Test
    @DisplayName("toColorMaps guards a null row list (the historical null-array NPE)")
    void nullRowsYieldEmpty() {
        assertEquals(0, ColorMapLoader.toColorMaps(null, diagnostics()).size());
    }

    @Test
    @DisplayName("toColorMaps skips an unknown type with a diagnostic instead of aborting")
    void skipsUnknownType() {
        Diagnostics diag = diagnostics();
        ConcurrentMap<ColorMap.Type, ColorMap> maps = ColorMapLoader.toColorMaps(
            List.of(new ColorMapLoader.ColorMapRow("NOT_A_TYPE", "AAAA")), diag);

        assertEquals(0, maps.size(), "the unknown-type row is skipped");
        assertEquals(1, diag.count(Diagnostics.Severity.WARN), "the skip is recorded as a warning");
    }

    @Test
    @DisplayName("toColorMaps decodes a Base64 pixel payload for a known type")
    void decodesKnownType() {
        String pixels = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        ConcurrentMap<ColorMap.Type, ColorMap> maps = ColorMapLoader.toColorMaps(
            List.of(new ColorMapLoader.ColorMapRow("GRASS", pixels)), diagnostics());

        assertEquals(1, maps.size());
        assertEquals(4, maps.get(ColorMap.Type.GRASS).pixels().length);
    }
}
