package lib.minecraft.renderer.sweep;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.image.PixelBuffer;
import lib.minecraft.renderer.TextRenderer;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.fixture.TooltipFixtures;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.text.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of {@link BookParitySweep} short of a client: the tracked roster read as one book request a
 * page, the refusal of a roster of another format, and a row's comparison of the renderer's page with the
 * client's drawing taken to its first pixel - equal where the two draw the same pixels, failed with both
 * sizes and the stand-ins kept where they do not.
 *
 * <p>{@link MinecraftFontsExtension} supplies the Minecraft font atlas the book's lines are laid out
 * against; every book texture is served jar-free by {@link TooltipFixtures#stubContext}.
 */
@ExtendWith(MinecraftFontsExtension.class)
@DisplayName("BookParitySweep roster and rows")
class BookParitySweepTest {

    /** Every texel of the synthetic paper. */
    private static final int PAPER = 0xFFC8B48C;

    /** The book texture's id. */
    private static final String TEXTURE = "minecraft:gui/book";

    /** The width of the screen the client lays the book out on, in output pixels - the book's own width. */
    private static final int SCREEN_W = 384;

    /** The height of that screen, in output pixels - the book's, and the two clear rows above it. */
    private static final int SCREEN_H = 388;

    /** The output rows the client leaves clear above the book - its two GUI rows. */
    private static final int ROWS_ABOVE = 4;

    /**
     * Builds the synthetic paper, a 256 x 256 texture every texel of which is {@link #PAPER}.
     *
     * @return the paper
     */
    private static PixelBuffer paper() {
        PixelBuffer paper = PixelBuffer.straight(256, 256);
        paper.fill(PAPER);
        return paper;
    }

    /**
     * Builds the subject naming no page, the book's background alone.
     *
     * @return the subject
     */
    private static BookParitySweep.Subject background() {
        return new BookParitySweep.Subject("background", TextOptions.builder().style(TextOptions.Style.BOOK).build());
    }

    /**
     * Lists the files a row's folder holds.
     *
     * @param folder the row's folder
     * @return the file names, sorted
     * @throws IOException if the folder cannot be listed
     */
    private static List<String> files(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    @DisplayName("every page of the tracked roster is one book request holding its page as one component, "
        + "and the subject naming none the empty page")
    void theTrackedRosterIsEveryPageOnABookRequest() throws IOException {
        JsonObject roster = BookParitySweep.roster();
        List<JsonObject> listed = new ArrayList<>();
        roster.getAsJsonArray("subjects").forEach(element -> listed.add(element.getAsJsonObject()));
        List<BookParitySweep.Subject> subjects = BookParitySweep.subjects(roster);

        assertThat("the subjects' keys, against the roster's in its order",
            subjects.stream().map(BookParitySweep.Subject::key).toList(),
            equalTo(listed.stream().map(entry -> entry.get("key").getAsString()).toList()));
        assertThat("no entry naming no page, which would leave the empty page unread",
            listed.stream().anyMatch(entry -> !entry.has("page")), is(true));
        assertThat("no entry naming a page, which would leave a page unread",
            listed.stream().anyMatch(entry -> entry.has("page")), is(true));
        for (int i = 0; i < listed.size(); i++) {
            JsonObject entry = listed.get(i);
            String key = entry.get("key").getAsString();
            TextOptions options = subjects.get(i).options();
            TextSegment page = entry.has("page") ? TextSegment.fromJson(entry.get("page")) : TextSegment.literal("");

            assertThat(key + ": its style", options.getStyle(), is(TextOptions.Style.BOOK));
            assertThat(key + ": its lines", options.getLines(), is(empty()));
            assertThat(key + ": its chrome", options.getChromeStyle(), is(Optional.empty()));
            assertThat(key + ": its page, the roster's one component", options.getPage(), equalTo(page));
        }
    }

    @Test
    @DisplayName("a roster of another format, of none, or of one that is no single value is refused, naming what it read")
    void aRosterOfAnotherFormatIsRefused() {
        JsonObject roster = JsonParser.parseString("{\"format\": 2, \"subjects\": []}").getAsJsonObject();
        JsonObject unversioned = JsonParser.parseString("{\"subjects\": []}").getAsJsonObject();
        JsonObject listed = JsonParser.parseString("{\"format\": [1], \"subjects\": []}").getAsJsonObject();

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> BookParitySweep.subjects(roster));
        assertThat(refusal.getMessage(), containsString("format '2'"));
        IllegalStateException none = assertThrows(IllegalStateException.class, () -> BookParitySweep.subjects(unversioned));
        assertThat(none.getMessage(), containsString("format 'null'"));
        IllegalStateException list = assertThrows(IllegalStateException.class, () -> BookParitySweep.subjects(listed));
        assertThat(list.getMessage(), containsString("format '[1]'"));
    }

    @Test
    @DisplayName("a page drawing the client's pixels compares equal once the client's drawing is taken to its first pixel")
    void aRowTakesTheClientsDrawingToItsFirstPixel(@TempDir Path dir) throws IOException {
        RendererContext context = TooltipFixtures.stubContext(Map.of(TEXTURE, paper()), Map.of());
        BookParitySweep.Subject subject = background();
        Path references = Files.createDirectories(dir.resolve("references"));
        Path output = dir.resolve("output");
        // The client's drawing is the screen the book is laid out on: the book drawn two GUI rows down,
        // the rows above it clear. The paper is opaque, so the render's held ints are its straight bytes.
        PixelBuffer drawn = new TextRenderer(context).render(subject.options()).image().toPixelBuffer();
        BufferedImage screen = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < drawn.height(); y++) {
            for (int x = 0; x < drawn.width(); x++)
                screen.setRGB(x, y + ROWS_ABOVE, drawn.getPixel(x, y));
        }
        ImageIO.write(screen, "PNG", references.resolve("background.png").toFile());

        BookParitySweep.Row row = BookParitySweep.renderAndCompare(subject, new TextRenderer(context), references, output);

        assertThat("the row's key", row.subject(), equalTo("background"));
        assertThat("the renderer's canvas, the book's quad", List.of(row.javaW(), row.javaH()), contains(384, 384));
        assertThat("the client's drawing to its first pixel", List.of(row.vanillaW(), row.vanillaH()), contains(384, 384));
        assertThat("pixels whose bytes differ", row.rawDifferingPixels(), is(0L));
        assertThat("pixels whose over-white colour differs", row.differingPixels(), is(0L));
        assertThat("the mean delta", row.meanDelta(), is(0.0));
        assertThat("the stand-ins", row.standIns(), is(""));
        assertThat("the row's folder, no diff written where the two agree", files(output.resolve("background")),
            contains(SweepPair.JAVA, SweepPair.VANILLA));
    }

    @Test
    @DisplayName("a page whose canvas disagrees with the client's fails, keeping both sizes and the stand-ins it drew")
    void aRowWhoseCanvasDisagreesFailsKeepingWhatItHas(@TempDir Path dir) throws IOException {
        // No book texture, so the missing texture stands in for it and is laid as the book's one quad.
        RendererContext context = TooltipFixtures.stubContext(Map.of(), Map.of());
        Path references = Files.createDirectories(dir.resolve("references"));
        Path output = dir.resolve("output");
        BufferedImage screen = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 2; y < 7; y++) {
            for (int x = 3; x < 10; x++)
                screen.setRGB(x, y, 0xFF102030);
        }
        ImageIO.write(screen, "PNG", references.resolve("background.png").toFile());

        BookParitySweep.Row row = BookParitySweep.renderAndCompare(background(), new TextRenderer(context), references, output);

        assertThat("the row's delta, the failure marker", row.meanDelta(), is(Double.POSITIVE_INFINITY));
        assertThat("the renderer's canvas", List.of(row.javaW(), row.javaH()), contains(384, 384));
        assertThat("the client's drawing to its first pixel", List.of(row.vanillaW(), row.vanillaH()), contains(7, 5));
        assertThat("the stand-in the render drew", row.standIns(), is("TEXTURE ABSENT " + TEXTURE));
        assertThat("both renders written", files(output.resolve("background")), contains(SweepPair.JAVA, SweepPair.VANILLA));
    }

}
