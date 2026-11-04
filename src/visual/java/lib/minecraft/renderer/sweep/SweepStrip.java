package lib.minecraft.renderer.sweep;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.AnimatedImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.frame.FrameBlend;
import dev.simplified.image.frame.FrameDisposal;
import dev.simplified.image.frame.ImageFrame;
import dev.simplified.image.frame.LoopCount;
import dev.simplified.image.option.GifWriteOptions;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The plumbing the animated sweeps share: a strip's frame files, the GIF of a strip and the contact
 * sheet of a row.
 *
 * <p>A strip is {@code frame_000.png} onwards in one directory. A row's {@code vanilla/} strip is the
 * reference strip's own files, copied, and its {@code java/} strip is the render's frames; a row keeps
 * no per-frame diff files, because its contact sheet draws every diff frame and both strips it is
 * computed from are on disk beside it.
 */
@UtilityClass
final class SweepStrip {

    /** Longest edge (px) of one frame cell in a contact sheet. */
    private static final int CONTACT_CELL = 96;

    /** The background a contact sheet is drawn over. */
    private static final @NotNull Color SHEET_BACKGROUND = new Color(28, 28, 32);

    /** The colour a contact sheet's row labels are drawn in. */
    private static final @NotNull Color SHEET_LABEL = new Color(220, 220, 230);

    /**
     * Returns the file name of one frame of a strip.
     *
     * @param frame the frame index
     * @return {@code frame_NNN.png}
     */
    static @NotNull String frameName(int frame) {
        return String.format("frame_%03d.png", frame);
    }

    /**
     * Deletes a subject's folder and everything in it, so what the next run writes there is all the
     * folder holds - a strip that shrank leaves no tail of an earlier run's frames behind.
     *
     * @param directory the subject's folder, which need not exist
     * @throws IOException if a file cannot be deleted
     */
    static void clear(@NotNull Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
                Files.delete(path);
        }
    }

    /**
     * Reads {@code frame_000.png} through {@code frame_(N-1).png}.
     *
     * @param directory the strip directory
     * @param frames how many frames the strip carries
     * @return the frames in order, or empty where one is absent or is not an image
     * @throws IOException if a frame cannot be read
     */
    static @NotNull Optional<List<BufferedImage>> readFrames(@NotNull Path directory, int frames) throws IOException {
        List<BufferedImage> out = new ArrayList<>(frames);
        for (int frame = 0; frame < frames; frame++) {
            Path png = directory.resolve(frameName(frame));
            if (!Files.isRegularFile(png)) return Optional.empty();
            BufferedImage image = ImageIO.read(png.toFile());
            if (image == null) return Optional.empty();
            out.add(image);
        }
        return Optional.of(out);
    }

    /**
     * Copies a reference strip's frame files into a row's strip directory as they are.
     *
     * @param from the reference strip directory
     * @param to the row's strip directory, created where absent
     * @param frames how many frames the strip carries
     * @throws IOException if a frame cannot be copied
     */
    static void copyFrames(@NotNull Path from, @NotNull Path to, int frames) throws IOException {
        Files.createDirectories(to);
        for (int frame = 0; frame < frames; frame++)
            Files.copy(from.resolve(frameName(frame)), to.resolve(frameName(frame)), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Writes an animated GIF of a strip: infinite loop, transparent background.
     *
     * <p><b>Each frame clears the canvas behind it rather than painting over what was there.</b> A
     * GIF frame carries its own disposal and the default is to leave the previous one standing,
     * which is right for an opaque strip and wrong for every transparent one: the subject is drawn
     * on nothing, so the pixels it does not cover are transparent, and a viewer showing the frame
     * before through them accumulates every pose the subject has held. It reads as smearing rather
     * than as a missing clear, which is why it survives a look at the PNGs.
     *
     * @param out the file to write
     * @param frames the frames, in order
     * @param delayMs how long each frame shows
     * @throws IOException if the write fails
     */
    static void writeGif(@NotNull Path out, @NotNull List<BufferedImage> frames, int delayMs) throws IOException {
        AnimatedImageData.Builder builder = AnimatedImageData.builder()
            .withWidth(frames.getFirst().getWidth())
            .withHeight(frames.getFirst().getHeight())
            .withLoopCount(LoopCount.Preset.INFINITE);
        for (BufferedImage frame : frames)
            builder.withFrame(ImageFrame.of(PixelBuffer.wrap(frame), delayMs, 0, 0,
                FrameDisposal.RESTORE_TO_BACKGROUND, FrameBlend.SOURCE));

        GifWriteOptions options = GifWriteOptions.builder()
            .withLoopCount(LoopCount.Preset.INFINITE)
            .isTransparent(true)
            .withAlphaThreshold(8)
            .build();
        Files.write(out, ImageFactory.toByteArray(builder.build(), ImageFormat.GIF, options));
    }

    /**
     * Builds a contact sheet: three labelled rows (vanilla / java / diff) a line, at most
     * {@code columns} cells a line, the strip wrapping onto as many lines as it needs. Each cell is
     * scaled to fit {@link #CONTACT_CELL} with its aspect kept - an entity canvas is not square, and a
     * squashed cell reads as a pose difference.
     *
     * @param vanilla the reference frames
     * @param java the rendered frames
     * @param diff the per-frame diffs
     * @param columns the most cells one line holds
     * @return the sheet
     */
    static @NotNull BufferedImage contactSheet(
        @NotNull List<BufferedImage> vanilla,
        @NotNull List<BufferedImage> java,
        @NotNull List<BufferedImage> diff,
        int columns
    ) {
        int gap = 2;
        int leftMargin = 64;
        int rowH = CONTACT_CELL + gap;
        int cells = vanilla.size();
        int lineCells = Math.min(cells, columns);
        int lines = (cells + columns - 1) / columns;
        String[] labels = {"vanilla", "java", "diff"};
        List<List<BufferedImage>> rows = List.of(vanilla, java, diff);
        int width = leftMargin + lineCells * (CONTACT_CELL + gap) + gap;
        int height = lines * rows.size() * rowH + gap;
        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        try {
            g.setColor(SHEET_BACKGROUND);
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for (int line = 0; line < lines; line++) {
                for (int row = 0; row < rows.size(); row++) {
                    int y = gap + (line * rows.size() + row) * rowH;
                    g.setColor(SHEET_LABEL);
                    g.drawString(labels[row], 4, y + CONTACT_CELL / 2);
                    List<BufferedImage> strip = rows.get(row);
                    for (int column = 0; column < columns; column++) {
                        int frame = line * columns + column;
                        if (frame >= strip.size()) break;
                        BufferedImage cell = strip.get(frame);
                        float fit = Math.min(CONTACT_CELL / (float) cell.getWidth(),
                            CONTACT_CELL / (float) cell.getHeight());
                        int w = Math.max(1, Math.round(cell.getWidth() * fit));
                        int h = Math.max(1, Math.round(cell.getHeight() * fit));
                        int x = leftMargin + column * (CONTACT_CELL + gap) + (CONTACT_CELL - w) / 2;
                        g.drawImage(cell, x, y + (CONTACT_CELL - h) / 2, w, h, null);
                    }
                }
            }
        } finally {
            g.dispose();
        }
        return sheet;
    }

}
