package lib.minecraft.renderer.tooling.colormap;

import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the colormap row's {@code type}, which the walk derives from its coordinate's file stem
 * rather than reading off a declared constant. The three names it must answer are the colormap-backed
 * members of the renderer's tint-target domain, which is what the renderer indexes a loaded map under
 * - so a stem that uppercased to anything else would emit a table naming a target that does not
 * exist.
 */
@DisplayName("ColorMapWalk derives a row's type from its coordinate's file stem")
class ColorMapWalkTest {

    @Test
    @DisplayName("every declared colormap's file stem uppercases to the name it is indexed under")
    void stemUppercasesToTheConstantName() {
        for (ColorMapPolicies policy : ColorMapPolicies.values()) {
            String stem = policy.name().toLowerCase(Locale.ROOT);
            assertEquals(policy.name(),
                ColorMapWalk.typeOf(VanillaSourceClasses.Paths.COLORMAP_DIR + stem + ".png"),
                policy.name() + " is indexed under its own file stem");
        }
    }

    @Test
    @DisplayName("the stem is taken between the last separator and the last dot")
    void stemIsBoundedBySeparatorAndDot() {
        assertEquals("GRASS", ColorMapWalk.typeOf("assets/minecraft/textures/colormap/grass.png"));
        assertEquals("DRY_FOLIAGE", ColorMapWalk.typeOf("assets/minecraft/textures/colormap/dry_foliage.png"));
    }

    @Test
    @DisplayName("a dotted directory does not shorten the stem")
    void dotsBeforeTheFileNameAreNotTheExtension() {
        assertEquals("GRASS", ColorMapWalk.typeOf("assets/mine.craft/colormap/grass.png"));
    }

}
