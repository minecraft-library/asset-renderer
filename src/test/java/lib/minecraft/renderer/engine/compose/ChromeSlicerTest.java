package lib.minecraft.renderer.engine.compose;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.compose.ChromeDecomposition.Edge;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Pins {@link ChromeSlicer} against art the client ships.
 * <p>
 * The round trip - decompose, re-assemble at the authored size, diff - is the assertion the slicer
 * has to pass before any resize of it means anything. It is not sufficient on its own: it holds
 * whatever band depth is derived, so a decomposition that resizes wrongly still round-trips. The
 * derived depths are therefore pinned beside it, because those are what a resize is a function of.
 */
@Tag("slow")
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("ChromeSlicer takes shipped art apart and puts it back")
class ChromeSlicerTest {

    private static RendererContext textures;

    @BeforeAll
    static void resolveTextures() {
        textures = ClientAssetsExtension.context();
    }

    private static PixelBuffer resolve(String id) {
        Optional<PixelBuffer> found = textures.resolveTextureAtTick(id, 0);
        assertThat("texture '" + id + "' resolves", found.isPresent(), is(true));
        return found.get();
    }

    private static PixelBuffer crop(PixelBuffer source, int width, int height) {
        PixelBuffer out = PixelBuffer.create(width, height);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                out.setPixel(x, y, source.getPixel(x, y));

        return out;
    }

    private static int differingPixels(PixelBuffer a, PixelBuffer b) {
        if (a.width() != b.width() || a.height() != b.height()) return Integer.MAX_VALUE;

        int differing = 0;
        for (int y = 0; y < a.height(); y++)
            for (int x = 0; x < a.width(); x++)
                if (a.getPixel(x, y) != b.getPixel(x, y)) differing++;

        return differing;
    }

    private static int roundTrip(PixelBuffer art) {
        ChromeDecomposition decomposition = ChromeSlicer.decompose(art, Optional.empty(), true);
        return differingPixels(art, ChromeSlicer.assemble(decomposition, art, art.width(), art.height()));
    }

    @Test
    @DisplayName("every container background round-trips at its authored size")
    void everyContainerBackgroundRoundTrips() {
        List<int[]> sizes = List.of(new int[] { 176, 222 }, new int[] { 176, 166 }, new int[] { 176, 133 });
        List<String> ids = List.of("minecraft:gui/container/generic_54", "minecraft:gui/container/shulker_box",
            "minecraft:gui/container/hopper");

        for (int i = 0; i < ids.size(); i++) {
            PixelBuffer art = crop(resolve(ids.get(i)), sizes.get(i)[0], sizes.get(i)[1]);
            assertThat(ids.get(i), roundTrip(art), is(equalTo(0)));
        }
    }

    @Test
    @DisplayName("the slot sprite and the tooltip pair round-trip")
    void spritesRoundTrip() {
        for (String id : List.of("minecraft:gui/sprites/container/slot",
                                 "minecraft:gui/sprites/tooltip/frame",
                                 "minecraft:gui/sprites/tooltip/background"))
            assertThat(id, roundTrip(resolve(id)), is(equalTo(0)));
    }

    @Test
    @DisplayName("the six-row chest derives the depths and periods the prototype measured")
    void theChestDerivesTheMeasuredModel() {
        PixelBuffer art = crop(resolve("minecraft:gui/container/generic_54"), 176, 222);
        ChromeDecomposition d = ChromeSlicer.decompose(art, Optional.empty(), true);

        assertThat("derived depths", List.of(d.left(), d.top(), d.right(), d.bottom()),
            is(equalTo(List.of(7, 17, 7, 97))));
        assertThat("top period", d.bands().get(Edge.TOP).period(), is(equalTo(1)));
        assertThat("left period", d.bands().get(Edge.LEFT).period(), is(equalTo(1)));
        assertThat("right period", d.bands().get(Edge.RIGHT).period(), is(equalTo(1)));
        assertThat("bottom period - the slot lattice", d.bands().get(Edge.BOTTOM).period(), is(equalTo(18)));
    }

    @Test
    @DisplayName("the tooltip frame's side bands carry a ramp rather than a period")
    void theTooltipFrameSideBandsCarryARamp() {
        ChromeDecomposition d = ChromeSlicer.decompose(resolve("minecraft:gui/sprites/tooltip/frame"), Optional.empty(), true);

        assertThat("derived depths", List.of(d.left(), d.top(), d.right(), d.bottom()),
            is(equalTo(List.of(10, 10, 10, 10))));
        assertThat("left period", d.bands().get(Edge.LEFT).period(), is(equalTo(0)));
        assertThat("right period", d.bands().get(Edge.RIGHT).period(), is(equalTo(0)));
    }

    @Test
    @DisplayName("a period-zero band resamples its ramp rather than restarting it")
    void aPeriodZeroBandResamplesItsRamp() {
        PixelBuffer art = resolve("minecraft:gui/sprites/tooltip/frame");
        ChromeDecomposition d = ChromeSlicer.decompose(art, Optional.empty(), true);
        PixelBuffer grown = ChromeSlicer.assemble(d, art, 200, 200);

        // Down the left band's outermost drawn column, the ramp only ever descends in the source.
        // Tiling it would restart it; resampling keeps it monotone, and an ascent is the symptom.
        int ascents = 0;
        int previous = -1;
        for (int y = d.top(); y < 200 - d.bottom(); y++) {
            int blue = grown.getPixel(1, y) & 0xFF;
            if (previous >= 0 && blue > previous) ascents++;
            previous = blue;
        }

        assertThat("ascending steps in a channel that only descends", ascents, is(equalTo(0)));
    }

    /**
     * The five container backgrounds carry one frame at five different heights, so each is the
     * other's ground truth at a size it was not authored at. Only the frame is compared - the
     * interiors hold different slot layouts and are supposed to differ.
     */
    private static final List<String> CONTAINERS = List.of(
        "minecraft:gui/container/generic_54", "minecraft:gui/container/shulker_box",
        "minecraft:gui/container/hopper", "minecraft:gui/container/dispenser",
        "minecraft:gui/container/crafting_table");

    /** The drawn height of each container in {@link #CONTAINERS}; all five are 176 wide. */
    private static final List<Integer> HEIGHTS = List.of(222, 166, 133, 166, 166);

    /** The frame's depth in pixels - the ring outside which no container's art is shared. */
    private static final int FRAME = 4;

    private static int differingInFrame(PixelBuffer a, PixelBuffer b) {
        int differing = 0;
        for (int y = 0; y < a.height(); y++)
            for (int x = 0; x < a.width(); x++) {
                boolean inFrame = x < FRAME || x >= a.width() - FRAME || y < FRAME || y >= a.height() - FRAME;
                if (inFrame && a.getPixel(x, y) != b.getPixel(x, y)) differing++;
            }

        return differing;
    }

    @Test
    @DisplayName("a container resized to another container's height reproduces that container's frame")
    void aResizedContainerReproducesAnothersFrame() {
        for (int from = 0; from < CONTAINERS.size(); from++) {
            PixelBuffer source = crop(resolve(CONTAINERS.get(from)), 176, HEIGHTS.get(from));
            ChromeDecomposition d = ChromeSlicer.decompose(source, Optional.empty(), true);

            for (int to = 0; to < CONTAINERS.size(); to++) {
                if (HEIGHTS.get(to).equals(HEIGHTS.get(from))) continue;

                PixelBuffer target = crop(resolve(CONTAINERS.get(to)), 176, HEIGHTS.get(to));
                PixelBuffer grown = ChromeSlicer.assemble(d, source, 176, HEIGHTS.get(to));

                assertThat(CONTAINERS.get(from) + " grown to " + CONTAINERS.get(to) + "'s height",
                    differingInFrame(grown, target), is(equalTo(0)));
            }
        }
    }

    @Test
    @DisplayName("the slot lattice a resized chest grows lands on the pitch vanilla drew it at")
    void theGrownSlotLatticeKeepsItsPitch() {
        PixelBuffer source = crop(resolve("minecraft:gui/container/generic_54"), 176, 222);
        ChromeDecomposition d = ChromeSlicer.decompose(source, Optional.empty(), true);

        // Six container rows at 222; three more rows is 54 more pixels. The shipped art is the
        // oracle for what those rows look like - every one is a copy of the row above it.
        PixelBuffer grown = ChromeSlicer.assemble(d, source, 176, 222 + 54);

        int differing = 0;
        for (int y = 17; y < 17 + 9 * 18; y++)
            for (int x = 0; x < 176; x++)
                if (grown.getPixel(x, y) != source.getPixel(x, 17 + (y - 17) % 18)) differing++;

        assertThat("nine slot rows that are not the shipped row repeated", differing, is(equalTo(0)));
    }

    @Test
    @DisplayName("a declared border is taken as the depths rather than derived against")
    void aDeclaredBorderIsTakenAsTheDepths() {
        PixelBuffer art = resolve("minecraft:gui/sprites/tooltip/frame");
        ChromeDecomposition.Border declared = new ChromeDecomposition.Border(3, 3, 3, 3);
        ChromeDecomposition d = ChromeSlicer.decompose(art, Optional.of(declared), true);

        assertThat("declared depths win", List.of(d.left(), d.top(), d.right(), d.bottom()),
            is(equalTo(List.of(3, 3, 3, 3))));

        // A border the client's own validation refuses is not a declaration, so it is ignored and
        // the depths come back derived.
        ChromeDecomposition.Border refused = new ChromeDecomposition.Border(art.width(), 3, art.width(), 3);
        assertThat("a border wider than the sprite does not load", refused.loadableAt(art.width(), art.height()), is(false));
        assertThat("so the depths are derived instead",
            ChromeSlicer.decompose(art, Optional.of(refused), true).left(), is(equalTo(10)));
    }

}
