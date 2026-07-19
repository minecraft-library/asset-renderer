package lib.minecraft.renderer.tooling.colormap;

import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import dev.simplified.gson.node.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;

/**
 * Reads the vanilla biome colormap PNGs straight from the jar and populates the {@code maps}
 * node. Not an ASM walk - it iterates {@link ColorMapPolicies} and reads each PNG via
 * {@link ClassNodeCache#readBytes} rather than extracting the whole pack to disk.
 *
 * <p><b>Missing-file tolerant</b>: partial colormap sets are legal - an absent PNG is a
 * {@link Diagnostics#info} and no row. <b>Decode-failure fatal</b>: a present-but-corrupt PNG
 * aborts the run. Pixel contract: 256x256 ARGB, row-major, packed big-endian 4 bytes/px, base64 -
 * round-trips {@code ColorMapLoader.asIntBuffer()}.
 */
public final class ColorMapWalk {

    private ColorMapWalk() {
    }

    /**
     * Reads every declared colormap and populates {@code root}'s {@code maps} node.
     *
     * @param session the live session
     * @param root the envelope root
     */
    public static void run(@NotNull ToolingSession session, @NotNull JsonTree root) {
        ClassNodeCache cache = session.cache();
        Diagnostics diagnostics = session.diagnostics().child("maps");
        JsonTree maps = root.childArray("maps");

        int read = 0;
        for (ColorMapPolicies policy : ColorMapPolicies.values()) {
            byte[] png = cache.readBytes(policy.entryPath());
            if (png == null) {
                diagnostics.info("colormap '%s' absent - skipped (partial sets are legal)", policy.entryPath());
                continue;
            }
            String pixels = Base64.getEncoder().encodeToString(pack(decode(png, policy.entryPath())));
            maps.add(JsonTree.object()
                .put("type", policy.type().name())
                .put("source", policy.entryPath())
                .put("pixels", pixels));
            read++;
        }
        diagnostics.info("%d of %d colormaps read", read, ColorMapPolicies.values().length);
    }

    /** Decodes the PNG bytes to a row-major ARGB pixel array; a decode failure is fatal. */
    private static int @NotNull [] decode(byte @NotNull [] png, @NotNull String entryPath) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException ex) {
            throw new ToolingException(ex, "Failed to read colormap '%s'", entryPath);
        }
        if (image == null)
            throw new ToolingException("Colormap '%s' could not be decoded", entryPath);
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);
        return pixels;
    }

    /** Packs ARGB pixels into a big-endian 4-bytes/px array (round-trips {@code asIntBuffer}). */
    private static byte @NotNull [] pack(int @NotNull [] pixels) {
        ByteBuffer buffer = ByteBuffer.allocate(pixels.length * Integer.BYTES);
        buffer.asIntBuffer().put(pixels);
        return buffer.array();
    }

}
