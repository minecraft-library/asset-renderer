package lib.minecraft.renderer.tooling.colormap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins how the colormap row's {@code type} is derived from its coordinate's file stem rather than
 * read off a declared constant - the stem between the path's last separator and its last dot,
 * uppercased.
 */
@DisplayName("ColorMapReader derives a row's type from its coordinate's file stem")
class ColorMapWalkTest {

    @Test
    @DisplayName("the stem is taken between the last separator and the last dot")
    void stemIsBoundedBySeparatorAndDot() {
        assertEquals("GRASS", ColorMapReader.typeOf("assets/minecraft/textures/colormap/grass.png"));
        assertEquals("DRY_FOLIAGE", ColorMapReader.typeOf("assets/minecraft/textures/colormap/dry_foliage.png"));
    }

    @Test
    @DisplayName("a dotted directory does not shorten the stem")
    void dotsBeforeTheFileNameAreNotTheExtension() {
        assertEquals("GRASS", ColorMapReader.typeOf("assets/mine.craft/colormap/grass.png"));
    }

}
