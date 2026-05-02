package lib.minecraft.renderer.visual;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.exception.AssetPipelineException;
import lib.minecraft.renderer.options.ItemOptions;
import lib.minecraft.renderer.pipeline.AssetPipeline;
import lib.minecraft.renderer.pipeline.AssetPipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.util.PackDownloader;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostic task that proves resource pack overlay handling end-to-end. Downloads the Defrosted
 * 16x pack into {@code cache/texturepacks/} on first run, builds two pipelines (vanilla-only and
 * vanilla-plus-Defrosted), renders the same set of items / tools / armor through both, and
 * writes a side-by-side comparison PNG per id under {@code cache/visual/pack-overlay/}.
 * <p>
 * Defrosted is a plain texture-replacement pack at vanilla resolution - every override lands in
 * {@code assets/minecraft/textures/} so each item visibly differs from vanilla. Earlier prototype
 * runs against Hypixel+ produced near-identical renders because that pack drives most overrides
 * through NBT-conditional item-definitions the headless renderer doesn't evaluate.
 * <p>
 * Usage: {@code ./gradlew :asset-renderer:packOverlay [-PrenderSize=256]}
 */
@UtilityClass
public final class TestPackOverlay {

    private static final @NotNull String DEFROSTED_URL =
        "https://cdn.modrinth.com/data/NPzwNDRa/versions/kdfFWoXg/%21%20%20%20%20%20%C2%A73defrosted%20%C2%A78%5B%C2%A7f16x%C2%A78%5D%20%5B1.21.11%5D%20%5Bv1.4%5D.zip";

    private static final @NotNull String PACK_FILENAME = "defrosted-16x-1.21.11-v1.4.zip";

    private static final @NotNull String PACK_LABEL = "Defrosted";

    private static final @NotNull String[] COMPARE_IDS = {
        // tools
        "minecraft:diamond_sword", "minecraft:netherite_pickaxe", "minecraft:bow", "minecraft:fishing_rod",
        "minecraft:diamond_shovel", "minecraft:iron_axe", "minecraft:trident",
        // armor
        "minecraft:diamond_chestplate", "minecraft:netherite_helmet", "minecraft:iron_boots",
        "minecraft:golden_leggings", "minecraft:iron_chestplate",
        // common items the pack is likely to retexture
        "minecraft:golden_apple", "minecraft:enchanted_book", "minecraft:nether_star",
        "minecraft:ender_pearl", "minecraft:experience_bottle", "minecraft:totem_of_undying"
    };

    /**
     * Runs the pack-overlay comparison.
     *
     * @param args {@code args[0]} is an optional render cell size in pixels (defaults to 256)
     * @throws IOException if any output cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : 256;

        Path texturepacks = Path.of("cache/texturepacks");
        Files.createDirectories(texturepacks);
        Path zipFile = texturepacks.resolve(PACK_FILENAME);
        System.out.println("Ensuring " + PACK_LABEL + " pack is downloaded -> " + zipFile);
        PackDownloader.download(DEFROSTED_URL, zipFile, false);

        System.out.println("Building vanilla-only pipeline...");
        PipelineRendererContext vanilla = buildContext(Concurrent.newList());

        System.out.println("Building vanilla + " + PACK_LABEL + " pipeline...");
        ConcurrentList<File> userPacks = Concurrent.newList(zipFile.toFile());
        PipelineRendererContext packed = buildContext(userPacks);

        ItemRenderer vanillaRenderer = new ItemRenderer(vanilla);
        ItemRenderer packedRenderer = new ItemRenderer(packed);

        Path outputDir = Path.of("cache/visual/pack-overlay");
        Files.createDirectories(outputDir);

        for (String itemId : COMPARE_IDS) {
            String safeName = itemId.replace(":", "_");
            System.out.printf("Rendering pair for %s at %dx%d...%n", itemId, size, size);
            try {
                BufferedImage left = render(vanillaRenderer, itemId, size);
                BufferedImage right = render(packedRenderer, itemId, size);
                BufferedImage composite = sideBySide(left, right, "vanilla", PACK_LABEL, itemId, size);
                File outputFile = outputDir.resolve(safeName + ".png").toFile();
                ImageIO.write(composite, "PNG", outputFile);
                System.out.println("  Wrote " + outputFile.getAbsolutePath());
            } catch (Exception ex) {
                System.err.println("  FAILED: " + ex.getMessage());
                ex.printStackTrace(System.err);
            }
        }
    }

    /**
     * Builds a pipeline context with the given user packs and returns the rendered context. Logs
     * and rethrows pipeline failures so the caller sees a clear bootstrap error message.
     */
    private static @NotNull PipelineRendererContext buildContext(@NotNull ConcurrentList<File> userPacks) {
        AssetPipelineOptions options = AssetPipelineOptions.defaults()
            .mutate()
            .texturePacks(userPacks)
            .build();
        try {
            AssetPipeline.Result result = new AssetPipeline().run(options);
            return PipelineRendererContext.of(result);
        } catch (AssetPipelineException ex) {
            System.err.println("Pipeline bootstrap failed: " + ex.getMessage());
            throw ex;
        }
    }

    /**
     * Renders one item to a {@link BufferedImage} via the supplied renderer using
     * {@link ItemOptions.Type#GUI_2D} - the same path the {@code itemRender2D} task uses for
     * pixel-level comparability.
     */
    private static @NotNull BufferedImage render(@NotNull ItemRenderer renderer, @NotNull String itemId, int size) {
        ItemOptions options = ItemOptions.builder()
            .itemId(itemId)
            .type(ItemOptions.Type.GUI_2D)
            .outputSize(size)
            .build();
        ImageData image = renderer.render(options);
        return image.toBufferedImage();
    }

    /**
     * Composites two equal-sized renders horizontally with a label strip beneath each cell and a
     * shared title strip below. Background is dark grey so transparent armor / tool sprites are
     * still visible.
     */
    private static @NotNull BufferedImage sideBySide(
        @NotNull BufferedImage left,
        @NotNull BufferedImage right,
        @NotNull String leftLabel,
        @NotNull String rightLabel,
        @NotNull String title,
        int cellSize
    ) {
        int labelStrip = Math.max(20, cellSize / 12);
        int titleStrip = Math.max(20, cellSize / 12);
        int width = cellSize * 2;
        int height = cellSize + labelStrip + titleStrip;
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setColor(new Color(40, 40, 40));
            g.fillRect(0, 0, width, height);

            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(left, 0, 0, cellSize, cellSize, null);
            g.drawImage(right, cellSize, 0, cellSize, cellSize, null);

            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.LIGHT_GRAY);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, labelStrip - 6)));
            FontMetrics fm = g.getFontMetrics();
            int labelY = cellSize + (labelStrip + fm.getAscent()) / 2 - 2;
            drawCentered(g, leftLabel, 0, cellSize, labelY, fm);
            drawCentered(g, rightLabel, cellSize, cellSize, labelY, fm);

            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(10, titleStrip - 6)));
            fm = g.getFontMetrics();
            int titleY = cellSize + labelStrip + (titleStrip + fm.getAscent()) / 2 - 2;
            drawCentered(g, title, 0, width, titleY, fm);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * Draws a single line of text horizontally centred inside the rectangle starting at
     * {@code (x, baselineY)} with width {@code width}.
     */
    private static void drawCentered(
        @NotNull Graphics2D g,
        @NotNull String text,
        int x,
        int width,
        int baselineY,
        @NotNull FontMetrics fm
    ) {
        int textWidth = fm.stringWidth(text);
        g.drawString(text, x + (width - textWidth) / 2, baselineY);
    }

}
