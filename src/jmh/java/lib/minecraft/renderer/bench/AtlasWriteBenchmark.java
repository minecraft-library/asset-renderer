package lib.minecraft.renderer.bench;

import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import lib.minecraft.renderer.AtlasRenderer;
import lib.minecraft.renderer.call.request.AtlasOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * The atlas written to a PNG file, end to end - the work the {@code generateAtlas} task does: every
 * block and item tile rendered and the sheet encoded into one file. Beside {@link AtlasBakeBenchmark},
 * which hands the sheet back in memory, this measures the file a caller wants in the end, so a change
 * to how the sheet reaches the file reads against the same work.
 * <p>
 * The trial's setup renders nothing, so the smallest heap that writes the atlas is the write's own
 * rather than a setup bake's. Each invocation overwrites one temporary file the trial deletes, and the
 * trial prints the file's size as a {@code # footprint} line when it ends. Run:
 * <pre>{@code
 * ./gradlew jmh -PjmhInclude=AtlasWriteBenchmark -PjmhProfilers=lib.minecraft.renderer.bench.CpuMemoryProfiler
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class AtlasWriteBenchmark extends AbstractRendererBenchmark {

    /** Atlas renderer bound to the trial's pipeline context. */
    private AtlasRenderer renderer;

    /** Write options - {@link AtlasOptions#defaults()}, held across all invocations. */
    private AtlasOptions options;

    /** The temporary file every invocation writes the atlas into. */
    private Path output;

    @Override
    protected void onSetupTrial() {
        this.renderer = new AtlasRenderer(context());
        this.options = AtlasOptions.defaults();

        try {
            this.output = Files.createTempFile("atlas-write-benchmark", ".png");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Override
    protected void onTearDownTrial() {
        try {
            if (Files.exists(this.output))
                System.out.printf("# footprint file=%.1f MB%n", Files.size(this.output) / CpuMemoryProfiler.MB);
            Files.deleteIfExists(this.output);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Benchmark
    public long writeFullAtlas() throws IOException {
        new ImageFactory().toFile(this.renderer.render(this.options).image(), ImageFormat.PNG, this.output.toFile());
        return Files.size(this.output);
    }

}
