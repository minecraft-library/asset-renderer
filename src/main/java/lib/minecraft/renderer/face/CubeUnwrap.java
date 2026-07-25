package lib.minecraft.renderer.face;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Reads one face of a cube out of the sheet its own atlas unwrap addresses, for the consumers that
 * paint a box from six cropped images rather than from interpolated UVs.
 *
 * <p>Where {@link SkinFace} carries a fixed table of rectangles for the six body parts of a player
 * skin, this derives the rectangle from the cube itself - its {@code uv} origin, its size and its
 * mirror flag - through {@link EntityFace#defaultUv}, which is the same unwrap the entity body path
 * resolves its UVs from. A cube whose strip happens to land where the skin table says is cropped
 * identically either way; one whose does not is cropped correctly rather than not at all.
 *
 * <p>The crop is a nearest-neighbour resample rather than a rectangle copy, because a vanilla cube's
 * size need not be a whole number of texels - the baby armor shell's waist is {@code 5.9 x 2 x 2.9}.
 * Each destination texel is sampled at its own centre, which is exactly a rectangle copy whenever
 * the strip is whole-numbered and the closest thing to vanilla's own sampling when it is not.
 */
@UtilityClass
public class CubeUnwrap {

    /**
     * Crops one face of a cube out of a sheet.
     *
     * @param sheet the source image
     * @param uv the cube's texture origin in pixels
     * @param size the cube's extent along each axis in model units
     * @param mirror whether the cube's unwrap is mirrored left to right
     * @param face the model-frame face to crop
     * @return a new buffer holding that face's texels, transparent where the strip falls off the sheet
     */
    public static @NotNull PixelBuffer crop(
        @NotNull PixelBuffer sheet,
        @NotNull Vector2f uv,
        @NotNull Vector3f size,
        boolean mirror,
        @NotNull EntityFace face
    ) {
        // A mirrored cube reads the opposite side's strip on the two side faces and reads every
        // strip right to left, which is the same pair of moves the skin table's legacy left-limb
        // fallback makes.
        Vector4f rect = face.mirror(mirror).defaultUv(uv, size);
        float u0 = rect.x();
        float v0 = rect.y();
        float uSpan = rect.z() - u0;
        float vSpan = rect.w() - v0;
        int width = Math.max(1, Math.round(uSpan));
        int height = Math.max(1, Math.round(vSpan));

        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int sy = (int) Math.floor(v0 + (y + 0.5f) * vSpan / height);
            for (int x = 0; x < width; x++) {
                int sx = (int) Math.floor(u0 + (x + 0.5f) * uSpan / width);
                if (sx < 0 || sx >= sheet.width() || sy < 0 || sy >= sheet.height()) continue;
                pixels[y * width + (mirror ? width - 1 - x : x)] = sheet.getPixel(sx, sy);
            }
        }
        return PixelBuffer.of(pixels, width, height);
    }

}
