package lib.minecraft.renderer.tooling.colormap;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Locale;

/**
 * Reads the vanilla biome colormap PNGs straight from the jar and populates the {@code maps}
 * node. Not an ASM walk - it iterates {@link ColorMapPolicies}, takes the
 * {@link Navigation.AtResource} coordinate each one answers with, and reads the PNG there via
 * {@link ClassNodeCache#readBytes} rather than extracting the whole pack to disk. A row's
 * {@code type} is the entry path's file stem uppercased, the spelling the renderer names the tint
 * target this map serves by.
 *
 * <p><b>Every declared colormap is required</b>: a missing PNG is recorded at
 * {@link Diagnostics.Severity#ERROR} and the walk then aborts, so one run names every colormap
 * the jar has lost and no short table reaches disk. <b>Decode-failure fatal</b>: a
 * present-but-corrupt PNG aborts the run. Pixel contract: 256x256 ARGB, row-major, packed
 * big-endian 4 bytes/px, base64 - round-trips {@code ColorMapLoader.asIntBuffer()}.
 */
@UtilityClass
public final class ColorMapWalk {

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
        int declared = ColorMapPolicies.values().length;
        for (ColorMapPolicies policy : ColorMapPolicies.values()) {
            AsmContext context = AsmContext.keyless(session, diagnostics);
            if (!(policy.navigate(context) instanceof Navigation.AtResource resource))
                throw new ToolingException("Colormap '%s' declares no jar resource to read", policy);
            String entryPath = resource.entryPath();
            byte[] png = cache.readBytes(entryPath);
            if (png == null) {
                diagnostics.error("colormap '%s' is declared but absent from the jar", entryPath);
                continue;
            }
            String pixels = Base64.getEncoder().encodeToString(pack(decode(png, entryPath)));
            maps.add(JsonTree.object()
                .put("type", typeOf(entryPath))
                .put("source", entryPath)
                .put("pixels", pixels));
            read++;
        }
        if (read < declared)
            throw new ToolingException(
                "Read %d of %d declared colormaps - the jar is either obfuscated or from an unsupported version",
                read, declared);
        diagnostics.info("%d of %d colormaps read", read, declared);
    }

    /** Derives the tint-target name the renderer indexes a map under - the entry path's file stem, uppercased. */
    static @NotNull String typeOf(@NotNull String entryPath) {
        String fileName = entryPath.substring(entryPath.lastIndexOf('/') + 1);
        return fileName.substring(0, fileName.lastIndexOf('.')).toUpperCase(Locale.ROOT);
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
