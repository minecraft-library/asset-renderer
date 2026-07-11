package lib.minecraft.renderer.tooling.atlas;

import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;

/**
 * The atlas tile-emptiness scan - the one consumer of {@link AtlasPolicies}
 * {@code SPARSE_CONTENT_THRESHOLD}, kept inside the atlas package so the policy stays
 * flow-local package-private while the {@code ToolingAtlasDiagnose} shell stays thin.
 *
 * <p>A single pass over a slice's pixels counts the opaque ones; the two flag signals and the
 * opaque ratio all derive from that one count.
 */
public final class AtlasScan {

    private AtlasScan() {
    }

    /**
     * The two emptiness flags plus the opaque-pixel ratio for one tile slice.
     *
     * @param fullyTransparent whether every pixel has {@code alpha == 0} (the render produced nothing)
     * @param sparseContent whether the slice rendered something but fewer than the sparse threshold of its pixels are opaque
     * @param opaqueRatio the fraction of opaque pixels, in {@code [0, 1]}
     */
    public record Result(boolean fullyTransparent, boolean sparseContent, double opaqueRatio) {}

    /**
     * Scans a tile slice for the transparent / sparse signals against
     * {@link AtlasPolicies} {@code SPARSE_CONTENT_THRESHOLD}.
     *
     * @param image the sliced tile
     * @param width the slice width in pixels
     * @param height the slice height in pixels
     * @return the flag signals plus the opaque ratio
     */
    public static @NotNull Result scan(@NotNull BufferedImage image, int width, int height) {
        int opaque = 0;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                if ((image.getRGB(x, y) >>> 24) != 0) opaque++;
        int totalPixels = width * height;
        double ratio = totalPixels == 0 ? 0.0 : (double) opaque / totalPixels;
        boolean fullyTransparent = opaque == 0;
        boolean sparseContent = !fullyTransparent && ratio < AtlasPolicies.sparseContentThreshold();
        return new Result(fullyTransparent, sparseContent, ratio);
    }

    /** The opaque-pixel fraction below which a tile is flagged {@code sparseContent} (the report metric). */
    public static double sparseContentThreshold() {
        return AtlasPolicies.sparseContentThreshold();
    }

}
