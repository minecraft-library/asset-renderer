package lib.minecraft.refharness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads pixels back from the active main framebuffer (after vanilla has finished its
 * frame) and writes them as PNG. Sweep correctness depends on the suppression mixins
 * ({@code SkyMixin}, {@code HandMixin}) preventing sky / hand from being drawn so the
 * captured framebuffer contains only the subject the sweeper just placed (or spawned).
 *
 * <p>Readback follows {@code Screenshot.takeScreenshot} - the canonical 26.1
 * {@code GpuDevice.copyTextureToBuffer} path - with Y-flip and alpha preservation so
 * any {@code RenderTarget#clearColor=0} regions of the framebuffer come out as fully
 * transparent in the PNG.
 */
public final class IsoRenderer {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private IsoRenderer() {}

    /**
     * Captures the given {@link RenderTarget} (the main framebuffer in normal use) and
     * writes its color buffer to {@code outputPath} as a PNG.
     */
    public static void writeRenderTarget(RenderTarget target, Path outputPath) throws IOException {
        GpuTexture sourceTexture = target.getColorTexture();
        if (sourceTexture == null) {
            throw new IllegalStateException("RenderTarget has no color texture");
        }

        Files.createDirectories(outputPath.getParent());

        int width = target.width;
        int height = target.height;
        long byteSize = (long) width * height * sourceTexture.getFormat().pixelSize();

        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
            () -> "refharness-readback", /*usage*/ 9, byteSize);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        IOException[] err = new IOException[1];

        encoder.copyTextureToBuffer(sourceTexture, buffer, 0L, () -> {
            try {
                try (GpuBuffer.MappedView read = encoder.mapBuffer(buffer, true, false);
                     NativeImage image = new NativeImage(width, height, false)) {
                    int pixelSize = sourceTexture.getFormat().pixelSize();
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int argb = read.data().getInt((x + y * width) * pixelSize);
                            // Y-flip + preserve alpha for transparent-background pass-through.
                            image.setPixelABGR(x, height - y - 1, argb);
                        }
                    }
                    image.writeToFile(outputPath);
                } catch (IOException ex) {
                    err[0] = ex;
                }
            } finally {
                buffer.close();
            }
        }, /*mipLevel*/ 0);

        if (err[0] != null) throw err[0];
    }
}
