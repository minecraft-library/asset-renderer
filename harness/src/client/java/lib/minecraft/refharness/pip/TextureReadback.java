package lib.minecraft.refharness.pip;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.simplified.annotations.UtilityClass;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 *
 * <p><b>The render thread copies a frame and moves on; the PNG is encoded off it.</b> The copy's
 * callback runs on the render thread, the same thread every subject is drawn on, so an encode there
 * would hold the next draw for as long as the encode took. The callback flips the mapped rows into an image
 * a row at a time - the bytes the texture holds, in the order the image stores them - and hands the
 * image to a small pool that encodes it with the same encoder and closes it. A full queue runs the
 * encode on the thread handing it over, which bounds how many filled images wait in memory, and
 * {@link #drain} waits the pool out before the run's exit status is read.
 */
@UtilityClass
public final class TextureReadback {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /** the read-backs and PNG writes that failed after a copy landed, across every one this run queued */
    private static final AtomicInteger FAILED_WRITES = new AtomicInteger();

    /** how many images encode at once, a quarter of the machine and never fewer than two */
    private static final int ENCODER_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 4);

    /** how many filled images may wait for an encoder before the render thread encodes one itself */
    private static final int QUEUED_IMAGES = 32;

    /**
     * The encoders a filled image is handed to. A rejected encode - a full queue, or one handed over
     * after {@link #drain} began - runs on the thread handing it over rather than being dropped, so no
     * image is lost to the pool's own state.
     */
    private static final ThreadPoolExecutor ENCODERS = new ThreadPoolExecutor(
        ENCODER_THREADS, ENCODER_THREADS, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUED_IMAGES),
        runnable -> {
            Thread thread = new Thread(runnable, "refharness-png");
            thread.setDaemon(true);
            return thread;
        },
        (runnable, executor) -> runnable.run());

    /**
     * Copies a colour texture back off the GPU and writes it as a PNG once the copy lands.
     *
     * <p>The two ways this fails are told apart by when they happen. The output directory is created
     * before the copy is queued, so one that cannot be made throws here and the caller's subject
     * fails. The PNG itself is written after the copy's callback on a later frame, after this has
     * returned and the caller has counted the subject rendered, so a write that fails there has no
     * caller to throw to: it is logged with its path and counted in {@link #failedWrites()}, which the
     * run's exit status reads.
     *
     * @param colorTexture the texture to read
     * @param width its width in pixels, as it was allocated
     * @param height its height in pixels, as it was allocated
     * @param debugName the label the GPU device tags the staging buffer with
     * @param outputPath where to write; parent directories are created on demand
     * @throws IOException if the output directory cannot be created
     */
    public static void writeToPng(
        GpuTexture colorTexture, int width, int height, String debugName, Path outputPath
    ) throws IOException {
        Files.createDirectories(outputPath.getParent());

        final int pixelSize = colorTexture.getFormat().pixelSize();
        if (pixelSize != Integer.BYTES) {
            throw new IllegalStateException(String.format(
                "Texture '%s' holds %s bytes a pixel, and a PNG is written from four", debugName, pixelSize));
        }
        long rowBytes = (long) width * pixelSize;
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
            () -> "refharness-" + debugName + "-readback", /*usage*/ 9, rowBytes * height);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        encoder.copyTextureToBuffer(colorTexture, buffer, 0L, () -> {
            NativeImage image = new NativeImage(width, height, false);
            try (GpuBuffer.MappedView read = encoder.mapBuffer(buffer, true, false)) {
                long source = MemoryUtil.memAddress0(read.data());
                long target = image.getPointer();
                for (int y = 0; y < height; y++)
                    MemoryUtil.memCopy(source + y * rowBytes, target + (height - y - 1L) * rowBytes, rowBytes);
            } catch (RuntimeException ex) {
                image.close();
                LOG.error("Read-back failed for {}", outputPath, ex);
                FAILED_WRITES.incrementAndGet();
                return;
            } finally {
                buffer.close();
            }
            ENCODERS.execute(() -> encode(image, outputPath));
        }, /*mipLevel*/ 0);
    }

    /**
     * Encodes one filled image to its PNG and frees it, counting a write that fails.
     *
     * @param image the flipped image, closed here whatever happens
     * @param outputPath where to write
     */
    private static void encode(NativeImage image, Path outputPath) {
        try (image) {
            image.writeToFile(outputPath);
        } catch (IOException | RuntimeException ex) {
            LOG.error("PNG write failed for {}", outputPath, ex);
            FAILED_WRITES.incrementAndGet();
        }
    }

    /**
     * Waits for every encode handed to the pool to finish, and counts any that never ran as a failed
     * write. Called once the run is over and before its exit status is read, because the last
     * subjects' encodes are still in flight when the client stops.
     */
    public static void drain() {
        ENCODERS.shutdown();
        try {
            if (!ENCODERS.awaitTermination(5, TimeUnit.MINUTES)) {
                List<Runnable> abandoned = ENCODERS.shutdownNow();
                LOG.error("{} PNG encode(s) did not finish before the run ended", abandoned.size());
                FAILED_WRITES.addAndGet(Math.max(1, abandoned.size()));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            FAILED_WRITES.incrementAndGet();
        }
    }

    /**
     * Counts the read-backs and PNG writes that failed after a copy landed, across every one this run
     * queued - the failures {@link #writeToPng} could not throw.
     *
     * @return the count so far
     */
    public static int failedWrites() {
        return FAILED_WRITES.get();
    }
}
