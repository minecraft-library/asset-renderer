package lib.minecraft.renderer.engine.compose;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Pins {@link Window.Sliced} - the arm that paints a panel by slicing art rather than by drawing
 * rules - against the same shipped containers the drawn arm answers for.
 * <p>
 * The two arms are held to each other where they overlap: vanilla's own container background sliced
 * and re-assembled at its authored size has to be what {@link Window.Theme#VANILLA} paints from
 * rules, because they are two descriptions of one image.
 */
@Tag("slow")
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("Window.Sliced paints a panel from art")
class WindowSlicedTest {

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

    private static int differing(PixelBuffer a, PixelBuffer b) {
        int count = 0;
        for (int y = 0; y < a.height(); y++)
            for (int x = 0; x < a.width(); x++)
                if (a.getPixel(x, y) != b.getPixel(x, y)) count++;

        return count;
    }

    @Test
    @DisplayName("a sliced shulker box repaints its own art")
    void aSlicedShulkerBoxRepaintsItsOwnArt() {
        PixelBuffer art = crop(resolve("minecraft:gui/container/shulker_box"), 176, 166);
        Window window = Window.Sliced.of(art, Optional.empty(), Optional.empty(), Optional.empty());

        PixelBuffer painted = PixelBuffer.create(176, 166);
        window.paintPanel(painted, Window.Box.of(176, 166));

        assertThat(differing(art, painted), is(equalTo(0)));
    }

    @Test
    @DisplayName("the sliced arm and the drawn arm agree on the frame vanilla ships")
    void theTwoArmsAgreeOnTheFrame() {
        PixelBuffer art = crop(resolve("minecraft:gui/container/shulker_box"), 176, 166);
        Window sliced = Window.Sliced.of(art, Optional.empty(), Optional.empty(), Optional.empty());

        PixelBuffer fromArt = PixelBuffer.create(176, 166);
        sliced.paintPanel(fromArt, Window.Box.of(176, 166));

        PixelBuffer fromRules = PixelBuffer.create(176, 166);
        Window.Theme.VANILLA.paintPanel(fromRules, Window.Box.of(176, 166));

        // Only the frame ring - the drawn arm paints a plain interior where the art carries slots.
        int differingInFrame = 0;
        for (int y = 0; y < 166; y++)
            for (int x = 0; x < 176; x++) {
                boolean frame = x < 4 || x >= 172 || y < 4 || y >= 162;
                if (frame && fromArt.getPixel(x, y) != fromRules.getPixel(x, y)) differingInFrame++;
            }

        assertThat(differingInFrame, is(equalTo(0)));
    }

    @Test
    @DisplayName("a cell sliced from the shipped slot sprite is what the drawn arm draws")
    void aSlicedCellMatchesTheDrawnCell() {
        PixelBuffer slot = resolve("minecraft:gui/sprites/container/slot");
        PixelBuffer panel = crop(resolve("minecraft:gui/container/shulker_box"), 176, 166);
        Window window = Window.Sliced.of(panel, Optional.empty(), Optional.of(slot), Optional.empty());

        PixelBuffer fromArt = PixelBuffer.create(18, 18);
        window.paintCell(fromArt, Window.Box.of(18, 18));

        PixelBuffer fromRules = PixelBuffer.create(18, 18);
        Window.Theme.VANILLA.paintCell(fromRules, Window.Box.of(18, 18));

        assertThat(differing(fromArt, fromRules), is(equalTo(0)));
    }

    @Test
    @DisplayName("a panel with no cell art paints no cell")
    void aPanelWithNoCellArtPaintsNoCell() {
        PixelBuffer art = crop(resolve("minecraft:gui/container/shulker_box"), 176, 166);
        Window window = Window.Sliced.of(art, Optional.empty(), Optional.empty(), Optional.empty());

        PixelBuffer painted = PixelBuffer.create(18, 18);
        window.paintCell(painted, Window.Box.of(18, 18));

        int written = 0;
        for (int y = 0; y < 18; y++)
            for (int x = 0; x < 18; x++)
                if (painted.getPixel(x, y) != 0) written++;

        assertThat(written, is(equalTo(0)));
    }

    @Test
    @DisplayName("the sliced arm replicates at scale rather than resampling")
    void theSlicedArmReplicatesAtScale() {
        PixelBuffer art = crop(resolve("minecraft:gui/container/hopper"), 176, 133);
        Window window = Window.Sliced.of(art, Optional.empty(), Optional.empty(), Optional.empty());

        PixelBuffer one = PixelBuffer.create(176, 133);
        window.paintPanel(one, Window.Box.of(176, 133));

        PixelBuffer two = PixelBuffer.create(352, 266);
        window.paintPanel(two, new Window.Box(0, 0, 176, 133, 2));

        int differing = 0;
        for (int y = 0; y < 266; y++)
            for (int x = 0; x < 352; x++)
                if (two.getPixel(x, y) != one.getPixel(x / 2, y / 2)) differing++;

        assertThat(differing, is(equalTo(0)));
    }

    @Test
    @DisplayName("the sliced arm's floor is where its own bands meet")
    void theSlicedArmFloorIsWhereItsBandsMeet() {
        PixelBuffer art = crop(resolve("minecraft:gui/container/generic_54"), 176, 222);
        Window.Sliced window = Window.Sliced.of(art, Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(window.minimum().width(), is(equalTo(window.panel().left() + window.panel().right())));
        assertThat(window.minimum().height(), is(equalTo(window.panel().top() + window.panel().bottom())));
    }

}
