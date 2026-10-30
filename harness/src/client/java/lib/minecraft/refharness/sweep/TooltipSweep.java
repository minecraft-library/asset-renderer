package lib.minecraft.refharness.sweep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import lib.minecraft.refharness.frame.GuiFrameRenderer;
import lib.minecraft.refharness.mixin.TooltipRenderUtilAccessor;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;
import org.joml.Vector2i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tooltip sweep. Draws every tooltip subject of asset-renderer's tooltip roster through the client's
 * own {@code GuiGraphicsExtractor#tooltip} call, and the same subject's box alone, and every plain line
 * of the roster through the text draw of the client's hotbar-name call, into the shared GUI capture, so
 * each reference is what the client composes - a tooltip's two box sprites and the text over them, or a
 * plain line's text and its shadow alone.
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
 * <p>Every tooltip subject is drawn twice, the full tooltip and then its <b>box alone</b> through
 * {@code TooltipRenderUtil.extractTooltipBackground} at the same measurement, so a pair costs one
 * target resize and a mover is attributable without a mask: one in the box-alone twin is the box,
 * one in the full tooltip alone is the text.
 *
 * <p><b>The vanilla roster's plain lines are drawn as the hotbar draws an item's name.</b> Each line of
 * the roster's plain section is drawn by the text draw of the client's hotbar-name call into
 * {@code plain/} - the line's text and its shadow on nothing, opaque white where it names no colour -
 * at the same fixed origin, on a canvas of the client's width of the line and the font's line height
 * with {@link #MARGIN} on every side. The call fills a backdrop first only where the text background is
 * not for chat only, which it is by default, so the draw is that text alone. A plain line has no box,
 * so no box-alone twin, and a pack run draws its section alone.
 *
 * <p><b>A pack run draws the roster's section for its packs.</b> Constructed with the packs a run
 * selects, the sweep reads the roster's {@code packs} section keyed by their file names and draws it
 * into {@code pack-tooltips/<label>/}, beside the reference tree its run hands it as the output root.
 * A pack subject names a vanilla subject by key and draws that subject's lines under the packs'
 * sprites, in one of their tooltip styles where it names one, so a pack drawing and the vanilla
 * drawing of its key carry one text. Before the first drawing it writes {@code stack.json} beside
 * them: the packs the client selected and, for every tooltip sprite the section reaches, the id the
 * client's own tooltip draws, the stack of packs supplying its file and the scaling of the sprite the
 * client stitched - what asset-renderer holds its own chrome resolution to before it compares a pixel.
 * A requested pack the client did not select, a roster with no section for the packs and a pack
 * subject naming no unstyled vanilla subject each refuse the run.
 *
 * <p>No subject is obfuscated. The client's obfuscation picks its glyphs off {@code Font.random},
 * which nothing seeds, so such a line would not reproduce and the whole tree's determinism would go
 * with it.
 */
@Parity(claim = "harness-tooltip-sweep", mode = Mode.DEMOTE, subject = Subject.TEXT)
public final class TooltipSweep implements Sweep<TooltipSweep.Drawing> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /** The roster format this reader reads. */
    private static final int FORMAT = 1;

    /**
     * GUI pixels between the content origin and the canvas edge on every side - the margin the
     * client's tooltip box draws its sprites over, which is what puts the box's sprite rect at the
     * canvas origin, and more than any pixel the hotbar name's text draw lays round a plain line.
     */
    private static final int MARGIN = 12;

    /**
     * The roster's section of plain lines, and the directory below this sweep's own that their drawings
     * are written into.
     */
    private static final String PLAIN = "plain";

    /**
     * The colour the hotbar draws a freshly selected item's name in - opaque white, the alpha its
     * highlight timer clamps to.
     */
    private static final int HOTBAR_NAME_COLOR = ARGB.white(255);

    /** The built-in packs that redraw a tooltip, and that a reference run therefore never selects. */
    private static final Set<String> REDRAWING_PACKS = Set.of("high_contrast", "programmer_art");

    /** Places the box at the point it is asked for, whatever the window measures. */
    private static final ClientTooltipPositioner AT_THE_ORIGIN =
        (screenWidth, screenHeight, x, y, width, height) -> new Vector2i(x, y);

    /** The prefix the client's folder source gives the id of every pack it discovers. */
    private static final String FILE_PREFIX = "file/";

    /** The handshake a pack run writes beside its drawings. */
    private static final String STACK = "stack.json";

    /** The schema version of the handshake. */
    private static final int STACK_FORMAT = 2;

    /** Where the client's GUI atlas reads a sprite's file from - vanilla's {@code atlases/gui.json} source. */
    private static final FileToIdConverter GUI_SPRITE_FILES = new FileToIdConverter("textures/gui/sprites", ".png");

    /** The directory the client's shader manager lists every program and include under. */
    private static final String SHADERS = "shaders";

    /** The id the client gives its built-in vanilla pack. */
    private static final String VANILLA_PACK = "vanilla";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final GuiFrameRenderer capture = new GuiFrameRenderer("tooltip");

    /** The packs a pack run requested, as the client's folder source names them; empty for the vanilla roster. */
    private final List<String> requested;

    /**
     * The packs' file names comma-joined in request order, which keys the roster's section and names
     * the output directory; empty for the vanilla roster.
     */
    private final Optional<String> pack;

    /** Constructs the sweep that draws the vanilla roster into {@code tooltips/}. */
    public TooltipSweep() {
        this.requested = List.of();
        this.pack = Optional.empty();
    }

    /**
     * Constructs the sweep that draws the roster's section for a pack run's packs into
     * {@code pack-tooltips/<label>/}.
     *
     * @param packIds the requested packs as {@link HarnessConfig#packIds()} answers them, whose file
     *     names, comma-joined in request order, are the label that keys the roster's section and
     *     names the output directory
     */
    public TooltipSweep(List<String> packIds) {
        this.requested = List.copyOf(packIds);
        this.pack = Optional.of(packIds.stream().map(id -> id.substring(FILE_PREFIX.length())).collect(Collectors.joining(",")));
    }

    /** One drawing the sweep writes - a roster subject's tooltip or its box alone, or one of its plain lines. */
    public sealed interface Drawing permits TooltipSubject, PlainSubject {

        /** The key the roster lists the drawing's subject under. */
        String key();

    }

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
                                 int width, int height, boolean chrome) implements Drawing {}

    /**
     * One of the roster's plain lines to render.
     *
     * @param key the line's key in the roster's plain section, the stem of its file under {@code plain/}
     * @param line the line as the client's text component
     * @param width the line's width as the client's font measures it, in GUI pixels
     */
    public record PlainSubject(String key, Component line, int width) implements Drawing {}

    /**
     * One subject as the roster lists it, before its lines are parsed.
     *
     * @param key the roster key
     * @param style the tooltip style the subject names, empty for the default box
     * @param lines the subject's lines as the roster spells them
     */
    private record Listed(String key, Optional<Identifier> style, JsonArray lines) {}

    /**
     * Answers the directory this sweep writes into - {@code tooltips} for the vanilla roster, and for a
     * pack run a directory beside the reference tree its run hands it as the output root, named for the
     * run's packs.
     *
     * @return {@code tooltips}, or {@code pack-tooltips/<label>} for a pack run
     */
    @Override
    public String outputDir() {
        return pack.map(label -> "pack-tooltips/" + label).orElse("tooltips");
    }

    /**
     * Reads the roster and measures every subject with the client's font, each tooltip subject
     * yielding its full tooltip and then its box-alone twin, and for the vanilla roster each plain line
     * after them.
     *
     * @param ctx the sweep context
     * @return the drawings, in roster order
     * @throws IllegalStateException if a redrawing pack is selected, a requested pack is not, or the
     *     roster is unnamed, absent, malformed, of another format, has no section for a pack run's
     *     packs, lists a pack subject naming no unstyled subject, carries a line the client's codec
     *     refuses, has no plain section for a vanilla run, carries a plain line the client's codec
     *     refuses, or names two subjects that would write one file
     */
    @Override
    public List<Drawing> enumerate(SweepContext ctx) {
        Collection<String> selected = ctx.client().getResourcePackRepository().getSelectedIds();
        List<String> redrawing = selected.stream().filter(REDRAWING_PACKS::contains).toList();
        if (!redrawing.isEmpty()) {
            throw new IllegalStateException(String.format(
                "The tooltip references are drawn over %s, and this client selected '%s' of '%s'",
                pack.isEmpty() ? "vanilla's own sprites" : "the sprites of '" + requested + "' over vanilla's",
                redrawing, selected));
        }
        List<String> unselected = requested.stream().filter(id -> !selected.contains(id)).toList();
        if (!unselected.isEmpty()) {
            throw new IllegalStateException(String.format(
                "The pack tooltips are drawn over '%s', and this client did not select '%s' of them - it selected '%s'",
                requested, unselected, selected));
        }

        Path file = rosterFile();
        JsonObject roster = read(file);
        JsonElement format = roster.get("format");
        if (format == null || !format.isJsonPrimitive() || !String.valueOf(FORMAT).equals(format.getAsString())) {
            throw new IllegalStateException(String.format(
                "The tooltip roster is format '%s', and this harness reads format '%s'", format, FORMAT));
        }

        Font font = ctx.client().font;
        DynamicOps<JsonElement> ops = ctx.level().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        List<Drawing> drawings = new ArrayList<>();
        for (Listed entry : listed(roster, file)) {
            String key = entry.key();
            List<ClientTooltipComponent> lines = new ArrayList<>();
            for (JsonElement line : entry.lines()) {
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

            for (boolean chrome : new boolean[] { false, true })
                drawings.add(new TooltipSubject(key, entry.style(), List.copyOf(lines), width, height, chrome));
        }
        if (pack.isEmpty()) drawings.addAll(plainSubjects(roster, file, ops, font));

        // The path below the sweep's directory, not the file name alone: a plain line's file sits in
        // plain/, so it and the tooltip of the same key are two files under one name.
        Set<Path> files = new HashSet<>();
        for (Drawing drawing : drawings) {
            Path name = key(drawing).resolve(Path.of(""));
            if (!files.add(name)) {
                throw new IllegalStateException(String.format(
                    "Tooltip roster subject '%s' writes '%s', which another subject of the roster writes too", drawing.key(), name));
            }
        }

        LOG.info("TooltipSweep built: {} targets from {}", drawings.size(), file);
        return drawings;
    }

    /**
     * Refuses a client whose options draw a tooltip other than vanilla's default, then, for a pack
     * run, writes the handshake beside its drawings.
     *
     * <p>Asserted rather than set: a font option takes effect through a resource reload, and the run
     * directory every run shares would keep whatever this set.
     *
     * @param ctx the sweep context
     * @param subjects the enumerated work list
     * @throws IllegalStateException if high contrast, the forced Unicode font or the Japanese glyph
     *     variants are on, or the language is not {@code en_us}
     * @throws UncheckedIOException if a pack run's handshake cannot be written
     */
    @Override
    public void prepare(SweepContext ctx, List<Drawing> subjects) {
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
        if (pack.isPresent()) writeStack(ctx, subjects);
    }

    /**
     * Answers the roster key, {@code _chrome} for the box-alone twin, and a {@code style} token for a
     * subject naming a tooltip style, spelled with the {@code __} separator an identifier's key takes; a
     * plain line's is its key in {@code plain/}.
     *
     * @param drawing the drawing
     * @return the key its file is named by
     */
    @Override
    public RefKey key(Drawing drawing) {
        return switch (drawing) {
            case TooltipSubject subject -> {
                RefKey named = RefKey.named(subject.key());
                RefKey kind = subject.chrome() ? named.with("chrome") : named;
                yield subject.style()
                    .map(style -> kind.token("style", style.getNamespace() + "__" + style.getPath()))
                    .orElse(kind);
            }
            case PlainSubject subject -> RefKey.named(subject.key()).in(PLAIN);
        };
    }

    /**
     * Sizes the canvas to the tooltip's whole sprite rect, or a plain line's width and the font's line
     * height, at the GUI scale, with {@link #MARGIN} on every side.
     *
     * @param ctx the sweep context
     * @param drawing the drawing
     * @return the canvas
     */
    @Override
    public Canvas canvas(SweepContext ctx, Drawing drawing) {
        int scale = GuiFrameRenderer.GUI_SCALE;
        return switch (drawing) {
            case TooltipSubject subject -> new Canvas((subject.width() + 2 * MARGIN) * scale,
                (subject.height() + 2 * MARGIN) * scale, Optional.empty());
            case PlainSubject subject -> new Canvas((subject.width() + 2 * MARGIN) * scale,
                (ctx.client().font.lineHeight + 2 * MARGIN) * scale, Optional.empty());
        };
    }

    @Override
    public boolean render(SweepContext ctx, Drawing drawing, Canvas canvas, Path out) throws IOException {
        Minecraft client = ctx.client();
        return switch (drawing) {
            case TooltipSubject subject when subject.chrome() -> capture.render(client, canvas, out, extractor ->
                TooltipRenderUtil.extractTooltipBackground(extractor, MARGIN, MARGIN, subject.width(), subject.height(),
                    subject.style().orElse(null)));
            case TooltipSubject subject -> capture.render(client, canvas, out, extractor ->
                extractor.tooltip(client.font, subject.lines(), MARGIN, MARGIN, AT_THE_ORIGIN, subject.style().orElse(null)));
            // The text draw of the hotbar's name call, textWithBackdrop: the backdrop it fills first is the
            // text background at the 0 opacity the call passes, clear while that background is for chat
            // only, as it is by default, so the line is all it draws.
            case PlainSubject subject -> capture.render(client, canvas, out, extractor ->
                extractor.text(client.font, subject.line(), MARGIN, MARGIN, HOTBAR_NAME_COLOR, true));
        };
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
     * Lists the subjects this sweep draws - the roster's own for the vanilla roster, and for a pack run
     * its section's, each with the lines of the unstyled vanilla subject its key names.
     *
     * @param roster the roster
     * @param file the roster's path, for the refusals
     * @return the subjects, in roster order
     * @throws IllegalStateException if the roster lists no subjects, has no section for a pack run's
     *     packs, or lists a pack subject naming no unstyled subject
     */
    private List<Listed> listed(JsonObject roster, Path file) {
        List<Listed> vanilla = new ArrayList<>();
        for (JsonElement element : subjects(roster, "The top level", file)) {
            JsonObject entry = asObject(element, "a subject");
            String key = member(entry, "key", "a subject").getAsString();
            vanilla.add(new Listed(key, style(entry, key), member(entry, "lines", key).getAsJsonArray()));
        }
        if (pack.isEmpty()) return vanilla;

        String label = pack.get();
        JsonElement sections = roster.get("packs");
        if (sections == null || !sections.isJsonObject() || !sections.getAsJsonObject().has(label)) {
            throw new IllegalStateException(String.format(
                "The tooltip roster at '%s' has no section for packs '%s' - its sections are '%s'", file, label,
                sections != null && sections.isJsonObject() ? sections.getAsJsonObject().keySet() : List.of()));
        }
        Map<String, JsonArray> unstyled = new HashMap<>();
        for (Listed subject : vanilla)
            if (subject.style().isEmpty()) unstyled.putIfAbsent(subject.key(), subject.lines());

        List<Listed> section = new ArrayList<>();
        JsonObject packSection = asObject(sections.getAsJsonObject().get(label), "the section for " + label);
        for (JsonElement element : subjects(packSection, "The section for " + label, file)) {
            JsonObject entry = asObject(element, "a pack subject");
            String key = member(entry, "key", "a pack subject").getAsString();
            JsonArray lines = unstyled.get(key);
            if (lines == null) {
                throw new IllegalStateException(String.format(
                    "Pack subject '%s' of '%s' names no unstyled subject of the roster", key, label));
            }
            section.add(new Listed(key, style(entry, key), lines));
        }
        return section;
    }

    /**
     * Reads the roster's plain lines, each parsed by the client's own component codec and measured by
     * its font as the hotbar measures an item's name.
     *
     * @param roster the roster
     * @param file the roster's path, for the refusals
     * @param ops the registries' serialization context the lines are parsed against
     * @param font the client's font
     * @return the plain lines, in roster order
     * @throws IllegalStateException if the roster has no plain section, the section lists no subjects,
     *     or a line is one the client's codec refuses
     */
    private static List<PlainSubject> plainSubjects(JsonObject roster, Path file, DynamicOps<JsonElement> ops, Font font) {
        List<PlainSubject> plain = new ArrayList<>();
        for (JsonElement element : subjects(asObject(roster.get(PLAIN), "the plain section"), "The plain section", file)) {
            JsonObject entry = asObject(element, "a plain subject");
            String key = member(entry, "key", "a plain subject").getAsString();
            Component line = ComponentSerialization.CODEC.parse(ops, member(entry, "line", key)).getOrThrow(message ->
                new IllegalStateException(String.format("Plain subject '%s' carries a line the client refuses: '%s'", key, message)));
            plain.add(new PlainSubject(key, line, font.width(line)));
        }
        return plain;
    }

    /**
     * Writes a pack run's handshake beside its drawings: the client's pack selection, and for every
     * tooltip sprite the subjects reach - keyed by the id the client's own tooltip draws, sorted - the
     * file the client's GUI atlas reads it from, the stack of packs supplying that file and the scaling
     * of the sprite the client stitched. Then the file the client compiles its GUI text pipeline's vertex
     * shader from with the stack of packs supplying it, and the stack of every shader program and include
     * a selected pack ships.
     *
     * @param ctx the sweep context
     * @param subjects the enumerated work list
     * @throws UncheckedIOException if a supplied file cannot be read or the handshake cannot be written
     */
    private void writeStack(SweepContext ctx, List<Drawing> subjects) {
        Minecraft client = ctx.client();
        JsonObject root = new JsonObject();
        root.addProperty("format", STACK_FORMAT);
        PackSourceSweep.selection(client, root);

        ResourceManager manager = client.getResourceManager();
        TextureAtlas atlas = client.getAtlasManager().getAtlasOrThrow(AtlasIds.GUI);
        Path target = ctx.outputRoot().resolve(outputDir()).resolve(STACK);
        try {
            Map<String, JsonObject> sources = new TreeMap<>();
            List<Optional<Identifier>> styles = subjects.stream()
                .flatMap(drawing -> drawing instanceof TooltipSubject subject ? Stream.of(subject.style()) : Stream.empty())
                .distinct()
                .toList();
            for (Optional<Identifier> style : styles) {
                Identifier key = style.orElse(null);
                List<Identifier> sprites = List.of(
                    TooltipRenderUtilAccessor.refharness$backgroundSprite(key), TooltipRenderUtilAccessor.refharness$frameSprite(key));
                for (Identifier sprite : sprites) {
                    Identifier spriteFile = GUI_SPRITE_FILES.idToFile(sprite);
                    JsonObject entry = new JsonObject();
                    entry.addProperty("file", spriteFile.toString());
                    entry.add("stack", PackSourceSweep.stack(manager.getResourceStack(spriteFile)));
                    entry.add("scaling", scaling(atlas.getSprite(sprite)));
                    sources.put(sprite.toString(), entry);
                }
            }
            JsonObject chrome = new JsonObject();
            sources.forEach(chrome::add);
            root.add("chrome_sources", chrome);

            // The client's own statement of which program draws its text: the GUI text pipeline's vertex
            // shader id, through the conversion its shader manager reads that id's file by.
            Identifier textShaderFile = ShaderType.VERTEX.idConverter().idToFile(RenderPipelines.GUI_TEXT.getVertexShader());
            JsonObject textShader = new JsonObject();
            textShader.addProperty("file", textShaderFile.toString());
            textShader.add("stack", PackSourceSweep.stack(manager.getResourceStack(textShaderFile)));
            root.add("text_shader", textShader);

            Map<String, JsonArray> shaders = new TreeMap<>();
            for (Map.Entry<Identifier, List<Resource>> listed : manager.listResourceStacks(SHADERS, TooltipSweep::isShaderFile).entrySet()) {
                if (listed.getValue().stream().anyMatch(resource -> !VANILLA_PACK.equals(resource.sourcePackId())))
                    shaders.put(listed.getKey().toString(), PackSourceSweep.stack(listed.getValue()));
            }
            JsonObject shaderSources = new JsonObject();
            shaders.forEach(shaderSources::add);
            root.add("shader_sources", shaderSources);

            Files.createDirectories(target.getParent());
            Files.writeString(target, GSON.toJson(root));
            LOG.info("TooltipSweep: {} chrome sprites and {} pack shader files over {} -> {}", sources.size(), shaders.size(), requested, target);
        } catch (IOException ex) {
            throw new UncheckedIOException(String.format("The pack-tooltip handshake '%s' could not be written", target), ex);
        }
    }

    /**
     * Answers whether a file is one the client's shader manager reads - a vertex or fragment program,
     * or an include.
     *
     * @param id the file
     * @return whether it ends {@code .vsh}, {@code .fsh} or {@code .glsl}
     */
    private static boolean isShaderFile(Identifier id) {
        String path = id.getPath();
        return path.endsWith(".vsh") || path.endsWith(".fsh") || path.endsWith(".glsl");
    }

    /**
     * Answers the scaling the client's GUI blit reads for a stitched sprite, in the client codec's field
     * names for its type. The read is the body of {@code GuiGraphicsExtractor#getSpriteScaling}, so a
     * sprite whose sidecar declares none answers the client's default stretch.
     *
     * @param sprite the stitched sprite
     * @return the scaling
     * @throws IllegalStateException if the scaling is of a type the handshake has no form for
     */
    private static JsonObject scaling(TextureAtlasSprite sprite) {
        GuiSpriteScaling scaling = sprite.contents().getAdditionalMetadata(GuiMetadataSection.TYPE)
            .orElse(GuiMetadataSection.DEFAULT)
            .scaling();
        JsonObject json = new JsonObject();
        json.addProperty("type", scaling.type().getSerializedName());
        switch (scaling) {
            case GuiSpriteScaling.Stretch _ -> { }
            case GuiSpriteScaling.Tile tile -> {
                json.addProperty("width", tile.width());
                json.addProperty("height", tile.height());
            }
            case GuiSpriteScaling.NineSlice nineSlice -> {
                json.addProperty("width", nineSlice.width());
                json.addProperty("height", nineSlice.height());
                JsonObject border = new JsonObject();
                border.addProperty("left", nineSlice.border().left());
                border.addProperty("top", nineSlice.border().top());
                border.addProperty("right", nineSlice.border().right());
                border.addProperty("bottom", nineSlice.border().bottom());
                json.add("border", border);
                json.addProperty("stretch_inner", nineSlice.stretchInner());
            }
            default -> throw new IllegalStateException(String.format(
                "GUI sprite '%s' scales as '%s', which the handshake has no form for",
                sprite.contents().name(), scaling.type().getSerializedName()));
        }
        return json;
    }

    /**
     * Reads the tooltip style an entry names.
     *
     * @param entry the roster entry
     * @param key the entry's key, for the refusal
     * @return the style, empty when the entry names none
     * @throws IllegalStateException if the style is not an identifier
     */
    private static Optional<Identifier> style(JsonObject entry, String key) {
        if (!entry.has("style")) return Optional.empty();
        return Optional.of(Identifier.read(entry.get("style").getAsString()).getOrThrow(message ->
            new IllegalStateException(String.format("Tooltip subject '%s' names no style: '%s'", key, message))));
    }

    /**
     * Answers the subjects an object lists.
     *
     * @param object the roster or one of its sections
     * @param where what the object is in the roster, capitalised to open the refusal
     * @param file the roster's path, for the refusal
     * @return the listed subjects
     * @throws IllegalStateException if the object lists none
     */
    private static JsonArray subjects(JsonObject object, String where, Path file) {
        JsonElement listed = object.get("subjects");
        if (listed == null || !listed.isJsonArray())
            throw new IllegalStateException(String.format("%s of the tooltip roster at '%s' lists no subjects", where, file));
        return listed.getAsJsonArray();
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
