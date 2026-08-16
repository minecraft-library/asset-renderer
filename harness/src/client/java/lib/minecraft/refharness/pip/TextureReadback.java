package lib.minecraft.refharness.pip;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.simplified.annotations.UtilityClass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The asynchronous colour read-back every offscreen render ends with - copy the texture into a
 * mapped buffer, flip it onto image rows, write the PNG.
 *
 * <p>There is one copy of this loop because the size it reads must be fixed before the copy is
 * queued. {@code copyTextureToBuffer}'s callback runs on a later frame, by which time the target
 * that owns the texture may have resized or released it, and reading its size then yields a
 * zero-sized image rather than a wrong one. Taking the extent as parameters is what makes that
 * unrepresentable: a caller cannot hand in a field that is re-read later.
 *
 * <p>The rows are flipped because these textures are bottom-up like an OpenGL framebuffer, and the
 * alpha channel is carried through rather than composited, so a transparent background stays
 * transparent.
 */
@UtilityClass
public final class TextureReadback {

    /**
     * Copies a colour texture back off the GPU and writes it as a PNG.
     *
     * @param colorTexture the texture to read
     * @param width its width in pixels, as it was allocated
     * @param height its height in pixels, as it was allocated
     * @param debugName the label the GPU device tags the staging buffer with
     * @param outputPath where to write; parent directories are created on demand
     * @throws IOException if the PNG write fails
     */
    public static void writeToPng(
        GpuTexture colorTexture, int width, int height, String debugName, Path outputPath
    ) throws IOException {
        Files.createDirectories(outputPath.getParent());

        final int pixelSize = colorTexture.getFormat().pixelSize();
        long byteSize = (long) width * height * pixelSize;
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
            () -> "refharness-" + debugName + "-readback", /*usage*/ 9, byteSize);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        IOException[] err = new IOException[1];

        encoder.copyTextureToBuffer(colorTexture, buffer, 0L, () -> {
            try (GpuBuffer.MappedView read = encoder.mapBuffer(buffer, true, false);
                 NativeImage image = new NativeImage(width, height, false)) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int argb = read.data().getInt((x + y * width) * pixelSize);
                        image.setPixelABGR(x, height - y - 1, argb);
                    }
                }
                image.writeToFile(outputPath);
            } catch (IOException ex) {
                err[0] = ex;
            } finally {
                buffer.close();
            }
        }, /*mipLevel*/ 0);

        if (err[0] != null) throw err[0];
    }
}
