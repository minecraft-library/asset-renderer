package lib.minecraft.renderer.diagnostic;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.BlendMode;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Single entry point for the renderer's parity-debug surface. Every diagnostic toggle and trace
 * print used by the iso-pose / canvas-fit / per-pixel rasterizer hunts routes through here so
 * production call sites are one-liners and the toggles are all visible together at the top of
 * one file.
 *
 * <p>Three orthogonal diagnostic channels live here. Every one of them takes plain values - ids, tags,
 * coordinates as floats - so the channel names nothing a caller renders with, and anything can report
 * through it.
 *
 * <p><b>Pixel trace</b> ({@code -Dasset.entity.pixel.dump=x0,y0,x1,y1}, inclusive on all four sides).
 * Emits one TSV {@code [PX]} line per fragment whose screen position lands inside the rect, plus
 * one {@code [PX]\tTRI\t...} line per triangle. Single-pixel probes use
 * {@code -Dasset.entity.pixel.dump=33,18,33,18}; widen the rect for a swath. Multi-threaded
 * rasterization interleaves output order; each line is atomic and self-contained so offline
 * tooling can sort and group.
 *
 * <p><b>Canvas-fit trace</b> ({@code -Dasset.entity.fit.dump=true}). Emits {@code [PX]\tFIT},
 * {@code [PX]\tBASE-BOUNDS}, and {@code [PX]\tOVERLAY-BOUNDS} lines from
 * {@code EntityRenderer.computeCanvasFit} so canvas-size mismatches can be traced back to the
 * specific overlay that contributed the outlier bounds.
 *
 * <p><b>Per-polygon screen-bounds trace</b> (thread-local, bracketed per entity via
 * {@link #beginPerEntityBoundsDump(String)} / {@link #endPerEntityBoundsDump(String)} and
 * gated on {@code -Dasset.entity.bounds.dump=true}). Emits {@code [BD]} lines from
 * {@code EntityGeometryKit.contributeFaceAlphaTight} with the polygon's UV bbox, opaque-texel
 * bbox, and the four bilinear-interpolated 3D screen corners. The parity sweep flips it on per
 * entity so the dump only fires for the entity currently being investigated, then back off so
 * subsequent entities don't drown the log. Mirrors
 * {@code EntityFrameRenderer.dumpPolygonExtents} in the vanilla-reference-harness.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public final class DebugChannel {

    /**
     * Per-pixel trace rectangle parsed from {@code -Dasset.entity.pixel.dump=x0,y0,x1,y1}, inclusive on
     * all four sides. {@code null} when the property is absent or malformed.
     */
    private static final int @Nullable [] PIXEL_DUMP_RECT = parsePixelDumpRect();

    /**
     * Canvas-fit bounds dump toggle, parsed once at class init.
     */
    private static final boolean FIT_DUMP_ENABLED = Boolean.getBoolean("asset.entity.fit.dump");

    /**
     * Per-polygon screen-bounds trace toggle. Per-thread so the parity sweep can flip it on for
     * the entity currently being investigated without affecting concurrent workers.
     */
    private static final ThreadLocal<Boolean> BOUNDS_DUMP = ThreadLocal.withInitial(() -> false);

    /**
     * Header columns emitted at startup when the pixel-dump rect parses cleanly.
     */
    private static final @NotNull String PIXEL_DUMP_HEADER = String.join("\t",
        "stage", "px", "py", "depth", "tag", "u", "v", "tx", "ty",
        "rawARGB", "tintARGB", "shading", "afterShadeARGB",
        "blendMode", "outARGB");

    private static int @Nullable [] parsePixelDumpRect() {
        String prop = System.getProperty("asset.entity.pixel.dump");
        if (prop == null || prop.isBlank()) return null;
        String[] parts = prop.split(",");
        if (parts.length != 4) {
            System.out.println("[PX] malformed asset.entity.pixel.dump: '" + prop + "' (expected x0,y0,x1,y1)");
            return null;
        }
        try {
            int x0 = Integer.parseInt(parts[0].trim());
            int y0 = Integer.parseInt(parts[1].trim());
            int x1 = Integer.parseInt(parts[2].trim());
            int y1 = Integer.parseInt(parts[3].trim());
            System.out.println("[PX-HEADER] " + PIXEL_DUMP_HEADER);
            System.out.println("[PX] dump rect=" + x0 + "," + y0 + "-" + x1 + "," + y1);
            return new int[]{ x0, y0, x1, y1 };
        } catch (NumberFormatException nfe) {
            System.out.println("[PX] malformed asset.entity.pixel.dump numbers: '" + prop + "'");
            return null;
        }
    }

    private static @NotNull String hexArgb(int argb) {
        return String.format("0x%08X", argb);
    }

    // --- pixel trace ---

    /**
     * Whether the per-pixel trace is armed, so a kit that would have to build a triangle's debug tag,
     * or a caller that would have to gather a triangle's coordinates, can skip the work when nothing
     * will read them.
     *
     * @return true when {@code -Dasset.entity.pixel.dump} was set and parsed
     */
    public static boolean tracingPixels() {
        return PIXEL_DUMP_RECT != null;
    }

    private static boolean pixelDumpContains(int px, int py) {
        if (PIXEL_DUMP_RECT == null) return false;
        return px >= PIXEL_DUMP_RECT[0] && py >= PIXEL_DUMP_RECT[1]
            && px <= PIXEL_DUMP_RECT[2] && py <= PIXEL_DUMP_RECT[3];
    }

    /**
     * Logs a single {@code [PX]\tSKIP-FILL} fragment-rejection trace line. No-op when the
     * {@code (px, py)} sample is outside the configured pixel-dump rectangle.
     */
    public static void pixelSkipFill(int px, int py, @Nullable String tag, float bary0, float bary1, float bary2) {
        if (!pixelDumpContains(px, py)) return;
        System.out.println("[PX]\tSKIP-FILL\t" + px + "\t" + py + "\t\t" + tag
            + "\tbary=" + bary0 + "," + bary1 + "," + bary2);
    }

    /**
     * Logs a single {@code [PX]\tSKIP-DEPTH} fragment-rejection trace line. No-op when the
     * {@code (px, py)} sample is outside the configured pixel-dump rectangle.
     */
    public static void pixelSkipDepth(int px, int py, float depthVal, @Nullable String tag, float existingDepth) {
        if (!pixelDumpContains(px, py)) return;
        System.out.println("[PX]\tSKIP-DEPTH\t" + px + "\t" + py + "\t" + depthVal
            + "\t" + tag + "\texistingDepth=" + existingDepth);
    }

    /**
     * Logs a single {@code [PX]\tSKIP-ALPHA} fragment-rejection trace line. No-op when the
     * {@code (px, py)} sample is outside the configured pixel-dump rectangle.
     */
    public static void pixelSkipAlpha(int px, int py, float depthVal, @Nullable String tag,
                                       float u, float v, int tx, int ty, int rawTexel) {
        if (!pixelDumpContains(px, py)) return;
        System.out.println("[PX]\tSKIP-ALPHA\t" + px + "\t" + py + "\t" + depthVal
            + "\t" + tag
            + "\tu=" + u + "\tv=" + v + "\ttx=" + tx + "\tty=" + ty
            + "\trawARGB=" + hexArgb(rawTexel));
    }

    /**
     * Logs a single {@code [PX]\tWRITE} fragment-success trace line with the full sample chain.
     * No-op when the {@code (px, py)} sample is outside the configured pixel-dump rectangle.
     */
    public static void pixelWrite(int px, int py, float depthVal, @Nullable String tag,
                                   float u, float v, int tx, int ty,
                                   int rawTexel, int tintArgb,
                                   float shading, int afterShade,
                                   @NotNull BlendMode blendMode, int outArgb) {
        if (!pixelDumpContains(px, py)) return;
        System.out.println("[PX]\tWRITE\t" + px + "\t" + py + "\t" + depthVal
            + "\t" + tag
            + "\t" + u + "\t" + v + "\t" + tx + "\t" + ty
            + "\t" + hexArgb(rawTexel)
            + "\t" + hexArgb(tintArgb)
            + "\t" + shading
            + "\t" + hexArgb(afterShade)
            + "\t" + blendMode
            + "\t" + hexArgb(outArgb));
    }

    /**
     * Logs a single {@code [PX]\tTRI} per-triangle projection trace line - the projected screen
     * corners, world corners, and UVs - so offline tooling can join the per-pixel fragment trace to
     * its projecting triangle. Fires whenever the pixel-dump rectangle is configured and the triangle
     * carries a tag (untagged triangles are skipped); unlike the {@code pixel*} fragment lines it does
     * not gate on the rect bounds, since a triangle spans many pixels.
     *
     * @param tag the triangle's debug tag, or {@code null} for an untagged triangle
     * @param screen the three projected corners as {@code x, y} pairs, six values in corner order
     * @param world the three world-space corners as {@code x, y, z} triples, nine values in corner order
     * @param uv the three texture coordinates as {@code u, v} pairs, six values in corner order
     */
    public static void pixelTriangle(@Nullable String tag, float @NotNull [] screen,
                                     float @NotNull [] world, float @NotNull [] uv) {
        if (PIXEL_DUMP_RECT == null || tag == null) return;
        System.out.println("[PX]\tTRI\t" + tag
            + "\ts0=" + screen[0] + "," + screen[1] + "\ts1=" + screen[2] + "," + screen[3]
            + "\ts2=" + screen[4] + "," + screen[5]
            + "\tp0=" + world[0] + "," + world[1] + "," + world[2]
            + "\tp1=" + world[3] + "," + world[4] + "," + world[5]
            + "\tp2=" + world[6] + "," + world[7] + "," + world[8]
            + "\tuv0=" + uv[0] + "," + uv[1]
            + "\tuv1=" + uv[2] + "," + uv[3]
            + "\tuv2=" + uv[4] + "," + uv[5]);
    }

    // --- canvas-fit trace ---

    /**
     * Logs the {@code [PX]\tFIT} family-union screen-bounds trace line.
     *
     * @param entityId the entity the bounds were fitted for
     * @param minX the left edge of the union
     * @param maxX the right edge of the union
     * @param minY the bottom edge of the union
     * @param maxY the top edge of the union
     */
    public static void fitBounds(@NotNull String entityId, float minX, float maxX, float minY, float maxY) {
        if (!FIT_DUMP_ENABLED) return;
        System.out.println("[PX]\tFIT\t" + entityId
            + "\tminX=" + minX + "\tmaxX=" + maxX
            + "\tminY=" + minY + "\tmaxY=" + maxY);
    }

    /**
     * Logs the {@code [PX]\tBASE-BOUNDS} primary-model bounds line.
     *
     * @param minX the left edge of the primary model's bounds
     * @param maxX the right edge
     * @param minY the bottom edge
     * @param maxY the top edge
     */
    public static void baseBounds(float minX, float maxX, float minY, float maxY) {
        if (!FIT_DUMP_ENABLED) return;
        System.out.println("[PX]\tBASE-BOUNDS\tminX=" + minX + "\tmaxX=" + maxX
            + "\tminY=" + minY + "\tmaxY=" + maxY);
    }

    /**
     * Logs the {@code [PX]\tOVERLAY-BOUNDS} per-overlay bounds line.
     *
     * @param textureRef the overlay's texture ref, which names the overlay in the trace
     * @param minX the left edge of the overlay's bounds
     * @param maxX the right edge
     * @param minY the bottom edge
     * @param maxY the top edge
     */
    public static void overlayBounds(@NotNull String textureRef, float minX, float maxX, float minY, float maxY) {
        if (!FIT_DUMP_ENABLED) return;
        System.out.println("[PX]\tOVERLAY-BOUNDS\tref=" + textureRef
            + "\tminX=" + minX + "\tmaxX=" + maxX
            + "\tminY=" + minY + "\tmaxY=" + maxY);
    }

    // --- per-polygon bounds trace ---

    /**
     * Brackets the bounds dump for one entity in the parity sweep. Emits the
     * {@code [BD] ===== <id> START =====} framing line and switches the per-thread bounds-dump
     * toggle on so subsequent {@code bounds*} calls fire. No-op unless
     * {@code -Dasset.entity.bounds.dump=true} was set at JVM startup; pair every call with
     * {@link #endPerEntityBoundsDump(String)} in a {@code try / finally} so the toggle resets
     * even if the per-entity render throws.
     */
    public static void beginPerEntityBoundsDump(@NotNull String entityId) {
        if (!Boolean.getBoolean("asset.entity.bounds.dump")) return;
        System.out.printf("[BD] ===== %s START =====%n", entityId);
        BOUNDS_DUMP.set(true);
    }

    /**
     * Counterpart to {@link #beginPerEntityBoundsDump(String)}. Resets the toggle and emits the END framing line.
     */
    public static void endPerEntityBoundsDump(@NotNull String entityId) {
        if (!Boolean.getBoolean("asset.entity.bounds.dump")) return;
        BOUNDS_DUMP.set(false);
        System.out.printf("[BD] ===== %s END =====%n", entityId);
    }

    /**
     * Whether the per-polygon bounds trace is armed on this thread, so a caller that would have to
     * gather a face's coordinates for {@link #boundsFaceLabel} can skip the work when nothing will
     * read them.
     *
     * @return true inside a {@link #beginPerEntityBoundsDump(String)} bracket with the dump enabled
     */
    public static boolean tracingBounds() {
        return BOUNDS_DUMP.get();
    }

    /**
     * Builds the per-face label string used as a prefix on every bounds trace line for one
     * polygon. Returns {@code null} when the per-polygon bounds dump is disabled on this thread
     * so callers can hand the label straight to subsequent {@code bounds*} calls without
     * branching: the receiving methods short-circuit on a {@code null} label.
     *
     * @param boneName the bone the cube hangs from
     * @param cubeIndex the cube's index within its bone
     * @param faceDirection the face, printed by its own string form
     * @param origin the cube's origin as {@code x, y, z}
     * @param size the cube's size as {@code x, y, z}
     * @param grow the cube's growth as {@code x, y, z}
     * @param mirror whether the cube's UV is mirrored
     * @return the per-face label prefix, or {@code null} when the bounds dump is off on this thread
     */
    public static @Nullable String boundsFaceLabel(@NotNull String boneName, int cubeIndex,
                                                   @NotNull Object faceDirection, float @NotNull [] origin,
                                                   float @NotNull [] size, float @NotNull [] grow, boolean mirror) {
        if (!BOUNDS_DUMP.get()) return null;
        return String.format("bone=%s cube=%d face=%s orig=(%g,%g,%g) size=(%g,%g,%g) grow=(%g,%g,%g) mirror=%s",
            boneName, cubeIndex, faceDirection,
            origin[0], origin[1], origin[2],
            size[0], size[1], size[2],
            grow[0], grow[1], grow[2], mirror);
    }

    /**
     * Logs the {@code [BD]} degenerate-UV bounds-skip line. No-op when {@code label} is null.
     */
    public static void boundsDegenerateUv(@Nullable String label) {
        if (label == null) return;
        System.out.println("[BD] " + label + " DEGEN_UV");
    }

    /**
     * Logs the {@code [BD]} non-axis-aligned UV fallback line. No-op when {@code label} is null.
     */
    public static void boundsNonAxisUvFallback(@Nullable String label) {
        if (label == null) return;
        System.out.println("[BD] " + label + " NON_AXIS_UV_FALLBACK_4_CORNERS");
    }

    /**
     * Logs the {@code [BD]} missing-texture fallback line. No-op when {@code label} is null.
     */
    public static void boundsNoTextureFallback(@Nullable String label) {
        if (label == null) return;
        System.out.println("[BD] " + label + " NO_TEX_FALLBACK_4_CORNERS");
    }

    /**
     * Logs the {@code [BD]} all-transparent face line. No-op when {@code label} is null.
     */
    public static void boundsAllTransparent(@Nullable String label, int pxMin, int pyMin, int pxMax, int pyMax) {
        if (label == null) return;
        System.out.printf("[BD] %s uv_px=%d,%d,%d,%d ALL_TRANSPARENT%n",
            label, pxMin, pyMin, pxMax, pyMax);
    }

    /**
     * Logs the full {@code [BD]} bounds trace for a contributing face: UV bbox, opaque-texel
     * sub-bbox, and the four bilinear-interpolated 3D screen corners. No-op when {@code label}
     * is null.
     *
     * @param label the face's label, or {@code null} when the bounds dump is off
     * @param pxMin the UV bbox's left texel
     * @param pyMin the UV bbox's top texel
     * @param pxMax the UV bbox's right texel
     * @param pyMax the UV bbox's bottom texel
     * @param firstOpaquePx the opaque sub-bbox's left texel
     * @param firstOpaquePy the opaque sub-bbox's top texel
     * @param lastOpaquePx the opaque sub-bbox's right texel
     * @param lastOpaquePy the opaque sub-bbox's bottom texel
     * @param screenCorners the four screen corners as {@code x, y, z} triples in the order
     *     bottom-left, bottom-right, top-right, top-left - twelve values
     */
    public static void boundsFaceContribution(@Nullable String label,
                                              int pxMin, int pyMin, int pxMax, int pyMax,
                                              int firstOpaquePx, int firstOpaquePy,
                                              int lastOpaquePx, int lastOpaquePy,
                                              float @NotNull [] screenCorners) {
        if (label == null) return;
        float[] c = screenCorners;
        System.out.printf(
            "[BD] %s uv_px=%d,%d,%d,%d opaque_px=%d,%d,%d,%d screen_bl=(%g,%g,%g) screen_br=(%g,%g,%g) screen_tr=(%g,%g,%g) screen_tl=(%g,%g,%g)%n",
            label,
            pxMin, pyMin, pxMax, pyMax,
            firstOpaquePx, firstOpaquePy, lastOpaquePx, lastOpaquePy,
            c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11]);
    }

}
