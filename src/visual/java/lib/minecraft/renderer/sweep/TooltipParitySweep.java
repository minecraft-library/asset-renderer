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
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.DiffType;
import lib.minecraft.renderer.TextRenderer;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.bake.gui.TooltipChrome;
import lib.minecraft.renderer.call.request.ChromeStyle;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.pack.PackAcquisition;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.ResolvedTexture;
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

/**
 * Per-subject tooltip parity report comparing {@link TextRenderer} against the
 * vanilla-reference-harness ground truth for one configuration: {@code vanilla}, the tracked tooltip
 * roster against the reference tree's {@code tooltips/}, or a pack label the roster's {@code packs}
 * section keys, that section against the harness's pack run beside the tree.
 *
 * <p>The harness draws every subject of the configuration through the client's own tooltip call onto
 * the box's whole sprite rect - the client's measurement of the lines plus {@link #CLIENT_MARGIN_MCPX}
 * on every side. This sweep reads the same roster off its own classpath and renders each subject the
 * way a caller would: lore lines over the sprite chrome, the subject's tooltip style named, through a
 * renderer bound to the client's assets. A pack subject names a vanilla subject by key and renders
 * that subject's lines, so a pack row and the vanilla row of its key carry one text.
 *
 * <p><b>A pack configuration reads the run's handshake first.</b> The pack run writes
 * {@code stack.json} beside its drawings: the packs the client selected, and for every tooltip sprite
 * its section reaches the pack the client took the sprite's file from, that file's SHA-256 and the
 * scaling of the sprite the client stitched. This side loads the same packs from the same folder in the
 * same order and, before any pixel, resolves the chrome of every style the section names the way the
 * renderer does; a sprite it takes from another pack, with other bytes or another scaling, one it does
 * not resolve and one the client does not draw each refuse the configuration, every mismatch named. A
 * row compared over chrome the two sides resolved differently would measure the resolution rather than
 * the drawing.
 *
 * <p><b>Each subject is two rows.</b> The full row renders through {@link TextRenderer}, so it measures
 * the renderer whole. The box-alone row, keyed {@code _chrome} as the harness keys its twin, draws the
 * production chrome for the CLIENT's box - the content size read off the reference - through the same
 * calls {@code TextRenderer} makes, so it measures whether the chrome draws that box whatever width the
 * renderer's font would have given. A mover in a box-alone row is the box; one in the full row alone is
 * the text.
 *
 * <p><b>The cut follows the chrome.</b> The reference is the whole sprite rect and the renderer's canvas
 * is the text padded by what its chrome answers, so the reference is cut by the difference on every
 * side, the padding being the production chrome's own answer rather than a number restated here. A
 * chrome padding by the client's whole margin cuts nothing. What the cut drops of the client's drawing
 * is the {@code clipped_pixels} column. After the cut the two canvases must be equal, and a size that
 * disagrees is the finding rather than something to align away, so its row fails.
 *
 * <p><b>The byte gate.</b> Both sides are read as held - the reference through {@link ImageIO}, which
 * keeps the stored framebuffer bytes, and the render through its own pixel buffer whatever the image's
 * alpha mark - so {@code raw_differing_pixels} is a byte comparison and the over-white columns are what
 * a person reads beside it.
 *
 * <p>Output: one folder per row under {@code cache/visual/tooltip-parity-vanilla/}, or
 * {@code cache/visual/tooltip-<label>-parity-vanilla/} for a pack configuration, holding
 * {@code vanilla.png} (the reference region compared), {@code java.png}, {@code diff.png} and
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
    private static final int HANDSHAKE_FORMAT = 1;

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
     * @param options the options the Java side renders it from
     */
    record Subject(@NotNull String key, @NotNull Optional<ResourceId> style, @NotNull TextOptions options) {}

    /**
     * What one run renders, against what, and where it writes.
     *
     * @param label the configuration's label, {@code vanilla} or a label the roster's {@code packs}
     *     section keys
     * @param references the directory the harness drew the configuration's references into
     * @param output the directory the per-row folders and the report are written into
     * @param options the client options the renderer is bound by - vanilla's own, or the handshake's
     *     packs stacked over them
     * @param subjects the subjects the configuration renders, in roster order
     * @param handshake the pack run's {@code stack.json}, empty for {@code vanilla}
     */
    private record Configuration(@NotNull String label, @NotNull Path references, @NotNull Path output,
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
     * the sprite chrome in the style the subject names. {@code vanilla} reads the roster's own
     * subjects; a pack label reads its section, each subject taking the lines of the unstyled roster
     * subject its key names and its style from the section.
     *
     * @param roster the roster
     * @param label {@code vanilla} or a label the roster's {@code packs} section keys
     * @return the subjects, in roster order
     * @throws IllegalArgumentException if the label is neither
     * @throws IllegalStateException if a pack subject's key names no unstyled roster subject
     */
    static @NotNull List<Subject> subjects(@NotNull JsonObject roster, @NotNull String label) {
        List<JsonObject> listed = new ArrayList<>();
        roster.getAsJsonArray("subjects").forEach(element -> listed.add(element.getAsJsonObject()));
        if (label.equals(VANILLA)) {
            return listed.stream()
                .map(entry -> subject(entry.get("key").getAsString(), style(entry), entry.getAsJsonArray("lines")))
                .toList();
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
            subjects.add(subject(key, style(entry), lines));
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
     * Writes a sprite's scaling in the client codec's field names for its type - the type alone for a
     * stretch, its size as well for a tile, and its size, four border sides and inner stretch for a
     * nine-slice - so a stretch's unused size, and the {@code -1} a sprite without a sidecar carries,
     * compare equal to the client's stretch.
     *
     * @param scaling the scaling the chrome hands its kit
     * @return the scaling as the handshake writes one
     */
    static @NotNull JsonObject scalingJson(@NotNull MCMeta.GuiScaling scaling) {
        JsonObject json = new JsonObject();
        json.addProperty("type", scaling.type().name().toLowerCase(Locale.ROOT));
        switch (scaling.type()) {
            case STRETCH -> { }
            case TILE -> {
                json.addProperty("width", scaling.width());
                json.addProperty("height", scaling.height());
            }
            case NINE_SLICE -> {
                json.addProperty("width", scaling.width());
                json.addProperty("height", scaling.height());
                JsonObject border = new JsonObject();
                border.addProperty("left", scaling.border().left());
                border.addProperty("top", scaling.border().top());
                border.addProperty("right", scaling.border().right());
                border.addProperty("bottom", scaling.border().bottom());
                json.add("border", border);
                json.addProperty("stretch_inner", scaling.stretchInner());
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
     * @throws IllegalStateException if a pack configuration's chrome resolves differently from the
     *     client's
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
                SweepReport.pixels(r.meanDelta(), r.rawDifferingPixels()), Long.toString(r.clippedPixels()),
                Integer.toString(r.javaW()), Integer.toString(r.javaH()),
                Integer.toString(r.vanillaW()), Integer.toString(r.vanillaH())));
        }
        SweepReport.write(configuration.report(), SweepReport.KEY_COLUMN
            + "\tmean_argb_delta\tstatus\tdiffering_pixels\tjava_coverage\tvanilla_coverage"
            + "\traw_differing_pixels\tclipped_pixels\tjava_w\tjava_h\tvanilla_w\tvanilla_h", lines);
        SweepReport.printBuckets(rows.stream().mapToDouble(Row::meanDelta).toArray());
        System.out.printf("Wrote %s (%d rows)%n", configuration.report(), rows.size());
        System.out.println("Note: direct diff at a shared canvas - a size mismatch is the finding, not something to align away.");
    }

    /**
     * Holds the renderer's chrome resolution to the client's before any pixel is compared, refusing
     * the configuration on any mismatch.
     *
     * @param configuration the pack configuration, carrying its handshake
     * @param assets the client assets the renderer was loaded from
     * @param context the context the renderer resolves its chrome through
     * @throws IllegalStateException if the two sides resolve any chrome sprite differently
     */
    private static void checkResolution(@NotNull Configuration configuration, @NotNull ClientAssets assets,
                                        @NotNull RendererContext context) {
        JsonObject handshake = configuration.handshake().orElseThrow();
        JsonObject rendered = rendered(PackAcquisition.acquire(assets), PackSourceParity.labels(handshake), context,
            configuration.subjects());
        List<String> mismatches = resolutionMismatches(handshake.getAsJsonObject("chrome_sources"), rendered);
        if (!mismatches.isEmpty()) {
            throw new IllegalStateException(String.format(
                "The renderer resolves the chrome of '%s' differently from the client: %s", configuration.label(), mismatches));
        }
        System.out.printf("Chrome resolution of '%s' agrees with the client on %d sprites%n", configuration.label(), rendered.size());
    }

    /**
     * Resolves the chrome of every style the subjects name the way {@link TextRenderer} does, and
     * answers each sprite's file with the pack that supplies it, the SHA-256 of its bytes and the
     * scaling the chrome hands its kit. A style whose pair the chrome drops contributes nothing.
     *
     * @param stack the pack stack loaded from the same client assets as the context
     * @param labels the client's id for each pack of the stack, lowest priority first
     * @param context the context the chrome resolves its sprites through
     * @param subjects the configuration's subjects
     * @return the resolution, keyed by {@code <namespace>:textures/<path>.png} in sorted order
     * @throws IllegalStateException if the stack holds another number of packs than there are labels,
     *     or a resolved sprite is one the stack does not supply
     */
    private static @NotNull JsonObject rendered(@NotNull PackStack stack, @NotNull List<String> labels,
                                                @NotNull RendererContext context, @NotNull List<Subject> subjects) {
        List<ResourcePack> ascending = stack.ascending();
        if (ascending.size() != labels.size())
            throw new IllegalStateException(String.format("The renderer stacked %d packs for '%s'", ascending.size(), labels));
        Map<PackId, String> labelOf = new HashMap<>();
        for (int i = 0; i < ascending.size(); i++) labelOf.put(ascending.get(i).id(), labels.get(i));

        SortedMap<String, JsonObject> files = new TreeMap<>();
        for (Optional<ResourceId> style : subjects.stream().map(Subject::style).distinct().toList()) {
            Optional<TooltipChrome.ChromeSprites> resolved = TooltipChrome.ChromeSprites.resolve(context, style);
            if (resolved.isEmpty()) continue;
            TooltipChrome.ChromeSprites sprites = resolved.get();
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
     * Builds one subject: its lines as lore over the sprite chrome, in the style it names.
     *
     * @param key the roster key
     * @param style the tooltip style, empty for the default box
     * @param lines the subject's lines as the roster spells them
     * @return the subject
     */
    private static @NotNull Subject subject(@NotNull String key, @NotNull Optional<ResourceId> style, @NotNull JsonArray lines) {
        ConcurrentList<LineSegment> segments = Concurrent.newList();
        for (JsonElement line : lines)
            segments.addAll(TextSegment.fromJson(line).toLines());

        return new Subject(key, style, TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .chromeStyle(ChromeStyle.SPRITE)
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
     * Renders one row - the subject whole through the Java pipeline, or its box alone - cuts the
     * reference to the renderer's canvas, diffs the two, writes the per-row PNGs and returns the
     * comparison {@link Row}.
     *
     * <p>A failed row keeps the facts it has: the reference's size and what the cut drops whenever the
     * reference was read, and the renderer's canvas whenever one was drawn.
     *
     * @param configuration the configuration, which names the references and the output
     * @param subject the roster subject
     * @param boxAlone whether the row is the subject's box alone rather than the whole tooltip
     * @param javaRenderer the text renderer drawing the whole tooltip
     * @param context the context the box alone resolves its sprites through
     * @return the comparison row, its delta {@code POSITIVE_INFINITY} on failure
     */
    private static @NotNull Row renderAndCompare(@NotNull Configuration configuration, @NotNull Subject subject, boolean boxAlone,
                                                 @NotNull TextRenderer javaRenderer, @NotNull RendererContext context) {
        String stem = subject.key() + (boxAlone ? "_chrome" : "")
            + subject.style().map(id -> "~style=" + id.namespace() + "__" + id.name()).orElse("");
        Path subjectDir = configuration.output().resolve(stem);
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        long clipped = 0;
        int javaW = 0;
        int javaH = 0;
        int vanillaW = 0;
        int vanillaH = 0;
        try {
            Files.createDirectories(subjectDir);
            Path vanillaPng = configuration.references().resolve(stem + ".png");
            BufferedImage reference = ImageIO.read(vanillaPng.toFile());
            if (reference == null) {
                System.err.printf("       %-50s vanilla PNG unreadable: %s%n", stem, vanillaPng);
                return Row.failed(stem, clipped, javaW, javaH, vanillaW, vanillaH);
            }
            vanillaW = reference.getWidth();
            vanillaH = reference.getHeight();

            // The cut, per side, in Minecraft pixels until the pixel edge: the client's margin less
            // what the renderer's chrome pads the text by.
            int padMcPx = TooltipChrome.of(subject.options().getChromeStyle()).paddingMcPx(subject.options());
            int cut = (CLIENT_MARGIN_MCPX - padMcPx) * scale;
            PixelBuffer whole = PixelBuffer.wrap(reference);
            for (int y = 0; y < vanillaH; y++) {
                for (int x = 0; x < vanillaW; x++) {
                    boolean kept = x >= cut && x < vanillaW - cut && y >= cut && y < vanillaH - cut;
                    if (!kept && ColorMath.alpha(whole.getPixel(x, y)) > 0) clipped++;
                }
            }
            PixelBuffer vanillaPB = whole.crop(cut, cut, vanillaW - 2 * cut, vanillaH - 2 * cut);
            BufferedImage vanillaImg = vanillaPB.toBufferedImage();

            PixelBuffer javaPB = boxAlone
                ? drawBoxAlone(context, subject.options(),
                    vanillaW / scale - 2 * CLIENT_MARGIN_MCPX, vanillaH / scale - 2 * CLIENT_MARGIN_MCPX)
                : javaRenderer.render(subject.options()).image().toPixelBuffer();
            BufferedImage javaImg = javaPB.toBufferedImage();
            javaW = javaImg.getWidth();
            javaH = javaImg.getHeight();

            ImageIO.write(vanillaImg, "PNG", new File(subjectDir.toFile(), "vanilla.png"));
            ImageIO.write(javaImg, "PNG", new File(subjectDir.toFile(), "java.png"));

            if (vanillaImg.getWidth() != javaW || vanillaImg.getHeight() != javaH) {
                System.err.printf("       %-50s canvas %dx%d against vanilla's %dx%d after the cut - not comparable%n",
                    stem, javaW, javaH, vanillaImg.getWidth(), vanillaImg.getHeight());
                return Row.failed(stem, clipped, javaW, javaH, vanillaW, vanillaH);
            }

            ImageIO.write(vanillaPB.diff(javaPB, DiffType.OVER_WHITE).toBufferedImage(), "PNG",
                new File(subjectDir.toFile(), "diff.png"));
            ImageIO.write(ParityMetrics.panelDiff(vanillaImg, javaImg, vanillaPB, javaPB), "PNG",
                new File(subjectDir.toFile(), "diff_panel.png"));

            ParityMetrics.Stats stats = ParityMetrics.compareImages(vanillaImg, javaImg);
            System.out.printf("  %-50s mean delta %.2f  diff-px %d  raw-px %d  java-cov %.1f%%  vanilla-cov %.1f%%  clipped %d%n",
                stem, stats.meanDelta(), stats.differingPixels(), stats.rawDifferingPixels(),
                stats.javaCoverage() * 100, stats.vanillaCoverage() * 100, clipped);
            return new Row(stem, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.vanillaCoverage(),
                stats.rawDifferingPixels(), clipped, javaW, javaH, vanillaW, vanillaH);
        } catch (Exception ex) {
            System.err.printf("       %-50s FAILED: %s%n", stem, ex.getMessage());
            return Row.failed(stem, clipped, javaW, javaH, vanillaW, vanillaH);
        }
    }

    /**
     * Draws a subject's box alone for the client's content size, through the calls
     * {@link TextRenderer} makes for its own canvas: the chrome the options name contributes its
     * layers for a box framing that content, folded onto a transparent canvas the chrome's padding
     * wider on every side.
     *
     * @param context the context the chrome's sprites resolve through - the configuration's assets
     * @param options the subject's options, which name the chrome and its tooltip style
     * @param contentWMcPx the client's content width, in Minecraft pixels
     * @param contentHMcPx the client's content height, in Minecraft pixels
     * @return the drawn box
     */
    private static @NotNull PixelBuffer drawBoxAlone(@NotNull RendererContext context, @NotNull TextOptions options,
                                                     int contentWMcPx, int contentHMcPx) {
        TooltipChrome chrome = TooltipChrome.of(options.getChromeStyle());
        int padMcPx = chrome.paddingMcPx(options);
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        int w = (contentWMcPx + 2 * padMcPx) * scale;
        int h = (contentHMcPx + 2 * padMcPx) * scale;

        LayerStack<ImageLayer> stack = new LayerStack<>();
        chrome.contribute(stack, new TooltipChrome.ChromeBox(w, h, scale),
            TooltipChrome.ChromeSprites.resolve(context, options.getTooltipStyle()), options);
        PixelBuffer buffer = PixelBuffer.create(w, h);
        Layers.foldInto(stack, options.getLayerDecorator(), buffer);
        return buffer;
    }

    /**
     * Per-row line in the TSV report.
     *
     * @param subject the row's reference stem
     * @param meanDelta the mean over-white delta, {@code POSITIVE_INFINITY} when the comparison did not happen
     * @param differingPixels the count of pixels whose over-white delta is non-zero
     * @param javaCoverage the fraction of the renderer's pixels with non-zero alpha
     * @param vanillaCoverage the fraction of the compared reference pixels with non-zero alpha
     * @param rawDifferingPixels the count of pixels whose packed ARGB differ
     * @param clippedPixels the count of reference pixels with non-zero alpha the cut drops
     * @param javaW the renderer's canvas width in output px, 0 when nothing was drawn
     * @param javaH the renderer's canvas height in output px, 0 when nothing was drawn
     * @param vanillaW the whole reference's width in output px, before the cut
     * @param vanillaH the whole reference's height in output px, before the cut
     */
    private record Row(@NotNull String subject, double meanDelta, long differingPixels, double javaCoverage,
                       double vanillaCoverage, long rawDifferingPixels, long clippedPixels,
                       int javaW, int javaH, int vanillaW, int vanillaH) {

        /**
         * Builds a row whose comparison did not happen, keeping the facts it has.
         *
         * @param subject the row's reference stem
         * @param clippedPixels the count of reference pixels the cut drops, 0 when the reference was not read
         * @param javaW the renderer's canvas width, 0 when nothing was drawn
         * @param javaH the renderer's canvas height, 0 when nothing was drawn
         * @param vanillaW the whole reference's width, 0 when it was not read
         * @param vanillaH the whole reference's height, 0 when it was not read
         * @return the failed row
         */
        static @NotNull Row failed(@NotNull String subject, long clippedPixels, int javaW, int javaH, int vanillaW, int vanillaH) {
            return new Row(subject, Double.POSITIVE_INFINITY, -1, 0, 0, -1, clippedPixels, javaW, javaH, vanillaW, vanillaH);
        }

    }

}
