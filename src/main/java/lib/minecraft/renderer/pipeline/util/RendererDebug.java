package lib.minecraft.renderer.pipeline.util;

import dev.simplified.image.pixel.BlendMode;
import lib.minecraft.renderer.geometry.Box;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Single entry point for the renderer's parity-debug surface. Every diagnostic toggle and trace
 * print used by the iso-pose / canvas-fit / per-pixel rasterizer hunts routes through here so
 * production call sites are one-liners and the toggles are all visible together at the top of
 * one file.
 *
 * <p>Three orthogonal diagnostic channels live here.
 *
 * <p><b>Pixel trace</b> ({@code -Dentity.pixel.dump=x0,y0,x1,y1}, inclusive on all four sides).
 * Emits one TSV {@code [PX]} line per fragment whose screen position lands inside the rect, plus
 * one {@code [PX]\tTRI\t...} line per triangle. Single-pixel probes use
 * {@code -Dentity.pixel.dump=33,18,33,18}; widen the rect for a swath. Multi-threaded
 * rasterization interleaves output order; each line is atomic and self-contained so offline
 * tooling can sort and group.
 *
 * <p><b>Canvas-fit trace</b> ({@code -Dentity.fit.dump=true}). Emits {@code [PX]\tFIT},
 * {@code [PX]\tBASE-BOUNDS}, and {@code [PX]\tOVERLAY-BOUNDS} lines from
 * {@code EntityRenderer.computeCanvasFit} so canvas-size mismatches can be traced back to the
 * specific overlay that contributed the outlier bounds.
 *
 * <p><b>Per-polygon screen-bounds trace</b> (thread-local, bracketed per entity via
 * {@link #beginPerEntityBoundsDump(String)} / {@link #endPerEntityBoundsDump(String)} and
 * gated on {@code -Dentity.bounds.dump=true}). Emits {@code [BD]} lines from
 * {@code EntityGeometryKit.contributeFaceAlphaTight} with the polygon's UV bbox, opaque-texel
 * bbox, and the four bilinear-interpolated 3D screen corners. The parity sweep flips it on per
 * entity so the dump only fires for the entity currently being investigated, then back off so
 * subsequent entities don't drown the log. Mirrors
 * {@code EntityFrameRenderer.dumpPolygonExtents} in the vanilla-reference-harness.
 */
@UtilityClass
public final class RendererDebug {

    /**
     * Per-pixel trace rectangle parsed from {@code -Dentity.pixel.dump=x0,y0,x1,y1}, inclusive on
     * all four sides. {@code null} when the property is absent or malformed.
     */
    private static final int @Nullable [] PIXEL_DUMP_RECT = parsePixelDumpRect();

    /** Canvas-fit bounds dump toggle, parsed once at class init. */
    private static final boolean FIT_DUMP_ENABLED = Boolean.getBoolean("entity.fit.dump");

    /**
     * Per-polygon screen-bounds trace toggle. Per-thread so the parity sweep can flip it on for
     * the entity currently being investigated without affecting concurrent workers.
     */
    private static final ThreadLocal<Boolean> BOUNDS_DUMP = ThreadLocal.withInitial(() -> false);

    /** Header columns emitted at startup when the pixel-dump rect parses cleanly. */
    private static final @NotNull String PIXEL_DUMP_HEADER = String.join("\t",
        "stage", "px", "py", "depth", "tag", "u", "v", "tx", "ty",
        "rawARGB", "tintARGB", "afterTintARGB", "shading", "afterShadeARGB",
        "blendMode", "outARGB");

    private static int @Nullable [] parsePixelDumpRect() {
        String prop = System.getProperty("entity.pixel.dump");
        if (prop == null || prop.isBlank()) return null;
        String[] parts = prop.split(",");
        if (parts.length != 4) {
            System.out.println("[PX] malformed entity.pixel.dump: '" + prop + "' (expected x0,y0,x1,y1)");
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
            System.out.println("[PX] malformed entity.pixel.dump numbers: '" + prop + "'");
            return null;
        }
    }

    private static @NotNull String hexArgb(int argb) {
        return String.format("0x%08X", argb);
    }

    // --- pixel trace ---

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
                                   int rawTexel, int tintArgb, int afterTint,
                                   float shading, int afterShade,
                                   @NotNull BlendMode blendMode, int outArgb) {
        if (!pixelDumpContains(px, py)) return;
        System.out.println("[PX]\tWRITE\t" + px + "\t" + py + "\t" + depthVal
            + "\t" + tag
            + "\t" + u + "\t" + v + "\t" + tx + "\t" + ty
            + "\t" + hexArgb(rawTexel)
            + "\t" + hexArgb(tintArgb)
            + "\t" + hexArgb(afterTint)
            + "\t" + shading
            + "\t" + hexArgb(afterShade)
            + "\t" + blendMode
            + "\t" + hexArgb(outArgb));
    }

    /**
     * Logs a single {@code [PX]\tTRI} per-triangle projection trace line. Fires unconditionally
     * when the pixel-dump rectangle is configured so offline tooling can join the per-pixel
     * fragment trace to the projecting triangle.
     */
    public static void pixelTriangle(@NotNull VisibleTriangle source,
                                      @NotNull Vector2f s0, @NotNull Vector2f s1, @NotNull Vector2f s2,
                                      @NotNull Vector3f p0, @NotNull Vector3f p1, @NotNull Vector3f p2) {
        if (PIXEL_DUMP_RECT == null || source.debugTag() == null) return;
        System.out.println("[PX]\tTRI\t" + source.debugTag()
            + "\ts0=" + s0.x() + "," + s0.y() + "\ts1=" + s1.x() + "," + s1.y()
            + "\ts2=" + s2.x() + "," + s2.y()
            + "\tp0=" + p0.x() + "," + p0.y() + "," + p0.z()
            + "\tp1=" + p1.x() + "," + p1.y() + "," + p1.z()
            + "\tp2=" + p2.x() + "," + p2.y() + "," + p2.z()
            + "\tuv0=" + source.uv0().x() + "," + source.uv0().y()
            + "\tuv1=" + source.uv1().x() + "," + source.uv1().y()
            + "\tuv2=" + source.uv2().x() + "," + source.uv2().y());
    }

    // --- canvas-fit trace ---

    /** Logs the {@code [PX]\tFIT} family-union screen-bounds trace line. */
    public static void fitBounds(@NotNull String entityId, @NotNull Box screenBounds) {
        if (!FIT_DUMP_ENABLED) return;
        System.out.println("[PX]\tFIT\t" + entityId
            + "\tminX=" + screenBounds.minX() + "\tmaxX=" + screenBounds.maxX()
            + "\tminY=" + screenBounds.minY() + "\tmaxY=" + screenBounds.maxY());
    }

    /** Logs the {@code [PX]\tBASE-BOUNDS} primary-model bounds line. */
    public static void baseBounds(@NotNull Box bounds) {
        if (!FIT_DUMP_ENABLED) return;
        System.out.println("[PX]\tBASE-BOUNDS\tminX=" + bounds.minX() + "\tmaxX=" + bounds.maxX()
            + "\tminY=" + bounds.minY() + "\tmaxY=" + bounds.maxY());
    }

    /** Logs the {@code [PX]\tOVERLAY-BOUNDS} per-overlay bounds line. */
    public static void overlayBounds(@NotNull String textureRef, @NotNull Box overlayBounds) {
        if (!FIT_DUMP_ENABLED) return;
        System.out.println("[PX]\tOVERLAY-BOUNDS\tref=" + textureRef
            + "\tminX=" + overlayBounds.minX() + "\tmaxX=" + overlayBounds.maxX()
            + "\tminY=" + overlayBounds.minY() + "\tmaxY=" + overlayBounds.maxY());
    }

    // --- per-polygon bounds trace ---

    /**
     * Brackets the bounds dump for one entity in the parity sweep. Emits the
     * {@code [BD] ===== <id> START =====} framing line and switches the per-thread bounds-dump
     * toggle on so subsequent {@code bounds*} calls fire. No-op unless
     * {@code -Dentity.bounds.dump=true} was set at JVM startup; pair every call with
     * {@link #endPerEntityBoundsDump(String)} in a {@code try / finally} so the toggle resets
     * even if the per-entity render throws.
     */
    public static void beginPerEntityBoundsDump(@NotNull String entityId) {
        if (!Boolean.getBoolean("entity.bounds.dump")) return;
        System.out.printf("[BD] ===== %s START =====%n", entityId);
        BOUNDS_DUMP.set(true);
    }

    /** Counterpart to {@link #beginPerEntityBoundsDump(String)}. Resets the toggle and emits the END framing line. */
    public static void endPerEntityBoundsDump(@NotNull String entityId) {
        if (!Boolean.getBoolean("entity.bounds.dump")) return;
        BOUNDS_DUMP.set(false);
        System.out.printf("[BD] ===== %s END =====%n", entityId);
    }

    /**
     * Builds the per-face label string used as a prefix on every bounds trace line for one
     * polygon. Returns {@code null} when the per-polygon bounds dump is disabled on this thread
     * so callers can hand the label straight to subsequent {@code bounds*} calls without
     * branching: the receiving methods short-circuit on a {@code null} label.
     */
    public static @Nullable String boundsFaceLabel(@NotNull String boneName, int cubeIndex,
                                                    @NotNull Object faceDirection,
                                                    @NotNull Vector3f origin, @NotNull Vector3f size,
                                                    float inflate, boolean mirror) {
        if (!BOUNDS_DUMP.get()) return null;
        return String.format("bone=%s cube=%d face=%s orig=(%g,%g,%g) size=(%g,%g,%g) inflate=%g mirror=%s",
            boneName, cubeIndex, faceDirection,
            origin.x(), origin.y(), origin.z(),
            size.x(), size.y(), size.z(),
            inflate, mirror);
    }

    /** Logs the {@code [BD]} degenerate-UV bounds-skip line. No-op when {@code label} is null. */
    public static void boundsDegenerateUv(@Nullable String label) {
        if (label == null) return;
        System.out.println("[BD] " + label + " DEGEN_UV");
    }

    /** Logs the {@code [BD]} non-axis-aligned UV fallback line. No-op when {@code label} is null. */
    public static void boundsNonAxisUvFallback(@Nullable String label) {
        if (label == null) return;
        System.out.println("[BD] " + label + " NON_AXIS_UV_FALLBACK_4_CORNERS");
    }

    /** Logs the {@code [BD]} missing-texture fallback line. No-op when {@code label} is null. */
    public static void boundsNoTextureFallback(@Nullable String label) {
        if (label == null) return;
        System.out.println("[BD] " + label + " NO_TEX_FALLBACK_4_CORNERS");
    }

    /** Logs the {@code [BD]} all-transparent face line. No-op when {@code label} is null. */
    public static void boundsAllTransparent(@Nullable String label, int pxMin, int pyMin, int pxMax, int pyMax) {
        if (label == null) return;
        System.out.printf("[BD] %s uv_px=%d,%d,%d,%d ALL_TRANSPARENT%n",
            label, pxMin, pyMin, pxMax, pyMax);
    }

    /**
     * Logs the full {@code [BD]} bounds trace for a contributing face: UV bbox, opaque-texel
     * sub-bbox, and the four bilinear-interpolated 3D screen corners. No-op when {@code label}
     * is null.
     */
    public static void boundsFaceContribution(@Nullable String label,
                                                int pxMin, int pyMin, int pxMax, int pyMax,
                                                int firstOpaquePx, int firstOpaquePy,
                                                int lastOpaquePx, int lastOpaquePy,
                                                @NotNull Vector3f bottomLeft, @NotNull Vector3f bottomRight,
                                                @NotNull Vector3f topRight, @NotNull Vector3f topLeft) {
        if (label == null) return;
        System.out.printf(
            "[BD] %s uv_px=%d,%d,%d,%d opaque_px=%d,%d,%d,%d screen_bl=(%g,%g,%g) screen_br=(%g,%g,%g) screen_tr=(%g,%g,%g) screen_tl=(%g,%g,%g)%n",
            label,
            pxMin, pyMin, pxMax, pyMax,
            firstOpaquePx, firstOpaquePy, lastOpaquePx, lastOpaquePy,
            bottomLeft.x(), bottomLeft.y(), bottomLeft.z(),
            bottomRight.x(), bottomRight.y(), bottomRight.z(),
            topRight.x(), topRight.y(), topRight.z(),
            topLeft.x(), topLeft.y(), topLeft.z());
    }

}
