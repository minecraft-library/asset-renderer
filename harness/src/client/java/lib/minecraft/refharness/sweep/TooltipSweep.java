package lib.minecraft.refharness.sweep;

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
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import org.joml.Vector2i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Tooltip sweep. Draws every subject of asset-renderer's tooltip roster through the client's own
 * {@code GuiGraphicsExtractor#tooltip} call, and the same subject's box alone, into the shared GUI
 * capture, so the reference is the tooltip as the client composes it - the box's two sprites and the
 * text over them.
 *
 * <p>The roster is the file {@link HarnessConfig#TOOLTIP_ROSTER} names, the tracked one under
 * asset-renderer's {@code src/visual/resources} unless a run names another. Each line is a text
 * component as an item tooltip carries one, parsed by the client's own component codec against the
 * running registries, so a line the client would refuse refuses the run rather than drawing
 * something else; so does a roster of another format, two subjects that would write one file, and a
 * client whose built-in high-contrast or programmer-art pack is selected, the first of which replaces
 * both tooltip sprites.
 *
 * <p>The call is the direct one and never the item route, which adds attribute and advanced lines
 * the roster did not ask for. It is made at a fixed content origin with a positioner that places the
 * box there: the client's default positioner measures against {@code guiWidth()}, which reads the
 * hidden window rather than the capture's swapped extent, so it would flip or clamp a wide line
 * against a window nobody sees. The canvas is the whole sprite rect the box is blitted over - the
 * client's own measurement of the lines, the loop the tooltip call runs, plus {@link #MARGIN} on every
 * side - so nothing the client draws for a tooltip falls off it.
 *
 * <p>Every subject is drawn twice, the full tooltip and then its <b>box alone</b> through
 * {@code TooltipRenderUtil.extractTooltipBackground} at the same measurement, so a pair costs one
 * target resize and a mover is attributable without a mask: one in the box-alone twin is the box,
 * one in the full tooltip alone is the text.
 *
 * <p>No subject is obfuscated. The client's obfuscation picks its glyphs off {@code Font.random},
 * which nothing seeds, so such a line would not reproduce and the whole tree's determinism would go
 * with it.
 */
@Parity(claim = "harness-tooltip-sweep", mode = Mode.DEMOTE, subject = Subject.TEXT)
public final class TooltipSweep implements Sweep<TooltipSweep.TooltipSubject> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /** The roster format this reader reads. */
    private static final int FORMAT = 1;

    /**
     * GUI pixels between the content origin and the canvas edge on every side - the margin the
     * client's tooltip box draws its sprites over, which is what puts the box's sprite rect at the
     * canvas origin.
     */
    private static final int MARGIN = 12;

    /** The built-in packs that redraw a tooltip, and that a reference run therefore never selects. */
    private static final Set<String> REDRAWING_PACKS = Set.of("high_contrast", "programmer_art");

    /** Places the box at the point it is asked for, whatever the window measures. */
    private static final ClientTooltipPositioner AT_THE_ORIGIN =
        (screenWidth, screenHeight, x, y, width, height) -> new Vector2i(x, y);

    private final GuiFrameRenderer capture = new GuiFrameRenderer("tooltip");

    /**
     * One tooltip to render.
     *
     * @param key the roster key, the stem of the subject's file
     * @param style the tooltip style the subject names, empty for vanilla's own box
     * @param lines the subject's lines as the client's tooltip components
     * @param width the content width the client's font measures, in GUI pixels
     * @param height the content height the client's font measures, in GUI pixels
     * @param chrome whether this is the box-alone twin rather than the full tooltip
     */
    public record TooltipSubject(String key, Optional<Identifier> style, List<ClientTooltipComponent> lines,
                                 int width, int height, boolean chrome) {}

    @Override
    public String outputDir() {
        return "tooltips";
    }

    /**
     * Reads the roster and measures every subject with the client's font, each roster subject
     * yielding its full tooltip and then its box-alone twin.
     *
     * @param ctx the sweep context
     * @return the subjects, in roster order
     * @throws IllegalStateException if a redrawing pack is selected, or the roster is unnamed, absent,
     *     malformed, of another format, carries a line the client's codec refuses, or names two
     *     subjects that would write one file
     */
    @Override
    public List<TooltipSubject> enumerate(SweepContext ctx) {
        Collection<String> selected = ctx.client().getResourcePackRepository().getSelectedIds();
        List<String> redrawing = selected.stream().filter(REDRAWING_PACKS::contains).toList();
        if (!redrawing.isEmpty()) {
            throw new IllegalStateException(String.format(
                "The tooltip references are drawn over vanilla's own sprites, and this client selected '%s' of '%s'",
                redrawing, selected));
        }

        Path file = rosterFile();
        JsonObject roster = read(file);
        JsonElement format = roster.get("format");
        if (format == null || !format.isJsonPrimitive() || !String.valueOf(FORMAT).equals(format.getAsString())) {
            throw new IllegalStateException(String.format(
                "The tooltip roster is format '%s', and this harness reads format '%s'", format, FORMAT));
        }
        JsonElement listed = roster.get("subjects");
        if (listed == null || !listed.isJsonArray())
            throw new IllegalStateException(String.format("The tooltip roster at '%s' lists no subjects", file));

        Font font = ctx.client().font;
        DynamicOps<JsonElement> ops = ctx.level().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        List<TooltipSubject> subjects = new ArrayList<>();
        Set<String> files = new HashSet<>();
        for (JsonElement element : listed.getAsJsonArray()) {
            JsonObject entry = asObject(element, "a subject");
            String key = member(entry, "key", "a subject").getAsString();
            Optional<Identifier> style = entry.has("style")
                ? Optional.of(Identifier.read(entry.get("style").getAsString()).getOrThrow(message ->
                    new IllegalStateException(String.format("Tooltip subject '%s' names no style: '%s'", key, message))))
                : Optional.empty();

            List<ClientTooltipComponent> lines = new ArrayList<>();
            for (JsonElement line : member(entry, "lines", key).getAsJsonArray()) {
                Component component = ComponentSerialization.CODEC.parse(ops, line).getOrThrow(message ->
                    new IllegalStateException(String.format("Tooltip subject '%s' carries a line the client refuses: '%s'", key, message)));
                lines.add(ClientTooltipComponent.create(component.getVisualOrderText()));
            }

            // The tooltip call's own measuring loop: the widest line, and every line's height less the
            // two pixels a one-line tooltip gives back.
            int width = 0;
            int height = lines.size() == 1 ? -2 : 0;
            for (ClientTooltipComponent line : lines) {
                width = Math.max(width, line.getWidth(font));
                height += line.getHeight(font);
            }

            for (boolean chrome : new boolean[] { false, true }) {
                TooltipSubject subject = new TooltipSubject(key, style, List.copyOf(lines), width, height, chrome);
                String name = key(subject).fileName();
                if (!files.add(name)) {
                    throw new IllegalStateException(String.format(
                        "Tooltip subject '%s' writes '%s', which another subject of the roster writes too", key, name));
                }
                subjects.add(subject);
            }
        }

        LOG.info("TooltipSweep built: {} targets from {}", subjects.size(), file);
        return subjects;
    }

    /**
     * Refuses a client whose options draw a tooltip other than vanilla's default.
     *
     * <p>Asserted rather than set: a font option takes effect through a resource reload, and the run
     * directory every run shares would keep whatever this set.
     *
     * @param ctx the sweep context
     * @param subjects the enumerated work list
     * @throws IllegalStateException if high contrast, the forced Unicode font or the Japanese glyph
     *     variants are on, or the language is not {@code en_us}
     */
    @Override
    public void prepare(SweepContext ctx, List<TooltipSubject> subjects) {
        Options options = ctx.client().options;
        boolean highContrast = options.highContrast().get();
        boolean unicode = options.forceUnicodeFont().get();
        boolean japanese = options.japaneseGlyphVariants().get();
        if (highContrast || unicode || japanese || !"en_us".equals(options.languageCode)) {
            throw new IllegalStateException(String.format(
                "The tooltip references are drawn at high contrast, forced Unicode font and Japanese glyph variants "
                    + "off in 'en_us', and this client runs them at '%s', '%s' and '%s' in '%s'",
                highContrast, unicode, japanese, options.languageCode));
        }
    }

    /**
     * Answers the roster key, {@code _chrome} for the box-alone twin, and a {@code style} token for a
     * subject naming a tooltip style, spelled with the {@code __} separator an identifier's key takes.
     *
     * @param subject the subject
     * @return the key its file is named by
     */
    @Override
    public RefKey key(TooltipSubject subject) {
        RefKey named = RefKey.named(subject.key());
        RefKey kind = subject.chrome() ? named.with("chrome") : named;
        return subject.style()
            .map(style -> kind.token("style", style.getNamespace() + "__" + style.getPath()))
            .orElse(kind);
    }

    /**
     * Sizes the canvas to the tooltip's whole sprite rect at the GUI scale - the measured content and
     * {@link #MARGIN} on every side.
     *
     * @param ctx the sweep context
     * @param subject the subject
     * @return the canvas
     */
    @Override
    public Canvas canvas(SweepContext ctx, TooltipSubject subject) {
        int scale = GuiFrameRenderer.GUI_SCALE;
        return new Canvas((subject.width() + 2 * MARGIN) * scale, (subject.height() + 2 * MARGIN) * scale,
            Optional.empty());
    }

    @Override
    public boolean render(SweepContext ctx, TooltipSubject subject, Canvas canvas, Path out) throws IOException {
        Minecraft client = ctx.client();
        Identifier style = subject.style().orElse(null);

        if (subject.chrome())
            return capture.render(client, canvas, out, extractor -> TooltipRenderUtil.extractTooltipBackground(
                extractor, MARGIN, MARGIN, subject.width(), subject.height(), style));

        return capture.render(client, canvas, out, extractor ->
            extractor.tooltip(client.font, subject.lines(), MARGIN, MARGIN, AT_THE_ORIGIN, style));
    }

    /**
     * A tooltip key is not a registry id, so the allowlist has nothing to match it against and
     * applying one would render nothing at all.
     */
    @Override
    public boolean honoursTargetFilter() {
        return false;
    }

    /**
     * Answers the roster's path, refusing a launch that names none.
     *
     * @return the path {@code refharness.tooltipRoster} names
     * @throws IllegalStateException if the property is unset
     */
    private static Path rosterFile() {
        if (HarnessConfig.TOOLTIP_ROSTER.isEmpty()) {
            throw new IllegalStateException(
                "No tooltip roster named - refharness.tooltipRoster is unset, and the harness build sets it to "
                    + "asset-renderer's src/visual/resources/lib/minecraft/renderer/sweep/tooltip-roster.json");
        }
        return Path.of(HarnessConfig.TOOLTIP_ROSTER);
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
                "No tooltip roster at '%s' - refharness.tooltipRoster names it", file.toAbsolutePath().normalize()), ex);
        }
        try {
            return asObject(JsonParser.parseString(text), "the roster");
        } catch (JsonParseException ex) {
            throw new IllegalStateException(String.format(
                "The tooltip roster at '%s' does not parse", file.toAbsolutePath().normalize()), ex);
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
            throw new IllegalStateException(String.format("Tooltip roster '%s' carries no member '%s'", where, member));
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
            throw new IllegalStateException(String.format("Tooltip roster '%s' is not an object", where));
        return element.getAsJsonObject();
    }
}
