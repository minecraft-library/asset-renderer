package lib.minecraft.renderer.visual;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.codec.gif.GifImageWriter;
import dev.simplified.image.codec.gif.GifWriteOptions;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.DiffType;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.engine.RasterEngine;
import lib.minecraft.renderer.engine.kit.GlintKit;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.options.ItemOptions;
import lib.minecraft.renderer.options.PlayerOptions;
import lib.minecraft.renderer.options.spec.ArmorOptions;
import lib.minecraft.renderer.options.spec.RenderOptions;
import lib.minecraft.renderer.options.spec.SkinOptions;
import lib.minecraft.renderer.options.spec.TextureOptions;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.request.ArmorMaterial;
import lib.minecraft.renderer.request.ArmorPiece;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Animated enchantment-glint parity tool. The remaining item-parity outliers are all intrinsically
 * foil items whose glint is a <em>time-animated</em> scroll; a single static frame can never align
 * with the harness's arbitrary-time capture. This tool instead drives <b>both</b> sides off one
 * deterministic glint-time schedule ({@code t_N = N * STEP_MILLIS}) so frame {@code N} here is
 * phase-aligned with frame {@code N} of the harness, and a per-frame diff becomes meaningful.
 *
 * <p>The harness references are produced by {@code renderVanillaGlintReferences} (the
 * {@code GlintSweeper} glint-only sweep) into
 * {@code cache/asset-renderer/vanilla/26.1/references/glint/<id>/frame_NNN.png}, with the harness's
 * {@code GlintTexturingMixin} forcing the same {@code t_N} per frame. This side renders the base item
 * icon with the glint suppressed ({@link ItemOptions#getGlintOverride()} = {@code false}), then
 * composites {@link GlintKit#applyGlintAtTimes} at the same schedule.
 *
 * <p>Output per subject under {@code cache/visual/glint-parity-vanilla/<id>/}: per-frame
 * {@code java/}, {@code vanilla/}, {@code diff/} PNGs, a {@code contact_sheet.png} (vanilla / java /
 * diff rows), and {@code java.gif} + {@code vanilla.gif} for eyeballing the motion. A top-level
 * {@code parity-report.tsv} ranks subjects by mean per-frame ARGB delta.
 *
 * <p><b>The atlas-UV residual is expected.</b> Vanilla samples the glint through each fragment's live
 * <em>atlas</em> sprite coordinate, unknowable offline; {@link GlintKit} substitutes the normalized
 * icon coordinate. The time alignment is exact; the spatial band pattern carries a residual the
 * delta will not drive to zero (see {@code notes/glint-parity.md}).
 *
 * <p>Usage: {@code ./gradlew :asset-renderer:glintParityVanilla [-PitemId=minecraft:nether_star]}.
 */
@UtilityClass
public final class TestGlintParityVanilla {

    /** Output directory for per-subject sub-folders plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/glint-parity-vanilla");

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Source of the harness-produced animated glint references. */
    private static final Path VANILLA_DIR = Path.of("cache/asset-renderer/vanilla/26.1/references/glint");

    /** Square render size - matches the harness {@code refharness.size} default. */
    private static final int RENDER_SIZE = 512;

    /** Frames per subject. MUST match the harness {@code GlintSweeper.FRAME_COUNT}. */
    private static final int FRAME_COUNT = 30;

    /** Glint-time step (vanilla post-{@code glintSpeed} millis) per frame. MUST match {@code GlintSweeper.STEP_MILLIS}. */
    private static final long STEP_MILLIS = 1_000L;

    /** Display delay per GIF frame - decoupled from the glint-time schedule, just a watchable speed. */
    private static final int GIF_FRAME_DELAY_MS = 66;

    /** Edge length (px) of one frame cell in the contact sheet. */
    private static final int CONTACT_CELL = 96;

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
     * Runs the animated glint parity sweep.
     *
     * @param args {@code args[0]} optional comma-separated list of item ids; absent renders all
     *     {@link #GUI_GLINT_ITEMS}
     * @throws IOException if an output directory or file cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
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
            System.err.printf("Glint reference directory missing: %s%n  Run :asset-renderer:renderVanillaGlintReferences first."
                + " Rendering Java-only frames + GIFs.%n", VANILLA_DIR.toAbsolutePath());
        Files.createDirectories(OUTPUT_DIR);

        Pipeline.Result result;
        try {
            result = Pipeline.run(PipelineOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("Pipeline bootstrap failed: " + ex.getMessage());
            throw ex;
        }
        PipelineRendererContext context = PipelineRendererContext.of(result);
        ItemRenderer renderer = new ItemRenderer(context);

        long[] schedule = new long[FRAME_COUNT];
        for (int n = 0; n < FRAME_COUNT; n++) schedule[n] = n * STEP_MILLIS;

        Map<String, GlintKit.SpriteUv> atlasUv = loadAtlasUv();

        List<Row> rows = new ArrayList<>();
        for (String itemId : subjects) {
            try {
                rows.add(renderSubject(context, renderer, itemId, schedule, atlasUv));
            } catch (Exception ex) {
                System.err.printf("       %-32s FAILED: %s%n", itemId, ex.getMessage());
                rows.add(new Row(itemId, 0, Double.POSITIVE_INFINITY, -1, false, LEATHER_ARMOR_ITEMS.contains(itemId)));
            }
        }

        rows.sort((a, b) -> Double.compare(b.meanDelta(), a.meanDelta()));
        StringBuilder report = new StringBuilder("item_id\tframes\tmean_argb_delta\tworst_frame\tvanilla_present\tdiagnostic\n");
        for (Row r : rows)
            report.append(String.format("%s\t%d\t%.4f\t%d\t%s\t%s%n",
                r.itemId(), r.frames(), r.meanDelta(), r.worstFrame(), r.vanillaPresent(), r.diagnostic()));
        Files.writeString(REPORT_FILE, report.toString());

        System.out.printf("%nWrote %s (%d subjects)%n", REPORT_FILE, rows.size());
        System.out.println("Mean per-frame ARGB delta (worst first; armor rows are diagnostic, not parity):");
        for (Row r : rows)
            System.out.printf("    %-32s mean %8.2f  %s%n", r.itemId(), r.meanDelta(),
                !r.vanillaPresent() ? "(java-only, no vanilla refs)" : r.diagnostic() ? "(diagnostic: pose/model differ)" : "");
    }

    /**
     * Renders one subject's Java glint frames, pairs them with the harness frames when present, and
     * writes the per-frame PNGs, contact sheet, and GIFs.
     */
    private static @NotNull Row renderSubject(
        @NotNull PipelineRendererContext context,
        @NotNull ItemRenderer renderer,
        @NotNull String itemId,
        long @NotNull [] schedule,
        @NotNull Map<String, GlintKit.SpriteUv> atlasUv
    ) throws IOException {
        boolean armor = LEATHER_ARMOR_ITEMS.contains(itemId);
        String safeName = itemId.replace(":", "__");
        Path dir = OUTPUT_DIR.resolve(safeName);
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
            ImageIO.write(java, "PNG", dir.resolve("java").resolve(frameName(n)).toFile());
            if (!haveVanilla) continue;

            BufferedImage vanilla = vanillaFrames.get(n);
            Files.createDirectories(dir.resolve("vanilla"));
            Files.createDirectories(dir.resolve("diff"));
            ImageIO.write(vanilla, "PNG", dir.resolve("vanilla").resolve(frameName(n)).toFile());

            BufferedImage diff = PixelBuffer.wrap(vanilla).diff(PixelBuffer.wrap(java), DiffType.OVER_WHITE).toBufferedImage();
            diffFrames.add(diff);
            ImageIO.write(diff, "PNG", dir.resolve("diff").resolve(frameName(n)).toFile());

            double delta = meanDelta(vanilla, java);
            totalDelta += delta;
            if (delta > worstDelta) { worstDelta = delta; worstFrame = n; }
        }

        writeGif(dir.resolve("java.gif"), javaFrames);
        if (haveVanilla) {
            writeGif(dir.resolve("vanilla.gif"), vanillaFrames);
            ImageIO.write(contactSheet(vanillaFrames, javaFrames, diffFrames), "PNG", dir.resolve("contact_sheet.png").toFile());
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
        @NotNull PipelineRendererContext context,
        @NotNull String itemId,
        long @NotNull [] schedule
    ) {
        ArmorPiece leather = ArmorPiece.of(ArmorMaterial.LEATHER);
        PlayerOptions.PlayerOptionsBuilder builder = PlayerOptions.builder()
            .type(PlayerOptions.Type.FULL)
            .dimension(PlayerOptions.Dimension.THREE_D)
            .skin(SkinOptions.builder()
                .skin(TextureOptions.builder().bytes(Optional.of(TRANSPARENT_SKIN)).build())
                .renderOverlay(false)
                .build())
            .render(RenderOptions.builder()
                .antiAlias(false)
                .outputSize(RENDER_SIZE)
                .build());
        ArmorOptions.ArmorOptionsBuilder armor = ArmorOptions.builder();
        switch (itemId) {
            case "minecraft:leather_helmet" -> armor.helmet(Optional.of(leather));
            case "minecraft:leather_chestplate" -> armor.chestplate(Optional.of(leather));
            case "minecraft:leather_leggings" -> armor.leggings(Optional.of(leather));
            case "minecraft:leather_boots" -> armor.boots(Optional.of(leather));
            default -> throw new IllegalArgumentException("not a leather armor id: " + itemId);
        }
        builder.armor(armor.build());
        PixelBuffer base = PixelBuffer.wrap(new PlayerRenderer(context).render(builder.build()).toBufferedImage());

        PixelBuffer glintTexture = new RasterEngine(context).textures().resolveTexture(GlintKit.ARMOR_GLINT_TEXTURE_ID);
        ConcurrentList<PixelBuffer> frames = GlintKit.applyGlintAtTimes(
            base, glintTexture, schedule, GlintKit.GlintOptions.armorDefault(30));

        List<BufferedImage> out = new ArrayList<>(frames.size());
        for (PixelBuffer frame : frames) out.add(frame.toBufferedImage());
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
     * shared glint-time schedule (item glint texture + {@link GlintKit.GlintOptions#itemDefault}).
     */
    private static @NotNull List<BufferedImage> renderJavaGlintFrames(
        @NotNull PipelineRendererContext context,
        @NotNull ItemRenderer renderer,
        @NotNull String itemId,
        long @NotNull [] schedule,
        @NotNull Optional<GlintKit.SpriteUv> spriteUv
    ) {
        ItemOptions baseOptions = ItemOptions.builder()
            .itemId(itemId)
            .type(ItemOptions.Type.GUI_2D)
            .render(ItemOptions.DEFAULT_RENDER.mutate().outputSize(RENDER_SIZE).build())
            .glintOverride(Optional.of(false))
            .build();
        PixelBuffer base = PixelBuffer.wrap(renderer.render(baseOptions).toBufferedImage());

        PixelBuffer glintTexture = new RasterEngine(context).textures().resolveTexture(GlintKit.ITEM_GLINT_TEXTURE_ID);
        ConcurrentList<PixelBuffer> frames = GlintKit.applyGlintAtTimes(
            base, glintTexture, schedule, itemGlintOptions(spriteUv.isPresent()), spriteUv.orElse(null));

        List<BufferedImage> out = new ArrayList<>(frames.size());
        for (PixelBuffer frame : frames) out.add(frame.toBufferedImage());
        return out;
    }

    /**
     * The item-glint preset. In atlas-UV validation mode the glint samples the real atlas {@code UV0}
     * (passed separately to {@link GlintKit#applyGlintAtTimes}) at vanilla's raw
     * {@link GlintKit#ITEM_SCALE}, so the only unknown is removed and every other factor (scroll,
     * rotation, sampling, blend, alpha) can be checked for bit-parity. Otherwise the offline
     * approximation runs, with the UV scale overridable via {@code -Dasset.glint.itemScale} for empirical
     * calibration (see {@code notes/glint-parity.md}).
     */
    private static GlintKit.@NotNull GlintOptions itemGlintOptions(boolean atlasUvMode) {
        GlintKit.GlintOptions preset = GlintKit.GlintOptions.itemDefault(30);
        // Atlas-UV mode uses vanilla's literal ITEM_SCALE; offline mode honours the calibration knob.
        float scale = atlasUvMode
            ? preset.textureScale()
            : Float.parseFloat(System.getProperty("asset.glint.itemScale", String.valueOf(preset.textureScale())));
        // -Dasset.glint.intensity scales the glint texture down via a grey pre-tint (calibration only).
        float intensity = Float.parseFloat(System.getProperty("asset.glint.intensity", "1.0"));
        int v = Math.clamp(Math.round(intensity * 255f), 0, 255);
        int tint = ColorMath.pack(255, v, v, v);
        return new GlintKit.GlintOptions(preset.framesPerSecond(), preset.totalFrames(), preset.glintTextureId(),
            scale, preset.atlasSampled(), preset.uLoopMillis(), preset.vLoopMillis(), tint);
    }

    /**
     * Loads the harness-extracted per-item atlas sprite-UV rects (atlas-UV validation mode), keyed by
     * item id, from {@code atlas_uv.json} beside the glint references. Returns empty unless
     * {@code -Dasset.glint.atlasUv=true} is set, so default runs stay on the production-representative
     * offline approximation. {@code -Dasset.glint.atlasUvFlipV=true} swaps {@code v0}/{@code v1} for a
     * one-shot check of the icon-Y vs atlas-V orientation.
     */
    private static @NotNull Map<String, GlintKit.SpriteUv> loadAtlasUv() {
        if (!Boolean.getBoolean("asset.glint.atlasUv")) return Map.of();
        Path file = VANILLA_DIR.resolve("atlas_uv.json");
        if (!Files.isRegularFile(file)) {
            System.err.printf("asset.glint.atlasUv set but %s missing - run renderVanillaGlintReferences first%n", file);
            return Map.of();
        }
        boolean flipV = Boolean.getBoolean("asset.glint.atlasUvFlipV");
        Map<String, GlintKit.SpriteUv> map = new HashMap<>();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (var entry : root.entrySet()) {
                JsonObject o = entry.getValue().getAsJsonObject();
                float u0 = o.get("u0").getAsFloat();
                float v0 = o.get("v0").getAsFloat();
                float u1 = o.get("u1").getAsFloat();
                float v1 = o.get("v1").getAsFloat();
                map.put(entry.getKey(), new GlintKit.SpriteUv(u0, flipV ? v1 : v0, u1, flipV ? v0 : v1));
                System.out.printf("  atlas-uv %-32s u[%.5f,%.5f] v[%.5f,%.5f]  ~%.1f glint texels%n",
                    entry.getKey(), u0, u1, v0, v1, GlintKit.ITEM_SCALE * (u1 - u0) * 128f);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        System.out.printf("Atlas-UV mode ON (%d sprites, flipV=%s)%n", map.size(), flipV);
        return map;
    }

    /** Reads {@code frame_000.png ... frame_(N-1).png}; returns {@code null} if the set is incomplete. */
    private static @Nullable List<BufferedImage> readVanillaFrames(@NotNull Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        List<BufferedImage> frames = new ArrayList<>(FRAME_COUNT);
        for (int n = 0; n < FRAME_COUNT; n++) {
            Path png = dir.resolve(frameName(n));
            if (!Files.isRegularFile(png)) return null;
            BufferedImage img = ImageIO.read(png.toFile());
            if (img == null) return null;
            frames.add(img);
        }
        return frames;
    }

    private static @NotNull String frameName(int n) {
        return String.format("frame_%03d.png", n);
    }

    /** Builds an animated GIF (infinite loop, transparent background) from the frame list. */
    private static void writeGif(@NotNull Path out, @NotNull List<BufferedImage> frames) throws IOException {
        AnimatedImageData.Builder builder = AnimatedImageData.builder()
            .withWidth(frames.getFirst().getWidth())
            .withHeight(frames.getFirst().getHeight())
            .withLoopCount(0);
        for (BufferedImage frame : frames)
            builder.withFrame(ImageFrame.of(PixelBuffer.wrap(frame), GIF_FRAME_DELAY_MS));

        GifWriteOptions options = GifWriteOptions.builder()
            .withLoopCount(0)
            .isTransparent(true)
            .withAlphaThreshold(8)
            .build();
        Files.write(out, new GifImageWriter().write(builder.build(), options));
    }

    /**
     * Builds a contact sheet: three labelled rows (vanilla / java / diff), one downscaled cell per
     * frame, so the whole animation is scannable in a single image.
     */
    private static @NotNull BufferedImage contactSheet(
        @NotNull List<BufferedImage> vanilla,
        @NotNull List<BufferedImage> java,
        @NotNull List<BufferedImage> diff
    ) {
        int gap = 2;
        int leftMargin = 64;
        int rowH = CONTACT_CELL + gap;
        int width = leftMargin + FRAME_COUNT * (CONTACT_CELL + gap) + gap;
        int height = 3 * rowH + gap;
        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        try {
            g.setColor(new Color(28, 28, 32));
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            String[] labels = {"vanilla", "java", "diff"};
            List<List<BufferedImage>> rowsData = List.of(vanilla, java, diff);
            for (int row = 0; row < 3; row++) {
                int y = gap + row * rowH;
                g.setColor(new Color(220, 220, 230));
                g.drawString(labels[row], 4, y + CONTACT_CELL / 2);
                List<BufferedImage> cells = rowsData.get(row);
                for (int n = 0; n < cells.size(); n++) {
                    int x = leftMargin + n * (CONTACT_CELL + gap);
                    g.drawImage(cells.get(n), x, y, CONTACT_CELL, CONTACT_CELL, null);
                }
            }
        } finally {
            g.dispose();
        }
        return sheet;
    }

    // --- diff math (mirrors TestItemParityVanilla: mean |delta| of RGB composited over white) ---

    private static double meanDelta(@NotNull BufferedImage a, @NotNull BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        long total = 0L;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                total += compositeDiff(pa, ColorMath.alpha(pa), pb, ColorMath.alpha(pb));
            }
        }
        return (double) total / ((long) w * h);
    }

    private static int compositeDiff(int pa, int aa, int pb, int ab) {
        int r = compositeOverWhite(ColorMath.red(pa), aa) - compositeOverWhite(ColorMath.red(pb), ab);
        int g = compositeOverWhite(ColorMath.green(pa), aa) - compositeOverWhite(ColorMath.green(pb), ab);
        int bl = compositeOverWhite(ColorMath.blue(pa), aa) - compositeOverWhite(ColorMath.blue(pb), ab);
        return Math.abs(r) + Math.abs(g) + Math.abs(bl);
    }

    private static int compositeOverWhite(int channel, int alpha) {
        return (channel * alpha + 255 * (255 - alpha) + 127) / 255;
    }

    /** Per-subject row in the TSV report. */
    private record Row(@NotNull String itemId, int frames, double meanDelta, int worstFrame, boolean vanillaPresent, boolean diagnostic) {}
}
