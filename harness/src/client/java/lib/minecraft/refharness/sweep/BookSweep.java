package lib.minecraft.refharness.sweep;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import lib.minecraft.refharness.frame.GuiFrameRenderer;
import lib.minecraft.refharness.mixin.BookViewScreenAccessor;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Book sweep. Draws every page of asset-renderer's book roster through the client's own book screen,
 * the screen a written book is read in, into the shared GUI capture - the book's background, then the
 * page's text as the screen draws it - so each reference is what the client composes for a book of
 * that one page.
 *
 * <p>The roster is the file {@link HarnessConfig#BOOK_ROSTER} names, the tracked one under
 * asset-renderer's {@code src/visual/resources} unless a run names another. Each page is a text
 * component parsed by the client's own component codec against the running registries, so a page the
 * client would refuse refuses the run rather than drawing something else; so does a roster of another
 * format and two subjects that would write one file. A subject naming no page is the book's background
 * alone, the one drawing every page shares, so a mover there is the book's texture and a mover in a
 * page alone is its text.
 *
 * <p>The screen is opened directly and never made the client's screen, and is laid out at the book's
 * own width, which puts the book's left edge at the canvas edge and its top two rows down, where the
 * client puts it. The step it hands the capture is the client's own screen frame, op for op, with one
 * call replaced: a stratum, the background, a stratum, the screen's private text visit with its
 * click-only flag set in place of the screen's state extract, and the deferred elements, which at a
 * pointer off the canvas are none. The visit reads that flag at one place, the jump over the
 * page-number line, and the state extract would draw that line and the screen's widgets. So every
 * line is drawn where the screen draws it, wrapped and capped as the screen wraps and caps it, and no
 * page number, arrow or Done button is: on a one-page book the client hides both arrows itself, and
 * Done sits below the canvas. The book's background alone is the same frame with the text visit left
 * out.
 *
 * <p>No page is obfuscated. The client's obfuscation picks its glyphs off {@code Font.random}, which
 * nothing seeds, so such a page would not reproduce and the whole tree's determinism would go with it.
 */
@Parity(claim = "harness-book-sweep", mode = Mode.DEMOTE, subject = Subject.TEXT)
public final class BookSweep implements Sweep<BookSweep.BookSubject> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /** The roster format this reader reads. */
    private static final int FORMAT = 1;

    /**
     * GUI pixels across the screen the book is laid out on - the book's own width, at which the screen
     * puts the book's left edge at its own.
     */
    private static final int SCREEN_WIDTH = 192;

    /**
     * GUI pixels down the screen the book is laid out on - the two rows the screen leaves above the
     * book, and the book's own height.
     */
    private static final int SCREEN_HEIGHT = 194;

    private final GuiFrameRenderer capture = new GuiFrameRenderer("book");

    /**
     * One page to render.
     *
     * @param key the roster key, the stem of the subject's file
     * @param page the page as the client's text component, empty for the book's background alone
     */
    public record BookSubject(String key, Optional<Component> page) {}

    /**
     * One subject as the roster spells it, its page not yet parsed.
     *
     * @param key the roster key, the stem of the subject's file
     * @param page the page as the roster's JSON, empty for the book's background alone
     */
    record RosterEntry(String key, Optional<JsonElement> page) {}

    /**
     * Answers the directory this sweep writes into.
     *
     * @return {@code books}
     */
    @Override
    public String outputDir() {
        return "books";
    }

    /**
     * Reads the roster and parses every subject's page with the client's own component codec.
     *
     * @param ctx the sweep context
     * @return the subjects, in roster order
     * @throws IllegalStateException if the roster is unnamed, absent, malformed or of another format,
     *     carries a page the client's codec refuses, or names two subjects that would write one file
     */
    @Override
    public List<BookSubject> enumerate(SweepContext ctx) {
        Path file = rosterFile();
        DynamicOps<JsonElement> ops = ctx.level().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        List<BookSubject> subjects = new ArrayList<>();
        for (RosterEntry entry : entries(read(file), file)) {
            Optional<Component> page = entry.page().map(json -> ComponentSerialization.CODEC.parse(ops, json)
                .getOrThrow(message -> new IllegalStateException(String.format(
                    "Book subject '%s' carries a page the client refuses: '%s'", entry.key(), message))));
            subjects.add(new BookSubject(entry.key(), page));
        }

        LOG.info("BookSweep built: {} targets from {}", subjects.size(), file);
        return subjects;
    }

    /** {@inheritDoc} */
    @Override
    public RefKey key(BookSubject subject) {
        return keyOf(subject.key());
    }

    /**
     * Answers the subjects a roster lists, in roster order, each page as the roster spells it.
     *
     * @param roster the roster
     * @param file the roster's path, for the refusals
     * @return the roster's entries
     * @throws IllegalStateException if the roster is of another format or lists no subjects, a subject is
     *     not an object or carries no key, or two subjects would write one file
     */
    static List<RosterEntry> entries(JsonObject roster, Path file) {
        JsonElement format = roster.get("format");
        if (format == null || !format.isJsonPrimitive() || !String.valueOf(FORMAT).equals(format.getAsString())) {
            throw new IllegalStateException(String.format(
                "The book roster is format '%s', and this harness reads format '%s'", format, FORMAT));
        }

        List<RosterEntry> entries = new ArrayList<>();
        Set<Path> files = new HashSet<>();
        for (JsonElement element : subjects(roster, file)) {
            JsonObject entry = asObject(element, "a subject");
            String key = member(entry, "key", "a subject").getAsString();
            Path name = keyOf(key).resolve(Path.of(""));
            if (!files.add(name)) {
                throw new IllegalStateException(String.format(
                    "Book roster subject '%s' writes '%s', which another subject of the roster writes too", key, name));
            }
            entries.add(new RosterEntry(key, Optional.ofNullable(entry.get("page"))));
        }
        return entries;
    }

    /**
     * Answers the key a subject writes under.
     *
     * @param key the roster key
     * @return the bare-named key
     */
    private static RefKey keyOf(String key) {
        return RefKey.named(key);
    }

    /**
     * Sizes the canvas to the screen the book is laid out on, at the GUI scale - the book and the two
     * rows the client leaves above it, one canvas for every subject.
     *
     * @param ctx the sweep context
     * @param subject the subject
     * @return the canvas
     */
    @Override
    public Canvas canvas(SweepContext ctx, BookSubject subject) {
        int scale = GuiFrameRenderer.GUI_SCALE;
        return new Canvas(SCREEN_WIDTH * scale, SCREEN_HEIGHT * scale, Optional.empty());
    }

    /**
     * Draws one subject through the client's own book screen, in the client's own screen frame: a
     * stratum, the book's background, a stratum, for a page the screen's own text visit with the
     * page-number line left out, and the deferred elements.
     *
     * @param ctx the sweep context
     * @param subject the subject
     * @param canvas the canvas from {@link #canvas}
     * @param out where the PNG is written
     * @return whether a PNG was written
     * @throws IOException if the PNG's directory cannot be created
     */
    @Override
    public boolean render(SweepContext ctx, BookSubject subject, Canvas canvas, Path out) throws IOException {
        BookViewScreen screen = new BookViewScreen(subject.page()
            .map(page -> new BookViewScreen.BookAccess(List.of(page)))
            .orElse(BookViewScreen.EMPTY_ACCESS));

        return capture.render(ctx.client(), canvas, out, extractor -> {
            // At the book's own width the screen puts the book's left edge at x 0, and its top two rows down.
            screen.init(SCREEN_WIDTH, SCREEN_HEIGHT);

            // The client's screen frame, op for op, with the screen's text visit in place of its state
            // extract: the click-only flag leaves out the page-number line, and the state extract would
            // draw it and the widgets. The background alone is the frame with the visit left out.
            extractor.nextStratum();
            screen.extractBackground(extractor, -1, -1, 0.0f);
            extractor.nextStratum();
            if (subject.page().isPresent()) {
                ((BookViewScreenAccessor) screen).refharness$visitText(
                    extractor.textRenderer(GuiGraphicsExtractor.HoveredTextEffects.TOOLTIP_AND_CURSOR), true);
            }
            extractor.extractDeferredElements(-1, -1, 0.0f);
        });
    }

    /**
     * Answers the subjects the roster lists.
     *
     * @param roster the roster
     * @param file the roster's path, for the refusal
     * @return the listed subjects
     * @throws IllegalStateException if the roster lists none
     */
    private static JsonArray subjects(JsonObject roster, Path file) {
        JsonElement listed = roster.get("subjects");
        if (listed == null || !listed.isJsonArray())
            throw new IllegalStateException(String.format("The book roster at '%s' lists no subjects", file));
        return listed.getAsJsonArray();
    }

    /**
     * Answers the roster's path, refusing a launch that names none.
     *
     * @return the path {@code refharness.bookRoster} names
     * @throws IllegalStateException if the property is unset
     */
    private static Path rosterFile() {
        if (HarnessConfig.BOOK_ROSTER.isEmpty()) {
            throw new IllegalStateException(
                "No book roster named - refharness.bookRoster is unset, and the harness build sets it to "
                    + "asset-renderer's src/visual/resources/lib/minecraft/renderer/sweep/book-roster.json");
        }
        return Path.of(HarnessConfig.BOOK_ROSTER);
    }

    /**
     * Reads the roster as one object, refusing one that is absent or does not parse.
     *
     * @param file the roster's path
     * @return the roster
     * @throws IllegalStateException if the file cannot be read, does not parse, or is not an object
     */
    private static JsonObject read(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(String.format(
                "No book roster at '%s' - refharness.bookRoster names it", file.toAbsolutePath().normalize()), ex);
        }
        try {
            return asObject(JsonParser.parseString(text), "the roster");
        } catch (JsonParseException ex) {
            throw new IllegalStateException(String.format(
                "The book roster at '%s' does not parse", file.toAbsolutePath().normalize()), ex);
        }
    }

    /**
     * Answers one member of an object, refusing an object that lacks it.
     *
     * @param object the object
     * @param member the member's name
     * @param where what the object is, for the refusal
     * @return the member's value
     * @throws IllegalStateException if the member is absent or null
     */
    private static JsonElement member(JsonObject object, String member, String where) {
        JsonElement value = object.get(member);
        if (value == null || value.isJsonNull())
            throw new IllegalStateException(String.format("Book roster '%s' carries no member '%s'", where, member));
        return value;
    }

    /**
     * Reads one element as an object, refusing one that is not.
     *
     * @param element the element
     * @param where what the element is, for the refusal
     * @return the element as an object
     * @throws IllegalStateException if the element is absent or not an object
     */
    private static JsonObject asObject(JsonElement element, String where) {
        if (element == null || !element.isJsonObject())
            throw new IllegalStateException(String.format("Book roster '%s' is not an object", where));
        return element.getAsJsonObject();
    }
}
