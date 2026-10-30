package lib.minecraft.renderer.sweep;

import api.simplified.mojang.exception.MojangApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.DiffType;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.TextRenderer;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.pack.TextPalette;
import lib.minecraft.renderer.bake.gui.TooltipChrome;
import lib.minecraft.renderer.call.request.ChromeStyle;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.call.result.Substitution;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.index.SubstitutionCollector;
import lib.minecraft.renderer.content.pack.PackAcquisition;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.ResolvedTexture;
import lib.minecraft.renderer.content.pack.TextPaletteLoader;
import lib.minecraft.renderer.content.read.PackSubtree;
import lib.minecraft.renderer.dump.PackSourceParity;
import lib.minecraft.renderer.engine.frame.ImageLayer;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.exception.ClientException;
import lib.minecraft.renderer.store.ParityJson;
import lib.minecraft.renderer.store.diff.ParityMetrics;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.MinecraftFont;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Per-subject tooltip parity report comparing {@link TextRenderer} against the
 * vanilla-reference-harness ground truth for one configuration: {@code vanilla}, the tracked tooltip
 * roster against the reference tree's {@code tooltips/}, or a pack label the roster's {@code packs}
 * section keys, that section against the harness's pack run beside the tree.
 *
 * <p>The harness draws every subject of the configuration through the client's own tooltip call onto
 * the box's whole sprite rect - the client's measurement of the lines plus {@link #CLIENT_MARGIN_MCPX}
 * on every side. This sweep reads the same roster off its own classpath and renders each subject the
 * way a caller would: lore lines over the chrome the subject names, the subject's tooltip style named,
 * through a renderer bound to the client's assets. A pack subject names a vanilla subject by key and
 * renders that subject's lines, so a pack row and the vanilla row of its key carry one text.
 *
 * <p><b>A pack configuration reads the run's handshake first.</b> The pack run writes
 * {@code stack.json} beside its drawings: the packs the client selected, and for every tooltip sprite
 * its section reaches the pack the client took the sprite's file from, that file's SHA-256 and the
 * scaling of the sprite the client stitched; the file the client compiles its GUI text pipeline's vertex
 * shader from with the stack of packs supplying it; and the stack of every shader program and include a
 * selected pack ships. This side loads the same packs from the same folder in the same order and,
 * before any pixel, resolves the chrome of every style the section names the way the renderer does; a
 * sprite it takes from another pack, with other bytes or another scaling, one no pack supplies to it and
 * one the client does not draw each refuse the configuration, every mismatch named. A palette read from
 * another text shader than the client runs, or a shader file resolved from another pack or with other
 * bytes, refuses it too. A row compared over chrome or text colours the two sides resolved differently
 * would measure the resolution rather than the drawing.
 *
 * <p><b>Each subject is two rows.</b> The full row renders through {@link TextRenderer}, so it measures
 * the renderer whole. The box-alone row, keyed {@code _chrome} as the harness keys its twin, draws the
 * production chrome for the CLIENT's box - the content size read off the reference - through the same
 * calls {@code TextRenderer} makes, so it measures whether the chrome draws that box whatever width the
 * renderer's font would have given. A mover in a box-alone row is the box; one in the full row alone is
 * the text. Each row names the stand-ins its render drew in {@code stand_ins} - the result's for the
 * full row, the box's own for the box-alone row - so a row whose chrome is the missing texture says so.
 *
 * <p><b>The vanilla configuration measures the code-drawn box as well.</b> Each unstyled subject is
 * drawn again over {@link ChromeStyle#PROCEDURAL}, its two rows keyed {@code ~chrome=procedural} after
 * the key and box-alone qualifier its sprite rows carry, and read against the same two drawings,
 * because the client draws one tooltip box whichever chrome the renderer is asked for. A styled subject
 * has no such row - the code-drawn box draws no tooltip style, so the client's drawing of the style is
 * not its twin - and a pack configuration has none, because the code-drawn box reads nothing from a
 * pack.
 *
 * <p><b>Both sides end at their first pixel.</b> The reference is the whole sprite rect, so the sweep
 * takes it to the bounds of every pixel the client drew; the renderer's canvas ends at the first pixel
 * its chrome draws. The two must be equal, and a size that disagrees is the finding rather than
 * something to align away, so its row fails.
 *
 * <p><b>The byte gate.</b> Both sides are read as held - the reference through {@link ImageIO}, which
 * keeps the stored framebuffer bytes, and the render through its own pixel buffer whatever the image's
 * alpha mark - so {@code raw_differing_pixels} is a byte comparison and the over-white columns are what
 * a person reads beside it.
 *
 * <p>Output: one folder per row under {@code cache/visual/tooltip-parity-vanilla/}, or
 * {@code cache/visual/tooltip-<label>-parity-vanilla/} for a pack configuration, holding
 * {@code vanilla.png} (the reference taken to its first pixel), {@code java.png}, {@code diff.png} and
 * {@code diff_panel.png}, plus a top-level {@code parity-report.tsv}.
 *
 * <p>Usage: {@code TooltipParitySweep <vanilla | pack label> [tooltipId]} - {@code ./gradlew
 * tooltipParityVanilla} after {@code renderVanillaTooltipReferences}, or {@code ./gradlew
 * tooltipHypixelSkyblockParityVanilla} after {@code renderVanillaPackTooltipsHypixelSkyblock}, either
 * taking {@code -PtooltipId=one_line} for the subjects one roster key names.
 */
@UtilityClass
public final class TooltipParitySweep {

    /** The configuration that renders the roster's own subjects against the reference tree. */
    private static final @NotNull String VANILLA = "vanilla";

    /** The handshake the harness's pack run writes beside its drawings. */
    private static final @NotNull String HANDSHAKE = "stack.json";

    /** The handshake format this reader reads. */
    private static final int HANDSHAKE_FORMAT = 2;

    /** The client's id for its built-in vanilla pack. */
    private static final @NotNull String VANILLA_PACK = "vanilla";

    /** The roster the harness draws the references from, a resource beside this class. */
    private static final @NotNull String ROSTER = "tooltip-roster.json";

    /**
     * Minecraft pixels between the content origin and the reference's edge on every side - the margin
     * the client's tooltip box blits its sprites over, which is the reference's own format.
     */
    private static final int CLIENT_MARGIN_MCPX = 12;

    /**
     * One roster subject, named exactly as the harness names it.
     *
     * @param key the roster key, which each of the subject's reference stems begins with, ahead of
     *     the box-alone qualifier and the style token
     * @param style the tooltip style the subject names, empty for the default box
     * @param options the options the Java side renders it from, which name the chrome it is drawn over
     */
    record Subject(@NotNull String key, @NotNull Optional<ResourceId> style, @NotNull TextOptions options) {

        /**
         * Answers the stem of the client drawing one of this subject's rows is compared with - the roster
         * key, {@code _chrome} for the box alone, and the style token. The client draws one tooltip box,
         * so a row over the code-drawn box reads the drawing its sprite row reads.
         *
         * @param boxAlone whether the row is the subject's box alone rather than the whole tooltip
         * @return the reference stem, without its {@code .png}
         */
        @NotNull String referenceStem(boolean boxAlone) {
            return this.stem(boxAlone, "");
        }

        /**
         * Answers the key one of this subject's rows is reported under - its reference stem, with a
         * {@code chrome} token ahead of the style token for a row over the code-drawn box, the order the
         * parity toolkit sorts a stem's tokens in.
         *
         * @param boxAlone whether the row is the subject's box alone rather than the whole tooltip
         * @return the row's key, which also names its output folder
         */
        @NotNull String rowKey(boolean boxAlone) {
            return this.stem(boxAlone, switch (this.options.getChromeStyle().orElseThrow()) {
                case SPRITE -> "";
                case PROCEDURAL -> "~chrome=procedural";
            });
        }

        /**
         * Spells a stem from the roster key, the box-alone qualifier, a chrome token and the style token,
         * in that order.
         *
         * @param boxAlone whether the row is the subject's box alone rather than the whole tooltip
         * @param chromeToken the chrome token, empty for none
         * @return the stem
         */
        private @NotNull String stem(boolean boxAlone, @NotNull String chromeToken) {
            return this.key + (boxAlone ? "_chrome" : "") + chromeToken
                + this.style.map(id -> "~style=" + id.namespace() + "__" + id.name()).orElse("");
        }

    }

    /**
     * What one run renders, against what, and where it writes.
     *
     * @param label the configuration's label, {@code vanilla} or a label the roster's {@code packs}
     *     section keys
     * @param references the directory the harness drew the configuration's references into
     * @param output the directory the per-row folders and the report are written into
     * @param options the client options the renderer is bound by - vanilla's own, or the handshake's
     *     packs stacked over them
     * @param subjects the subjects the configuration renders - in roster order, and for {@code vanilla}
     *     its code-drawn ones after its sprite ones
     * @param handshake the pack run's {@code stack.json}, empty for {@code vanilla}
     */
    record Configuration(@NotNull String label, @NotNull Path references, @NotNull Path output,
                         @NotNull ClientOptions options, @NotNull List<Subject> subjects,
                         @NotNull Optional<JsonObject> handshake) {

        /**
         * Builds the vanilla configuration: the roster's own subjects against the reference tree's
         * {@code tooltips/}, over vanilla's assets.
         *
         * @param roster the roster
         * @return the configuration
         */
        static @NotNull Configuration vanilla(@NotNull JsonObject roster) {
            return new Configuration(VANILLA, ParityPaths.references("tooltips"), Path.of("cache/visual/tooltip-parity-vanilla"),
                ClientOptions.defaults(), TooltipParitySweep.subjects(roster, VANILLA), Optional.empty());
        }

        /**
         * Builds a pack configuration: the roster's section for the label against the pack run's
         * drawings beside the reference tree, over the packs its handshake names. Exits the JVM with
         * status 1 when the pack run has written no handshake.
         *
         * @param roster the roster
         * @param label the label the roster's {@code packs} section keys
         * @return the configuration
         * @throws IOException if the handshake cannot be read
         */
        static @NotNull Configuration overPacks(@NotNull JsonObject roster, @NotNull String label) throws IOException {
            List<Subject> subjects = TooltipParitySweep.subjects(roster, label);
            Path references = ParityPaths.REFERENCES.resolveSibling("pack-tooltips").resolve(label);
            Path file = references.resolve(HANDSHAKE);
            if (!Files.isRegularFile(file)) {
                System.err.printf("No pack-tooltip handshake at %s%n  Run the harness's pack-tooltip run for '%s' first - "
                    + "renderVanillaPackTooltipsHypixelSkyblock draws hypixel-skyblock.%n", file.toAbsolutePath(), label);
                System.exit(1);
            }
            JsonObject handshake = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            ClientOptions options = ClientOptions.builder().texturePacks(Concurrent.adoptList(packFiles(handshake))).build();
            return new Configuration(label, references, Path.of("cache/visual/tooltip-" + label + "-parity-vanilla"),
                options, subjects, Optional.of(handshake));
        }

        /**
         * Answers the TSV report file.
         *
         * @return {@code parity-report.tsv} in the output directory
         */
        @NotNull Path report() {
            return this.output.resolve("parity-report.tsv");
        }

    }

    /**
     * Reads the roster off the classpath, which is the harness's own file.
     *
     * @return the roster
     * @throws IOException if the roster cannot be read
     */
    static @NotNull JsonObject roster() throws IOException {
        try (InputStream in = TooltipParitySweep.class.getResourceAsStream(ROSTER)) {
            if (in == null) {
                throw new IllegalStateException(String.format(
                    "The tooltip roster '%s' is not on the classpath beside '%s'", ROSTER, TooltipParitySweep.class.getName()));
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /**
     * Reads one configuration's subjects off the roster, each line a text component drawn as lore over
     * a chrome in the style the subject names. {@code vanilla} reads the roster's own subjects over the
     * sprite box, then each unstyled one again over the code-drawn box, which draws no style; a pack
     * label reads its section over the sprite box alone, since the code-drawn box reads nothing from a
     * pack, each subject taking the lines of the unstyled roster subject its key names and its style
     * from the section.
     *
     * @param roster the roster
     * @param label {@code vanilla} or a label the roster's {@code packs} section keys
     * @return the subjects, in roster order, and for {@code vanilla} its code-drawn ones after its
     *     sprite ones
     * @throws IllegalArgumentException if the label is neither
     * @throws IllegalStateException if a pack subject's key names no unstyled roster subject
     */
    static @NotNull List<Subject> subjects(@NotNull JsonObject roster, @NotNull String label) {
        List<JsonObject> listed = new ArrayList<>();
        roster.getAsJsonArray("subjects").forEach(element -> listed.add(element.getAsJsonObject()));
        if (label.equals(VANILLA)) {
            Stream<Subject> sprite = listed.stream()
                .map(entry -> subject(entry.get("key").getAsString(), style(entry), entry.getAsJsonArray("lines"), ChromeStyle.SPRITE));
            // The code-drawn box draws no tooltip style, and the client draws a styled tooltip in its style's
            // sprites, so only an unstyled subject has a client drawing to measure that box against.
            Stream<Subject> codeDrawn = listed.stream()
                .filter(entry -> !entry.has("style"))
                .map(entry -> subject(entry.get("key").getAsString(), Optional.empty(), entry.getAsJsonArray("lines"), ChromeStyle.PROCEDURAL));
            return Stream.concat(sprite, codeDrawn).toList();
        }

        JsonObject packs = roster.has("packs") ? roster.getAsJsonObject("packs") : new JsonObject();
        if (!packs.has(label)) {
            throw new IllegalArgumentException(String.format(
                "The tooltip roster has no configuration '%s' - it has '%s' and the packs '%s'", label, VANILLA, packs.keySet()));
        }
        Map<String, JsonArray> unstyled = new HashMap<>();
        for (JsonObject entry : listed)
            if (!entry.has("style")) unstyled.putIfAbsent(entry.get("key").getAsString(), entry.getAsJsonArray("lines"));

        List<Subject> subjects = new ArrayList<>();
        for (JsonElement element : packs.getAsJsonObject(label).getAsJsonArray("subjects")) {
            JsonObject entry = element.getAsJsonObject();
            String key = entry.get("key").getAsString();
            JsonArray lines = unstyled.get(key);
            if (lines == null) {
                throw new IllegalStateException(String.format(
                    "Pack subject '%s' of '%s' names no unstyled subject of the roster", key, label));
            }
            subjects.add(subject(key, style(entry), lines, ChromeStyle.SPRITE));
        }
        return subjects;
    }

    /**
     * Maps a handshake's requested packs to the files the pack run discovered them as, in request
     * order, through the mapping {@link PackSourceParity#packFiles} applies to the pack-source dump.
     *
     * @param handshake the pack run's {@code stack.json}
     * @return the pack files, lowest priority first
     * @throws IllegalStateException if the handshake is of another format, or a requested pack is not
     *     a folder-source id
     */
    static @NotNull List<File> packFiles(@NotNull JsonObject handshake) {
        JsonElement format = handshake.get("format");
        if (format == null || !format.isJsonPrimitive() || !String.valueOf(HANDSHAKE_FORMAT).equals(format.getAsString())) {
            throw new IllegalStateException(String.format(
                "The pack-tooltip handshake is format '%s', and this sweep reads format '%s'", format, HANDSHAKE_FORMAT));
        }
        return PackSourceParity.packFiles(handshake);
    }

    /**
     * Compares the chrome sprites the client drew with against the renderer's resolution of the same
     * styles, file by file: the pack the client took each file from, its SHA-256 and the sprite's
     * scaling against the renderer's, a sprite the client draws that the renderer does not resolve, a
     * sprite no pack supplied to the client, and a file the renderer resolves that the client's tooltip
     * does not draw.
     *
     * @param client the handshake's {@code chrome_sources} - per client sprite id its {@code file}, the
     *     {@code stack} of packs supplying it lowest priority first, and its {@code scaling}
     * @param rendered the renderer's resolution - per file its {@code pack}, {@code sha256} and
     *     {@code scaling}
     * @return every mismatch, each naming its file, sorted; empty when the two sides agree
     */
    static @NotNull List<String> resolutionMismatches(@NotNull JsonObject client, @NotNull JsonObject rendered) {
        List<String> mismatches = new ArrayList<>();
        Set<String> drawn = new HashSet<>();
        for (Map.Entry<String, JsonElement> sprite : client.entrySet()) {
            JsonObject entry = sprite.getValue().getAsJsonObject();
            String file = entry.get("file").getAsString();
            drawn.add(file);
            JsonArray stack = entry.getAsJsonArray("stack");
            if (stack.isEmpty()) {
                mismatches.add(String.format("%s: no pack supplies the client's sprite '%s'", file, sprite.getKey()));
                continue;
            }
            JsonElement resolved = rendered.get(file);
            if (resolved == null) {
                mismatches.add(String.format("%s: the client draws it as sprite '%s', and the renderer does not resolve it", file, sprite.getKey()));
                continue;
            }

            JsonObject top = stack.get(stack.size() - 1).getAsJsonObject();
            JsonObject ours = resolved.getAsJsonObject();
            if (!top.get("pack").equals(ours.get("pack")))
                mismatches.add(String.format("%s: the client takes it from %s, the renderer from %s", file, top.get("pack"), ours.get("pack")));
            if (!top.get("sha256").equals(ours.get("sha256")))
                mismatches.add(String.format("%s: the client's bytes hash %s, the renderer's %s", file, top.get("sha256"), ours.get("sha256")));
            if (!entry.get("scaling").equals(ours.get("scaling")))
                mismatches.add(String.format("%s: the client scales it as %s, the renderer as %s", file, entry.get("scaling"), ours.get("scaling")));
        }
        for (String file : rendered.keySet()) {
            if (!drawn.contains(file))
                mismatches.add(String.format("%s: the renderer resolves it, and the client's tooltip does not draw it", file));
        }
        return mismatches.stream().sorted().toList();
    }

    /**
     * Compares the text shader the client runs against the one the renderer reads its palette from: a
     * palette read from another file, or from another pack than the one on top of the client's stack, a
     * palette read from no pack where a pack supplies the client's program, and a text shader the
     * renderer could not read.
     *
     * @param client the handshake's {@code text_shader} - the {@code file} the client compiles its GUI text
     *     pipeline's vertex shader from, and the {@code stack} of packs supplying it lowest priority first
     * @param rendered the renderer's reading - the {@code state} of its palette and, where one was read,
     *     the {@code file} and the {@code pack} it was read from
     * @return every mismatch, each naming the client's file; empty when the two sides agree
     */
    static @NotNull List<String> textShaderMismatches(@NotNull JsonObject client, @NotNull JsonObject rendered) {
        String file = client.get("file").getAsString();
        JsonArray stack = client.getAsJsonArray("stack");
        if (stack.isEmpty())
            return List.of(String.format("%s: no pack supplies the client's text shader", file));

        String top = stack.get(stack.size() - 1).getAsJsonObject().get("pack").getAsString();
        String state = rendered.get("state").getAsString();
        if (state.equals("absent")) {
            return top.equals(VANILLA_PACK) ? List.of()
                : List.of(String.format("%s: the client runs it from %s, and the renderer reads no text shader from a pack", file, top));
        }
        if (!state.equals("present")) {
            return List.of(String.format("%s: the client runs it from %s, and the renderer reads no colour swap from the text shader its stack takes",
                file, top));
        }

        List<String> mismatches = new ArrayList<>();
        String read = rendered.get("file").getAsString();
        String pack = rendered.get("pack").getAsString();
        if (!read.equals(file))
            mismatches.add(String.format("%s: the client runs it, and the renderer reads its palette from %s", file, read));
        if (!pack.equals(top))
            mismatches.add(String.format("%s: the client runs it from %s, and the renderer reads its palette from %s", file, top, pack));
        return mismatches;
    }

    /**
     * Compares every shader program and include a pack ships to the client against the renderer's
     * resolution of the same files, file by file: the pack the client takes each from and its SHA-256
     * against the renderer's, a file the client resolves from a pack and the renderer from none, a file
     * no pack supplied to the client, and a file the renderer resolves from a pack that the client does
     * not list.
     *
     * @param client the handshake's {@code shader_sources} - per file the stack of packs supplying it,
     *     lowest priority first
     * @param rendered the renderer's resolution of every shader file it takes from a pack above vanilla
     *     - per file its {@code pack} and {@code sha256}
     * @return every mismatch, each naming its file, sorted; empty when the two sides agree
     */
    static @NotNull List<String> shaderMismatches(@NotNull JsonObject client, @NotNull JsonObject rendered) {
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, JsonElement> source : client.entrySet()) {
            String file = source.getKey();
            JsonArray stack = source.getValue().getAsJsonArray();
            if (stack.isEmpty()) {
                mismatches.add(String.format("%s: no pack supplies the client's copy", file));
                continue;
            }
            JsonElement resolved = rendered.get(file);
            if (resolved == null) {
                mismatches.add(String.format("%s: the client takes it from a pack, and the renderer from none above vanilla", file));
                continue;
            }

            JsonObject top = stack.get(stack.size() - 1).getAsJsonObject();
            JsonObject ours = resolved.getAsJsonObject();
            if (!top.get("pack").equals(ours.get("pack")))
                mismatches.add(String.format("%s: the client takes it from %s, the renderer from %s", file, top.get("pack"), ours.get("pack")));
            if (!top.get("sha256").equals(ours.get("sha256")))
                mismatches.add(String.format("%s: the client's bytes hash %s, the renderer's %s", file, top.get("sha256"), ours.get("sha256")));
        }
        for (String file : rendered.keySet()) {
            if (!client.has(file))
                mismatches.add(String.format("%s: the renderer takes it from a pack, and the client lists no pack shipping it", file));
        }
        return mismatches.stream().sorted().toList();
    }

    /**
     * Writes a sprite's scaling in the client codec's field names for its type - the type alone for a
     * stretch, its size as well for a tile, and its size, four border sides and inner stretch for a
     * nine-slice.
     *
     * @param scaling the scaling the chrome hands its kit
     * @return the scaling as the handshake writes one
     */
    static @NotNull JsonObject scalingJson(@NotNull MCMeta.GuiScaling scaling) {
        JsonObject json = new JsonObject();
        json.addProperty("type", scaling.type().name().toLowerCase(Locale.ROOT));
        switch (scaling) {
            case MCMeta.GuiScaling.Stretch stretch -> { }
            case MCMeta.GuiScaling.Tile tile -> {
                json.addProperty("width", tile.width());
                json.addProperty("height", tile.height());
            }
            case MCMeta.GuiScaling.NineSlice nineSlice -> {
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
        }
        return json;
    }

    /**
     * Runs the tooltip parity sweep over one configuration, or over the subjects one roster key names.
     *
     * @param args the configuration - {@code vanilla} or a label the roster's {@code packs} section
     *     keys - then an optional roster key to scope the run to
     * @throws IOException if the roster or the handshake cannot be read, or the report cannot be written
     * @throws IllegalArgumentException if no configuration is given, or the roster has none of that label
     * @throws IllegalStateException if a pack configuration's chrome or text shader resolves differently
     *     from the client's
     */
    public static void main(String @NotNull [] args) throws IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException(
                "Usage: TooltipParitySweep <vanilla | pack label> [tooltipId] - the configuration is required");
        }
        JsonObject roster = roster();
        Configuration configuration = args[0].equals(VANILLA)
            ? Configuration.vanilla(roster)
            : Configuration.overPacks(roster, args[0]);
        // A pack configuration has already refused a missing handshake, which lives in its reference
        // directory, so only the vanilla tree can be missing here.
        if (!Files.isDirectory(configuration.references())) {
            System.err.printf("Vanilla tooltip reference directory missing: %s%n  Run renderVanillaTooltipReferences first.%n",
                configuration.references().toAbsolutePath());
            return;
        }
        Files.createDirectories(configuration.output());

        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(configuration.options());
        } catch (ClientException | MojangApiException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }
        RendererContext context = RendererContext.load(result);
        if (configuration.handshake().isPresent()) checkResolution(configuration, result, context);
        TextRenderer javaRenderer = new TextRenderer(context);

        List<Subject> subjects = configuration.subjects().stream()
            .filter(subject -> args.length < 2 || subject.key().equals(args[1]))
            .toList();
        if (subjects.isEmpty()) {
            System.err.printf("No tooltip subject keyed '%s' in '%s'%n", args.length < 2 ? "" : args[1], configuration.label());
            return;
        }

        List<Row> rows = new ArrayList<>();
        for (Subject subject : subjects) {
            rows.add(renderAndCompare(configuration, subject, false, javaRenderer, context));
            rows.add(renderAndCompare(configuration, subject, true, javaRenderer, context));
        }

        rows.sort(SweepReport.byDelta(Row::meanDelta));

        List<String> lines = new ArrayList<>(rows.size());
        for (Row r : rows) {
            lines.add(String.join("\t",
                r.subject(), SweepReport.delta(r.meanDelta()), SweepReport.status(r.meanDelta()),
                SweepReport.pixels(r.meanDelta(), r.differingPixels()),
                SweepReport.ratio(r.javaCoverage()), SweepReport.ratio(r.vanillaCoverage()),
                SweepReport.pixels(r.meanDelta(), r.rawDifferingPixels()),
                Integer.toString(r.javaW()), Integer.toString(r.javaH()),
                Integer.toString(r.vanillaW()), Integer.toString(r.vanillaH()), r.standIns()));
        }
        SweepReport.write(configuration.report(), SweepReport.KEY_COLUMN
            + "\tmean_argb_delta\tstatus\tdiffering_pixels\tjava_coverage\tvanilla_coverage"
            + "\traw_differing_pixels\tjava_w\tjava_h\tvanilla_w\tvanilla_h\tstand_ins", lines);
        SweepReport.printBuckets(rows.stream().mapToDouble(Row::meanDelta).toArray());
        System.out.printf("Wrote %s (%d rows)%n", configuration.report(), rows.size());
        System.out.println("Note: direct diff at a shared canvas - a size mismatch is the finding, not something to align away.");
    }

    /**
     * Holds the renderer's chrome resolution, the text shader it reads its palette from and the shader
     * files it resolves from a pack to the client's before any pixel is compared, refusing the
     * configuration on any mismatch.
     *
     * @param configuration the pack configuration, carrying its handshake
     * @param assets the client assets the renderer was loaded from
     * @param context the context the renderer resolves its chrome through
     * @throws IllegalStateException if the two sides resolve any chrome sprite, the text shader or any
     *     pack-shipped shader file differently
     */
    private static void checkResolution(@NotNull Configuration configuration, @NotNull ClientAssets assets,
                                        @NotNull RendererContext context) {
        JsonObject handshake = configuration.handshake().orElseThrow();
        PackStack stack = PackAcquisition.acquire(assets);
        Map<PackId, String> labelOf = labelsOf(stack, PackSourceParity.labels(handshake));
        JsonObject rendered = rendered(stack, labelOf, context, configuration.subjects());
        JsonObject shaders = shaderSources(stack, labelOf);
        JsonObject clientText = handshake.getAsJsonObject("text_shader");

        List<String> mismatches = new ArrayList<>(resolutionMismatches(handshake.getAsJsonObject("chrome_sources"), rendered));
        mismatches.addAll(textShaderMismatches(clientText, textShader(stack, labelOf)));
        mismatches.addAll(shaderMismatches(handshake.getAsJsonObject("shader_sources"), shaders));
        if (!mismatches.isEmpty()) {
            throw new IllegalStateException(String.format(
                "The renderer resolves the chrome or the text shader of '%s' differently from the client: %s", configuration.label(), mismatches));
        }
        JsonArray textStack = clientText.getAsJsonArray("stack");
        System.out.printf("Chrome resolution of '%s' agrees with the client on %d sprites, and its text shader on '%s' from '%s' and %d shader files%n",
            configuration.label(), rendered.size(), clientText.get("file").getAsString(),
            textStack.get(textStack.size() - 1).getAsJsonObject().get("pack").getAsString(), shaders.size());
    }

    /**
     * Maps each pack of the stack to the client's id for it.
     *
     * @param stack the pack stack loaded from the same client assets as the context
     * @param labels the client's id for each pack of the stack, lowest priority first
     * @return the client's id for each pack
     * @throws IllegalStateException if the stack holds another number of packs than there are labels
     */
    private static @NotNull Map<PackId, String> labelsOf(@NotNull PackStack stack, @NotNull List<String> labels) {
        List<ResourcePack> ascending = stack.ascending();
        if (ascending.size() != labels.size())
            throw new IllegalStateException(String.format("The renderer stacked %d packs for '%s'", ascending.size(), labels));
        Map<PackId, String> labelOf = new HashMap<>();
        for (int i = 0; i < ascending.size(); i++) labelOf.put(ascending.get(i).id(), labels.get(i));
        return labelOf;
    }

    /**
     * Answers the text shader the renderer reads its palette from, as the comparison reads it - the
     * state of the stack's palette and, where one was read, the file and the client's id for the pack it
     * was read from.
     *
     * @param stack the pack stack
     * @param labelOf the client's id for each pack of the stack
     * @return the reading
     */
    private static @NotNull JsonObject textShader(@NotNull PackStack stack, @NotNull Map<PackId, String> labelOf) {
        Possible<TextPalette> palette = stack.textPalette();
        JsonObject reading = new JsonObject();
        reading.addProperty("state", palette.getState().name().toLowerCase(Locale.ROOT));
        palette.ifPresent(read -> {
            reading.addProperty("file", read.id().id());
            reading.addProperty("pack", labelOf.get(read.pack()));
        });
        return reading;
    }

    /**
     * Resolves every shader program and include the renderer takes from a pack above vanilla, and
     * answers each file with the client's id for that pack and the SHA-256 of its bytes.
     *
     * @param stack the pack stack
     * @param labelOf the client's id for each pack of the stack
     * @return the resolution, keyed by {@code <namespace>:shaders/<path>} in sorted order
     */
    private static @NotNull JsonObject shaderSources(@NotNull PackStack stack, @NotNull Map<PackId, String> labelOf) {
        SortedMap<String, PackSubtree.Entry> winners = new TreeMap<>();
        for (PackSubtree.Entry entry : TextPaletteLoader.shaderFiles(stack.ascending()))
            winners.put(entry.namespace() + ":" + entry.resourcePath(), entry);

        JsonObject sources = new JsonObject();
        winners.forEach((file, entry) -> {
            if (entry.pack().id().equals(PackId.VANILLA)) return;
            JsonObject source = new JsonObject();
            source.addProperty("pack", labelOf.get(entry.pack().id()));
            source.addProperty("sha256", ParityJson.sha256(entry.container().bytes(entry.entryPath()).orElseThrow()));
            sources.add(file, source);
        });
        return sources;
    }

    /**
     * Resolves the chrome of every style the subjects name the way {@link TextRenderer} does, and
     * answers each sprite's file with the pack that supplies it, the SHA-256 of its bytes and the
     * scaling the chrome hands its kit.
     *
     * @param stack the pack stack loaded from the same client assets as the context
     * @param labelOf the client's id for each pack of the stack
     * @param context the context the chrome resolves its sprites through
     * @param subjects the configuration's subjects
     * @return the resolution, keyed by {@code <namespace>:textures/<path>.png} in sorted order
     * @throws IllegalStateException if the chrome draws the missing texture for a sprite the stack does
     *     not supply
     */
    private static @NotNull JsonObject rendered(@NotNull PackStack stack, @NotNull Map<PackId, String> labelOf,
                                                @NotNull RendererContext context, @NotNull List<Subject> subjects) {
        SortedMap<String, JsonObject> files = new TreeMap<>();
        for (Optional<ResourceId> style : subjects.stream().map(Subject::style).distinct().toList()) {
            TooltipChrome.ChromeSprites sprites = TooltipChrome.ChromeSprites.resolve(context, style);
            files.put(file(sprites.backgroundId()), sprite(stack, labelOf, sprites.backgroundId(), sprites.backgroundScaling()));
            files.put(file(sprites.frameId()), sprite(stack, labelOf, sprites.frameId(), sprites.frameScaling()));
        }
        JsonObject out = new JsonObject();
        files.forEach(out::add);
        return out;
    }

    /**
     * Answers one resolved sprite as the comparison reads it - the pack that supplies its file, the
     * SHA-256 of the file's bytes and its scaling.
     *
     * @param stack the pack stack
     * @param labelOf the client's id for each pack of the stack
     * @param id the sprite's texture id
     * @param scaling the scaling the chrome hands its kit
     * @return the sprite
     * @throws IllegalStateException if the stack does not supply the sprite
     */
    private static @NotNull JsonObject sprite(@NotNull PackStack stack, @NotNull Map<PackId, String> labelOf,
                                              @NotNull ResourceId id, @NotNull MCMeta.GuiScaling scaling) {
        ResolvedTexture texture = stack.resolve(id).orElseThrow(() -> new IllegalStateException(String.format(
            "The chrome resolved sprite '%s', which the pack stack does not supply", id)));
        JsonObject sprite = new JsonObject();
        sprite.addProperty("pack", labelOf.get(texture.pack()));
        sprite.addProperty("sha256", ParityJson.sha256(texture.bytes()));
        sprite.add("scaling", scalingJson(scaling));
        return sprite;
    }

    /**
     * Answers the file a texture id names, as the client's resource manager keys it.
     *
     * @param id the texture id
     * @return {@code <namespace>:textures/<path>.png}
     */
    private static @NotNull String file(@NotNull ResourceId id) {
        return id.namespace() + ":textures/" + id.name() + ".png";
    }

    /**
     * Builds one subject: its lines as lore over the given chrome, in the style it names.
     *
     * @param key the roster key
     * @param style the tooltip style, empty for the default box
     * @param lines the subject's lines as the roster spells them
     * @param chrome the chrome the subject is drawn over
     * @return the subject
     */
    private static @NotNull Subject subject(@NotNull String key, @NotNull Optional<ResourceId> style, @NotNull JsonArray lines,
                                            @NotNull ChromeStyle chrome) {
        ConcurrentList<LineSegment> segments = Concurrent.newList();
        for (JsonElement line : lines)
            segments.addAll(TextSegment.fromJson(line).toLines());

        return new Subject(key, style, TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .chromeStyle(chrome)
            .tooltipStyle(style)
            .lines(segments)
            .build());
    }

    /**
     * Reads the tooltip style a roster entry names.
     *
     * @param entry the roster entry
     * @return the style, empty when the entry names none
     */
    private static @NotNull Optional<ResourceId> style(@NotNull JsonObject entry) {
        return entry.has("style") ? Optional.of(ResourceId.parse(entry.get("style").getAsString())) : Optional.empty();
    }

    /**
     * Renders one row - the subject whole through the Java pipeline, or its box alone - takes the
     * reference to its first pixel, diffs the two, writes the per-row PNGs and returns the comparison
     * {@link Row}.
     *
     * <p>A failed row keeps the facts it has: the reference's first-pixel bounds whenever the reference
     * was read, and the renderer's canvas and the stand-ins it drew whenever one was drawn.
     *
     * @param configuration the configuration, which names the references and the output
     * @param subject the roster subject
     * @param boxAlone whether the row is the subject's box alone rather than the whole tooltip
     * @param javaRenderer the text renderer drawing the whole tooltip
     * @param context the context the box alone resolves its sprites through
     * @return the comparison row, its delta {@code POSITIVE_INFINITY} on failure
     */
    static @NotNull Row renderAndCompare(@NotNull Configuration configuration, @NotNull Subject subject, boolean boxAlone,
                                         @NotNull TextRenderer javaRenderer, @NotNull RendererContext context) {
        String key = subject.rowKey(boxAlone);
        Path subjectDir = configuration.output().resolve(key);
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        int javaW = 0;
        int javaH = 0;
        int vanillaW = 0;
        int vanillaH = 0;
        String standIns = "";
        try {
            Files.createDirectories(subjectDir);
            Path vanillaPng = configuration.references().resolve(subject.referenceStem(boxAlone) + ".png");
            BufferedImage reference = ImageIO.read(vanillaPng.toFile());
            if (reference == null) {
                System.err.printf("       %-50s vanilla PNG unreadable: %s%n", key, vanillaPng);
                return Row.failed(key, javaW, javaH, vanillaW, vanillaH, standIns);
            }

            // The reference is the whole sprite rect; the region compared is every pixel the client drew.
            PixelBuffer whole = PixelBuffer.wrap(reference);
            Rectangle drawn = whole.opaqueBounds();
            vanillaW = drawn.width;
            vanillaH = drawn.height;
            PixelBuffer vanillaPB = whole.crop(drawn.x, drawn.y, drawn.width, drawn.height);
            BufferedImage vanillaImg = vanillaPB.toBufferedImage();

            PixelBuffer javaPB;
            if (boxAlone) {
                SubstitutionCollector collector = new SubstitutionCollector();
                javaPB = drawBoxAlone(context.collecting(collector), subject.options(),
                    reference.getWidth() / scale - 2 * CLIENT_MARGIN_MCPX, reference.getHeight() / scale - 2 * CLIENT_MARGIN_MCPX);
                standIns = standIns(collector.snapshot());
            } else {
                RenderResult result = javaRenderer.render(subject.options());
                javaPB = result.image().toPixelBuffer();
                standIns = standIns(result.substitutions());
            }
            BufferedImage javaImg = javaPB.toBufferedImage();
            javaW = javaImg.getWidth();
            javaH = javaImg.getHeight();

            ImageIO.write(vanillaImg, "PNG", new File(subjectDir.toFile(), "vanilla.png"));
            ImageIO.write(javaImg, "PNG", new File(subjectDir.toFile(), "java.png"));

            if (vanillaW != javaW || vanillaH != javaH) {
                System.err.printf("       %-50s canvas %dx%d against the client's %dx%d to its first pixel - not comparable%n",
                    key, javaW, javaH, vanillaW, vanillaH);
                return Row.failed(key, javaW, javaH, vanillaW, vanillaH, standIns);
            }

            ImageIO.write(vanillaPB.diff(javaPB, DiffType.OVER_WHITE).toBufferedImage(), "PNG",
                new File(subjectDir.toFile(), "diff.png"));
            ImageIO.write(ParityMetrics.panelDiff(vanillaImg, javaImg, vanillaPB, javaPB), "PNG",
                new File(subjectDir.toFile(), "diff_panel.png"));

            ParityMetrics.Stats stats = ParityMetrics.compareImages(vanillaImg, javaImg);
            System.out.printf("  %-50s mean delta %.2f  diff-px %d  raw-px %d  java-cov %.1f%%  vanilla-cov %.1f%%%n",
                key, stats.meanDelta(), stats.differingPixels(), stats.rawDifferingPixels(),
                stats.javaCoverage() * 100, stats.vanillaCoverage() * 100);
            return new Row(key, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.vanillaCoverage(),
                stats.rawDifferingPixels(), javaW, javaH, vanillaW, vanillaH, standIns);
        } catch (Exception ex) {
            System.err.printf("       %-50s FAILED: %s%n", key, ex.getMessage());
            return Row.failed(key, javaW, javaH, vanillaW, vanillaH, standIns);
        }
    }

    /**
     * Spells the stand-ins a row's render drew as its {@code stand_ins} cell: each one's kind, state and
     * id, then the item that named it where there is one, space-separated, the stand-ins joined by
     * {@code "; "} in the order they arrive.
     *
     * @param substitutions the stand-ins the render drew, distinct and in their natural order
     * @return the cell, empty where the render drew none
     */
    private static @NotNull String standIns(@NotNull List<Substitution> substitutions) {
        return substitutions.stream()
            .map(substitution -> substitution.kind() + " " + substitution.state() + " " + substitution.id()
                + substitution.namedBy().map(item -> " " + item).orElse(""))
            .collect(Collectors.joining("; "));
    }

    /**
     * Draws a subject's box alone for the client's content size, through the calls
     * {@link TextRenderer} makes for its own canvas: the chrome the options name lays out its box for
     * that content and contributes its layers, folded onto the canvas the chrome lays out for that
     * content.
     *
     * @param context the context the chrome's sprites resolve through - the configuration's assets -
     *     which records every stand-in the chrome draws
     * @param options the subject's options, which name the chrome and its tooltip style
     * @param contentWMcPx the client's content width, in Minecraft pixels
     * @param contentHMcPx the client's content height, in Minecraft pixels
     * @return the drawn box
     */
    private static @NotNull PixelBuffer drawBoxAlone(@NotNull RendererContext context, @NotNull TextOptions options,
                                                     int contentWMcPx, int contentHMcPx) {
        TooltipChrome chrome = TooltipChrome.of(options.getChromeStyle().orElseThrow());
        Optional<TooltipChrome.ChromeSprites> sprites = Optional.of(TooltipChrome.ChromeSprites.resolve(context, options.getTooltipStyle()));
        TooltipChrome.ChromeBox box = chrome.box(contentWMcPx, contentHMcPx, sprites, options);
        int scale = MinecraftFont.MC_PIXEL_SCALE;

        LayerStack<ImageLayer> stack = new LayerStack<>();
        chrome.contribute(stack, box, sprites, options);
        PixelBuffer buffer = PixelBuffer.create(Math.round(box.width() * scale), Math.round(box.height() * scale));
        Layers.foldInto(stack, options.getLayerDecorator(), buffer);
        return buffer;
    }

    /**
     * Per-row line in the TSV report.
     *
     * @param subject the row's key - its reference stem, with the chrome token for a row over the
     *     code-drawn box
     * @param meanDelta the mean over-white delta, {@code POSITIVE_INFINITY} when the comparison did not happen
     * @param differingPixels the count of pixels whose over-white delta is non-zero
     * @param javaCoverage the fraction of the renderer's pixels with non-zero alpha
     * @param vanillaCoverage the fraction of the compared reference pixels with non-zero alpha
     * @param rawDifferingPixels the count of pixels whose packed ARGB differ
     * @param javaW the renderer's canvas width in output px, 0 when nothing was drawn
     * @param javaH the renderer's canvas height in output px, 0 when nothing was drawn
     * @param vanillaW the reference's first-pixel bounds width in output px, the region compared, 0 when
     *     it was not read
     * @param vanillaH the reference's first-pixel bounds height in output px, the region compared, 0 when
     *     it was not read
     * @param standIns the stand-ins the render drew, each its kind, state and id, then the item that named
     *     it where there is one, joined by {@code "; "}; empty where it drew none or nothing was drawn
     */
    record Row(@NotNull String subject, double meanDelta, long differingPixels, double javaCoverage,
               double vanillaCoverage, long rawDifferingPixels,
               int javaW, int javaH, int vanillaW, int vanillaH, @NotNull String standIns) {

        /**
         * Builds a row whose comparison did not happen, keeping the facts it has.
         *
         * @param subject the row's key - its reference stem, with the chrome token for a row over the
         *     code-drawn box
         * @param javaW the renderer's canvas width, 0 when nothing was drawn
         * @param javaH the renderer's canvas height, 0 when nothing was drawn
         * @param vanillaW the reference's first-pixel bounds width, 0 when it was not read
         * @param vanillaH the reference's first-pixel bounds height, 0 when it was not read
         * @param standIns the stand-ins the render drew, empty where it drew none or nothing was drawn
         * @return the failed row
         */
        static @NotNull Row failed(@NotNull String subject, int javaW, int javaH, int vanillaW, int vanillaH,
                                   @NotNull String standIns) {
            return new Row(subject, Double.POSITIVE_INFINITY, -1, 0, 0, -1, javaW, javaH, vanillaW, vanillaH, standIns);
        }

    }

}
