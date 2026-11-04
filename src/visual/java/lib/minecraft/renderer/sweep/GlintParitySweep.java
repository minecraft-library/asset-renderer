package lib.minecraft.renderer.sweep;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.DiffType;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.bake.texture.GlintKit;
import lib.minecraft.renderer.call.request.ArmorOptions;
import lib.minecraft.renderer.call.request.ArmorPiece;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.call.request.PlayerOptions;
import lib.minecraft.renderer.call.request.SkinOptions;
import lib.minecraft.renderer.call.request.TextureOptions;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.store.diff.ParityMetrics;
import lib.minecraft.renderer.vanilla.equipment.ArmorMaterial;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Animated enchantment-glint parity tool. The remaining item-parity outliers are all intrinsically
 * foil items whose glint is a <em>time-animated</em> scroll; a single static frame can never align
 * with the harness's arbitrary-time capture. This tool instead drives <b>both</b> sides off one
 * deterministic glint-time schedule ({@code t_N = N * STEP_MILLIS}) so frame {@code N} here is
 * phase-aligned with frame {@code N} of the harness, and a per-frame diff becomes meaningful.
 *
 * <p>The harness references are produced by {@code renderVanillaGlintReferences} (the
 * {@code GlintSweep} glint-only sweep) into
 * the harness reference tree's {@code glint/<id>/frame_NNN.png}, with the harness's
 * {@code GlintTexturingMixin} forcing the same {@code t_N} per frame. This side renders the base item
 * icon with the glint suppressed ({@link ItemOptions#getGlintOverride()} = {@code false}), then
 * composites {@link GlintKit#applyGlintAtTimes} at the same schedule, over the glint texture read
 * through the context's missing-texture wrapper, as a render reads it.
 *
 * <p>Output per subject under {@code cache/visual/glint-parity-vanilla/<id>/}, the folder cleared
 * first so it holds this run's files alone: per-frame {@code java/} PNGs, the reference strip's own
 * files under {@code vanilla/}, a {@code contact_sheet.png} (vanilla / java / diff rows), and
 * {@code java.gif} for eyeballing the motion. A top-level {@code parity-report.tsv} ranks subjects by
 * mean per-frame ARGB delta.
 *
 * <p><b>The atlas-UV residual is expected.</b> Vanilla samples the glint through each fragment's live
 * <em>atlas</em> sprite coordinate, unknowable offline; {@link GlintKit} substitutes the normalized
 * icon coordinate. The time alignment is exact; the spatial band pattern carries a residual the
 * delta will not drive to zero.
 *
 * <p>Usage: {@code ./gradlew glintParityVanilla [-PitemId=minecraft:nether_star]}.
 */
@UtilityClass
public final class GlintParitySweep {

    /** Output directory for per-subject sub-folders plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/glint-parity-vanilla");

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Source of the harness-produced animated glint references. */
    private static final Path VANILLA_DIR = ParityPaths.references("glint");

    /** Square render size - matches the harness {@code refharness.size} default. */
    private static final int RENDER_SIZE = 512;

    /** Frames per subject. MUST match the harness {@code GlintSweep.FRAME_COUNT}. */
    private static final int FRAME_COUNT = 30;

    /** Glint-time step (vanilla post-{@code glintSpeed} millis) per frame. MUST match the harness {@code GlintSweep.STEP_MILLIS}. */
    private static final long STEP_MILLIS = 1_000L;

    /** Display delay per GIF frame - decoupled from the glint-time schedule, just a watchable speed. */
    private static final int GIF_FRAME_DELAY_MS = 66;

    /** Subject A: the 7 always-foil GUI items (item glint, {@code enchanted_glint_item}, scale 8.0). */
    private static final List<String> GUI_GLINT_ITEMS = List.of(
        "minecraft:enchanted_book",
        "minecraft:written_book",
        "minecraft:enchanted_golden_apple",
        "minecraft:experience_bottle",
        "minecraft:nether_star",
        "minecraft:debug_stick",
        "minecraft:end_crystal"
    );

    /**
     * Subject B (diagnostic): the 4 worn leather-armor pieces (armor glint,
     * {@code enchanted_glint_armor}, scale 0.16). Rendered armor-only over a transparent skin so just
     * the glinting leather shows. <b>Not a parity measurement</b> - the Java {@link PlayerRenderer}
     * uses the block-icon pose ({@code 30/225/0}) + hard-coded cubes while the harness uses the
     * entity pose ({@code 210/45/0}) + vanilla {@code ModelPart}s on an {@code armor_stand}, so the
     * silhouettes never align. Exists to exercise the armor-glint path and eyeball the sheen.
     */
    private static final List<String> LEATHER_ARMOR_ITEMS = List.of(
        "minecraft:leather_helmet",
        "minecraft:leather_chestplate",
        "minecraft:leather_leggings",
        "minecraft:leather_boots"
    );

    /** A 64x64 fully-transparent PNG used as the skin so only the worn armor renders. */
    private static final byte[] TRANSPARENT_SKIN = transparentSkin();

    /**
     * Runs the animated glint parity sweep over a context of its own, loaded over vanilla's own client options.
     *
     * @param args what {@link #run} reads
     * @throws IOException if the output cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        run(RendererContext.load(SweepPair.acquire(ClientOptions.defaults())), args);
    }

    /**
     * Runs the animated glint parity sweep.
     *
     * @param context the context every render resolves through, loaded over vanilla's own client options
     * @param args {@code args[0]} optional comma-separated list of item ids; absent renders all
     *     {@link #GUI_GLINT_ITEMS}
     * @throws IOException if an output directory or file cannot be written
     */
    static void run(@NotNull RendererContext context, String @NotNull [] args) throws IOException {
        List<String> filter = args.length > 0 ? List.of(args[0].split(",")) : List.of();
        List<String> allSubjects = Stream.concat(GUI_GLINT_ITEMS.stream(), LEATHER_ARMOR_ITEMS.stream()).toList();
        List<String> subjects = filter.isEmpty()
            ? allSubjects
            : allSubjects.stream().filter(filter::contains).toList();
        if (subjects.isEmpty()) {
            System.err.printf("No matching glint subjects for filter %s (known: %s)%n", filter, allSubjects);
            return;
        }

        boolean haveVanilla = Files.isDirectory(VANILLA_DIR);
        if (!haveVanilla)
            System.err.printf("Glint reference directory missing: %s%n  Run renderVanillaGlintReferences first."
                + " Rendering Java-only frames + GIFs.%n", VANILLA_DIR.toAbsolutePath());
        Files.createDirectories(OUTPUT_DIR);

        ItemRenderer renderer = new ItemRenderer(context);

        long[] schedule = new long[FRAME_COUNT];
        for (int n = 0; n < FRAME_COUNT; n++) schedule[n] = n * STEP_MILLIS;

        Map<String, GlintKit.SpriteUv> atlasUv = loadAtlasUv();

        // Parallel across independent subjects: each writes only its own folder, and the context and
        // the renderer are read-only. The collect keeps encounter order, so the table below is the one
        // a sequential loop writes.
        List<Row> rows = subjects.parallelStream()
            .map(itemId -> {
                try {
                    return renderSubject(context, renderer, itemId, schedule, atlasUv);
                } catch (Exception ex) {
                    System.err.printf("       %-32s FAILED: %s%n", itemId, ex.getMessage());
                    return new Row(itemId, 0, Double.POSITIVE_INFINITY, -1, false, LEATHER_ARMOR_ITEMS.contains(itemId));
                }
            })
            .collect(Collectors.toCollection(ArrayList::new));

        rows.sort(SweepReport.byDelta(Row::meanDelta));
        List<String> lines = new ArrayList<>(rows.size());
        for (Row r : rows)
            lines.add(String.join("\t",
                r.itemId(), SweepReport.delta(r.meanDelta()), SweepReport.status(r.meanDelta()),
                Integer.toString(r.frames()), Integer.toString(r.worstFrame()),
                Boolean.toString(r.vanillaPresent()), Boolean.toString(r.diagnostic())));
        SweepReport.write(REPORT_FILE, SweepReport.KEY_COLUMN
            + "\tmean_argb_delta\tstatus\tframes\tworst_frame\tvanilla_present\tdiagnostic", lines);
        SweepReport.printBuckets(rows.stream().mapToDouble(Row::meanDelta).toArray());

        System.out.printf("%nWrote %s (%d subjects)%n", REPORT_FILE, rows.size());
        List<Row> worst = rows.stream()
            .sorted((a, b) -> Double.compare(b.meanDelta(), a.meanDelta()))
            .toList();
        System.out.println("Mean per-frame ARGB delta (worst first; armor rows are diagnostic, not parity):");
        for (Row r : worst)
            System.out.printf("    %-32s mean %8.2f  %s%n", r.itemId(), r.meanDelta(),
                !r.vanillaPresent() ? "(java-only, no vanilla refs)" : r.diagnostic() ? "(diagnostic: pose/model differ)" : "");
    }

    /**
     * Renders one subject's Java glint frames, pairs them with the harness frames when present, and
     * writes the two strips, the contact sheet and the GIF into the subject's folder, cleared first.
     */
    private static @NotNull Row renderSubject(
        @NotNull RendererContext context,
        @NotNull ItemRenderer renderer,
        @NotNull String itemId,
        long @NotNull [] schedule,
        @NotNull Map<String, GlintKit.SpriteUv> atlasUv
    ) throws IOException {
        boolean armor = LEATHER_ARMOR_ITEMS.contains(itemId);
        String safeName = itemId.replace(":", "__");
        Path dir = OUTPUT_DIR.resolve(safeName);
        SweepStrip.clear(dir);
        Files.createDirectories(dir.resolve("java"));

        List<BufferedImage> javaFrames = armor
            ? renderJavaArmorFrames(context, itemId, schedule)
            : renderJavaGlintFrames(context, renderer, itemId, schedule, Optional.ofNullable(atlasUv.get(itemId)));
        List<BufferedImage> vanillaFrames = readVanillaFrames(VANILLA_DIR.resolve(safeName));
        boolean haveVanilla = vanillaFrames != null;

        List<BufferedImage> diffFrames = haveVanilla ? new ArrayList<>() : List.of();
        double totalDelta = 0;
        double worstDelta = -1;
        int worstFrame = -1;

        for (int n = 0; n < FRAME_COUNT; n++) {
            BufferedImage java = javaFrames.get(n);
            ImageIO.write(java, "PNG", dir.resolve("java").resolve(SweepStrip.frameName(n)).toFile());
            if (!haveVanilla) continue;

            BufferedImage vanilla = vanillaFrames.get(n);
            diffFrames.add(PixelBuffer.wrap(vanilla).diff(PixelBuffer.wrap(java), DiffType.OVER_WHITE).toBufferedImage());

            double delta = ParityMetrics.compareImages(vanilla, java).meanDelta();
            totalDelta += delta;
            if (delta > worstDelta) { worstDelta = delta; worstFrame = n; }
        }

        SweepStrip.writeGif(dir.resolve("java.gif"), javaFrames, GIF_FRAME_DELAY_MS);
        if (haveVanilla) {
            SweepStrip.copyFrames(VANILLA_DIR.resolve(safeName), dir.resolve("vanilla"), FRAME_COUNT);
            ImageIO.write(SweepStrip.contactSheet(vanillaFrames, javaFrames, diffFrames, FRAME_COUNT), "PNG",
                dir.resolve("contact_sheet.png").toFile());
        }

        double mean = haveVanilla ? totalDelta / FRAME_COUNT : Double.NaN;
        System.out.printf("  %-32s frames %d  mean delta %s  worst frame %d%s%n",
            itemId, FRAME_COUNT, haveVanilla ? String.format("%.2f", mean) : "n/a", worstFrame,
            armor ? "  (diagnostic: pose/model differ)" : "");
        return new Row(itemId, FRAME_COUNT, haveVanilla ? mean : Double.POSITIVE_INFINITY, worstFrame, haveVanilla, armor);
    }

    /**
     * Renders the worn leather-armor diagnostic frames: {@link PlayerRenderer} draws the piece over a
     * transparent skin (armor only) at the GUI 3D pose, then {@link GlintKit} composites the armor
     * glint ({@code enchanted_glint_armor}, scale 0.16) at the shared schedule. The armor piece is
     * unenchanted so the player render is the pre-glint base.
     */
    private static @NotNull List<BufferedImage> renderJavaArmorFrames(
        @NotNull RendererContext context,
        @NotNull String itemId,
        long @NotNull [] schedule
    ) {
        ArmorPiece leather = ArmorPiece.of(ArmorMaterial.LEATHER);
        PlayerOptions.Builder builder = PlayerOptions.builder()
            .type(PlayerOptions.Type.FULL)
            .dimension(PlayerOptions.Dimension.THREE_D)
            .skin(SkinOptions.builder()
                .skin(TextureOptions.builder().bytes(Optional.of(TRANSPARENT_SKIN)).build())
                .renderOverlay(false)
                .build())
            .output(OutputOptions.builder()
                .antiAlias(false)
                .canvasSize(RENDER_SIZE)
                .build());
        ArmorOptions.Builder armor = ArmorOptions.builder();
        switch (itemId) {
            case "minecraft:leather_helmet" -> armor.helmet(Optional.of(leather));
            case "minecraft:leather_chestplate" -> armor.chestplate(Optional.of(leather));
            case "minecraft:leather_leggings" -> armor.leggings(Optional.of(leather));
            case "minecraft:leather_boots" -> armor.boots(Optional.of(leather));
            default -> throw new IllegalArgumentException("not a leather armor id: " + itemId);
        }
        builder.armor(armor.build());
        PixelBuffer base = PixelBuffer.wrap(new PlayerRenderer(context).render(builder.build()).image().toPixelBuffer().toBufferedImage());

        PixelBuffer glintTexture = context.withMissingTexture().resolveTexture(GlintKit.ARMOR_GLINT_TEXTURE_ID).get();
        ConcurrentList<PixelBuffer> frames = GlintKit.applyGlintAtTimes(
            base, glintTexture, schedule, GlintKit.GlintOptions.armorDefault(30), null);

        // Each PNG holds premultiplied colour as the harness's do: a frame's held ints are its bytes.
        List<BufferedImage> out = new ArrayList<>(frames.size());
        for (PixelBuffer frame : frames) out.add(PixelBuffer.ofStraight(frame.data(), frame.width(), frame.height()).toBufferedImage());
        return out;
    }

    /** Builds a 64x64 fully-transparent PNG (all zero ARGB) so a player render shows armor only. */
    private static byte[] transparentSkin() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "PNG", bos);
            return bos.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Renders the base item icon with glint suppressed, then composites {@link GlintKit} at the
     * shared glint-time schedule (item glint texture + {@link GlintKit.GlintOptions#itemDefault}). In
     * atlas-UV validation mode the glint samples the real atlas {@code UV0}, passed separately to
     * {@link GlintKit#applyGlintAtTimes}, so the only unknown is removed and every other factor
     * (scroll, rotation, sampling, blend, alpha) can be checked for bit-parity; otherwise the offline
     * approximation runs.
     */
    private static @NotNull List<BufferedImage> renderJavaGlintFrames(
        @NotNull RendererContext context,
        @NotNull ItemRenderer renderer,
        @NotNull String itemId,
        long @NotNull [] schedule,
        @NotNull Optional<GlintKit.SpriteUv> spriteUv
    ) {
        ItemOptions baseOptions = ItemOptions.builder()
            .itemId(itemId)
            .type(ItemOptions.Type.GUI_2D)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(RENDER_SIZE).build())
            .glintOverride(Optional.of(false))
            .build();
        PixelBuffer base = PixelBuffer.wrap(renderer.render(baseOptions).image().toPixelBuffer().toBufferedImage());

        PixelBuffer glintTexture = context.withMissingTexture().resolveTexture(GlintKit.ITEM_GLINT_TEXTURE_ID).get();
        ConcurrentList<PixelBuffer> frames = GlintKit.applyGlintAtTimes(
            base, glintTexture, schedule, GlintKit.GlintOptions.itemDefault(30), spriteUv.orElse(null));

        // Each PNG holds premultiplied colour as the harness's do: a frame's held ints are its bytes.
        List<BufferedImage> out = new ArrayList<>(frames.size());
        for (PixelBuffer frame : frames) out.add(PixelBuffer.ofStraight(frame.data(), frame.width(), frame.height()).toBufferedImage());
        return out;
    }

    /**
     * Loads the harness-extracted per-item atlas sprite-UV rects (atlas-UV validation mode), keyed by
     * item id, from {@code atlas_uv.json} beside the glint references. Returns empty unless
     * {@code -Dasset.glint.atlasUv=true} is set, so default runs stay on the production-representative
     * offline approximation.
     */
    private static @NotNull Map<String, GlintKit.SpriteUv> loadAtlasUv() {
        if (!Boolean.getBoolean("asset.glint.atlasUv")) return Map.of();
        Path file = VANILLA_DIR.resolve("atlas_uv.json");
        if (!Files.isRegularFile(file)) {
            System.err.printf("asset.glint.atlasUv set but %s missing - run renderVanillaGlintReferences first%n", file);
            return Map.of();
        }
        Map<String, GlintKit.SpriteUv> map = new HashMap<>();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (var entry : root.entrySet()) {
                JsonObject o = entry.getValue().getAsJsonObject();
                float u0 = o.get("u0").getAsFloat();
                float v0 = o.get("v0").getAsFloat();
                float u1 = o.get("u1").getAsFloat();
                float v1 = o.get("v1").getAsFloat();
                map.put(entry.getKey(), new GlintKit.SpriteUv(u0, v0, u1, v1));
                System.out.printf("  atlas-uv %-32s u[%.5f,%.5f] v[%.5f,%.5f]  ~%.1f glint texels%n",
                    entry.getKey(), u0, u1, v0, v1, GlintKit.ITEM_SCALE * (u1 - u0) * 128f);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        System.out.printf("Atlas-UV mode ON (%d sprites)%n", map.size());
        return map;
    }

    /**
     * Reads a subject's reference strip.
     *
     * @param dir the subject's reference directory
     * @return the {@link #FRAME_COUNT} frames, or {@code null} where the set is incomplete, which is
     *     what renders the subject java-only
     * @throws IOException if a frame cannot be read
     */
    private static @Nullable List<BufferedImage> readVanillaFrames(@NotNull Path dir) throws IOException {
        return SweepStrip.readFrames(dir, FRAME_COUNT).orElse(null);
    }

    /** Per-subject row in the TSV report. */
    private record Row(@NotNull String itemId, int frames, double meanDelta, int worstFrame, boolean vanillaPresent, boolean diagnostic) {}
}
