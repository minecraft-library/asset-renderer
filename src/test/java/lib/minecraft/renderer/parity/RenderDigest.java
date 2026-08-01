package lib.minecraft.renderer.parity;

import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Reduces a render to the byte-exact fingerprint a pin is taken over.
 *
 * <p>Four copies of the CRC and three of the frame extraction existed across the pin tests, agreeing
 * by inspection. They are one function each here, because the four values they produce are stored in
 * one artifact family and compared against each other's history: a copy that drifted would not fail,
 * it would re-baseline.
 *
 * <p><b>Little-endian, over the ARGB ints, unchanged.</b> The byte order is not a detail that can be
 * tidied - eight promoted CRC32s were taken under it, and reversing it would move every one of them
 * while changing nothing about any render.
 */
public final class RenderDigest {

    private RenderDigest() {}

    /**
     * Returns the first frame's full ARGB pixel array.
     *
     * @param image the render
     * @return its first frame's pixels
     */
    public static int @NotNull [] firstFramePixels(@NotNull ImageData image) {
        return pixels(image.getFrames().getFirst().pixels());
    }

    /**
     * Returns a buffer's full ARGB pixel array.
     *
     * @param buffer the pixel buffer
     * @return its pixels
     */
    public static int @NotNull [] pixels(@NotNull PixelBuffer buffer) {
        return buffer.getPixels(0, 0, buffer.width(), buffer.height(), null, 0, 0);
    }

    /**
     * Returns the CRC32 of the little-endian ARGB int pixels.
     *
     * @param pixels the ARGB pixels
     * @return the CRC32 as an unsigned value in a long
     */
    public static long crc32(int @NotNull [] pixels) {
        ByteBuffer bytes = ByteBuffer.allocate(pixels.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int pixel : pixels) bytes.putInt(pixel);
        CRC32 crc = new CRC32();
        crc.update(bytes.array());
        return crc.getValue();
    }

    /**
     * Returns one CRC32 per frame, in frame order.
     *
     * @param image the animation
     * @return its per-frame CRC32s
     */
    public static @NotNull List<Long> frameCrcs(@NotNull ImageData image) {
        return image.getFrames().stream().map(frame -> crc32(pixels(frame.pixels()))).toList();
    }

}
