package lib.minecraft.renderer.content.pack;

import dev.simplified.annotations.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * What a PNG declares of itself in its first chunk, read before any of the image is decoded.
 *
 * <p>A PNG opens with an eight-byte signature and then its header chunk, which gives the image's width
 * and height in a fixed place. The client's image reader validates exactly that much before it decodes -
 * the signature, then a first chunk of the header's length and the header's type - and refuses a file
 * that fails it, so no image of any other format is a texture to the client. Reading the same bytes
 * here gives the pixel count an image would decode to for the cost of twenty-four bytes, which is what
 * lets a read refuse an image over its limit before the decoder allocates anything for it.
 */
@UtilityClass
final class PngHeader {

    /** The eight bytes every PNG opens with. */
    private static final long SIGNATURE = 0x89504E470D0A1A0AL;

    /** The length the header chunk declares. */
    private static final int HEADER_LENGTH = 13;

    /** The header chunk's type, {@code IHDR}. */
    private static final int HEADER_TYPE = 0x49484452;

    /** The bytes the signature, the header chunk's length and type, and the width and height span. */
    private static final int SPAN = 24;

    /**
     * Tests an image's bytes against the header the client's reader requires and a ceiling on its pixels.
     *
     * @param bytes the image file's bytes
     * @param pixels the most pixels the image may declare, its width by its height
     * @return why the image is refused - it does not open as a PNG, or it declares more pixels than the
     *     ceiling - or empty where it is neither, which leaves whether it decodes to the decoder
     */
    static @NotNull Optional<String> refusal(byte @NotNull [] bytes, long pixels) {
        if (bytes.length < SPAN) return Optional.of("PNG header missing");
        if (longAt(bytes, 0) != SIGNATURE) return Optional.of("bad PNG signature");
        if (intAt(bytes, 8) != HEADER_LENGTH) return Optional.of("bad length for IHDR chunk");
        if (intAt(bytes, 12) != HEADER_TYPE) return Optional.of("bad type for IHDR chunk");

        // Both are unsigned in the file, so a value past the signed range is a size no ceiling admits.
        long width = intAt(bytes, 16) & 0xFFFFFFFFL;
        long height = intAt(bytes, 20) & 0xFFFFFFFFL;
        if (width * height > pixels)
            return Optional.of(String.format("%d x %d is more than the limit of %d pixels", width, height, pixels));

        return Optional.empty();
    }

    private static long longAt(byte @NotNull [] bytes, int at) {
        return ((long) intAt(bytes, at) << 32) | (intAt(bytes, at + 4) & 0xFFFFFFFFL);
    }

    private static int intAt(byte @NotNull [] bytes, int at) {
        return ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16) | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
    }

}
