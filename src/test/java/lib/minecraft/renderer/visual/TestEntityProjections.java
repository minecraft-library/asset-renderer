package lib.minecraft.renderer.visual;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.engine.camera.Facing;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * Renders one entity under every {@link Projection} in the catalog (perspective / axonometric /
 * oblique) - one cell per enum entry - and writes a labelled contact sheet ({@code <entity>.png})
 * plus per-projection cell PNGs ({@code <entity>/<projection>.png}) to
 * {@code cache/visual/entity-projections/} for eyeballing. The entity mirror of the player
 * {@code projections} sheet (see {@code TestPlayerRender}): a functional check that the entity is a
 * first-class projection subject now that its model-to-world facing lives on a {@code Placement} and
 * its camera comes from the selected projection. Each cell fits its own canvas
 * ({@link EntityOptions.FitMode#OUTPUT_SIZE}) so the whole taxonomy reads at a glance.
 *
 * <p>Usage: {@code ./gradlew :asset-renderer:entityProjections [-PentityId=minecraft:zombie] [-PrenderSize=256]}.
 */
@UtilityClass
public final class TestEntityProjections {

    /** Output root for the contact sheet + per-projection cell PNGs. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/entity-projections");

    /** Default entity - a humanoid whose face makes orientation obvious across projections. */
    private static final String DEFAULT_ENTITY = "minecraft:zombie";

    /**
     * Runs the entity-projection sweep.
     *
     * @param args {@code args[0]} optional entity id (default {@code minecraft:zombie}); {@code args[1]}
     *     optional per-cell render size (default 256)
     * @throws IOException if the output directory or a file cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        String entityId = args.length > 0 && !args[0].isBlank() ? args[0] : DEFAULT_ENTITY;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 256;

        Pipeline.Result result;
        try {
            result = Pipeline.run(PipelineOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("Pipeline bootstrap failed: " + ex.getMessage());
            throw ex;
        }
        PipelineRendererContext context = PipelineRendererContext.of(result);
        ConcurrentMap<String, Entity> javaEntities = EntityModelLoader.load();
        if (javaEntities.isEmpty()) {
            System.err.println("entity_models.json / entity_geometry.json not present - run entityModelsJava first");
            return;
        }
        EntityRenderer renderer = new EntityRenderer(context, javaEntities);

        String safe = entityId.replace(':', '_');
        Path cellDir = OUTPUT_DIR.resolve(safe);
        Files.createDirectories(cellDir);

        System.out.printf("Entity projections for %s under %d projections at %dx%d%n",
            entityId, Projection.values().length, size, size);
        List<Cell> cells = new ArrayList<>();
        for (Projection projection : Projection.values()) {
            String label = projection.name().toLowerCase();
            BufferedImage img;
            try {
                EntityOptions options = EntityOptions.builder()
                    .entityId(Optional.of(entityId))
                    .output(OutputOptions.builder()
                        .canvasSize(size)
                        .supersample(2)
                        .antiAlias(true)
                        .projection(projection)
                        .build())
                    .build();
                ImageData image = renderer.render(options);
                img = image.toBufferedImage();
                ImageIO.write(img, "PNG", cellDir.resolve(label + ".png").toFile());
                System.out.printf("  %-16s ok%n", label);
            } catch (Exception ex) {
                System.err.printf("  %-16s FAILED: %s%n", label, ex.getMessage());
                img = failureCell(size, ex);
            }
            cells.add(new Cell(label, img));
        }

        writeGrid(safe, entityId, cells, 5, size);

        // Facing sweep: a compact projection x facing grid so the mirror / flip toggles are eyeballable
        // across an ortho (VANILLA_ISO), a perspective (PORTRAIT), and both oblique shears
        // (CAVALIER diagonal, MILITARY vertical). Each row is one projection; the four columns are
        // DEFAULT / MIRRORED (M-) / FLIPPED (-F) / MIRRORED_FLIPPED (MF).
        Projection[] facingProjections = {Projection.VANILLA_ISO, Projection.PORTRAIT, Projection.CAVALIER, Projection.MILITARY};
        Facing[] facings = {Facing.DEFAULT, Facing.MIRRORED, Facing.FLIPPED, Facing.MIRRORED_FLIPPED};
        List<Cell> facingCells = new ArrayList<>();
        for (Projection projection : facingProjections)
            for (Facing facing : facings) {
                String label = projection.name().toLowerCase() + " "
                    + (facing.mirrored() ? "M" : "-") + (facing.flipped() ? "F" : "-");
                BufferedImage img;
                try {
                    EntityOptions options = EntityOptions.builder()
                        .entityId(Optional.of(entityId))
                        .output(OutputOptions.builder()
                            .canvasSize(size)
                            .supersample(2)
                            .antiAlias(true)
                            .projection(projection)
                            .facing(facing)
                            .build())
                        .build();
                    img = renderer.render(options).toBufferedImage();
                    ImageIO.write(img, "PNG", cellDir.resolve("facing_" + safe(label) + ".png").toFile());
                } catch (Exception ex) {
                    System.err.printf("  facing %-20s FAILED: %s%n", label, ex.getMessage());
                    img = failureCell(size, ex);
                }
                facingCells.add(new Cell(label, img));
            }
        writeGrid(safe + "_facing", entityId, facingCells, 4, size);
    }

    private static @NotNull String safe(@NotNull String label) {
        return label.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    /** Lays out the pre-rendered cells in a labelled grid over a checkerboard and writes the sheet PNG. */
    private static void writeGrid(@NotNull String name, @NotNull String entityId,
                                  @NotNull List<Cell> cells, int cols, int size) throws IOException {
        int cell = Math.min(size, 192);
        int gap = 4;
        int labelH = 18;
        int titleH = 24;
        int rows = (cells.size() + cols - 1) / cols;
        int width = gap + cols * (cell + gap);
        int height = titleH + gap + rows * (cell + labelH + gap);
        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        try {
            g.setColor(new Color(24, 24, 28));
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
            g.drawString("entity-projections / " + entityId + "  (" + cells.size() + " projections)", gap, 17);

            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for (int i = 0; i < cells.size(); i++) {
                int cx = gap + (i % cols) * (cell + gap);
                int cy = titleH + gap + (i / cols) * (cell + labelH + gap);
                drawChecker(g, cx, cy, cell);
                g.drawImage(cells.get(i).image(), cx, cy, cell, cell, null);
                g.setColor(new Color(210, 210, 220));
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                drawCentered(g, cells.get(i).label(), cx, cell, cy + cell + 13);
            }
        } finally {
            g.dispose();
        }
        File out = OUTPUT_DIR.resolve(name + ".png").toFile();
        ImageIO.write(sheet, "PNG", out);
        System.out.println("Wrote " + out.getAbsolutePath());
    }

    private static void drawChecker(@NotNull Graphics2D g, int x, int y, int size) {
        int s = 8;
        for (int yy = 0; yy < size; yy += s)
            for (int xx = 0; xx < size; xx += s) {
                boolean dark = ((xx / s) + (yy / s)) % 2 == 0;
                g.setColor(dark ? new Color(60, 60, 66) : new Color(86, 86, 94));
                g.fillRect(x + xx, y + yy, Math.min(s, size - xx), Math.min(s, size - yy));
            }
    }

    private static void drawCentered(@NotNull Graphics2D g, @NotNull String text, int x, int width, int baselineY) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, x + (width - fm.stringWidth(text)) / 2, baselineY);
    }

    private static @NotNull BufferedImage failureCell(int size, @NotNull Exception ex) {
        int s = Math.min(size, 192);
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(120, 30, 30));
            g.fillRect(0, 0, s, s);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g.drawString("FAILED", 6, 18);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            String msg = String.valueOf(ex.getMessage());
            g.drawString(msg.length() > 30 ? msg.substring(0, 30) : msg, 6, 34);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** A single labelled grid cell - a rendered projection of the entity. */
    private record Cell(@NotNull String label, @NotNull BufferedImage image) {}

}
