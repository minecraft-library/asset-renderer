package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Cold texture pack load benchmark - measures a fresh {@link Pipeline#run} invocation on
 * an already-cached pack root (so the network hop is excluded but all PNG decode, JSON parse,
 * and {@code ImageIO.read} work is on the critical path). Measures parallel pack-load
 * scaling.
 * <p>
 * Intentionally does <b>not</b> extend {@link AbstractRendererBenchmark} because the base class
 * would hide the very work this benchmark measures behind its {@code @Setup(Level.Trial)} hook.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class TexturePackLoadBenchmark {

    @Benchmark
    public void coldLoad(Blackhole bh) throws Exception {
        bh.consume(Pipeline.run(PipelineOptions.defaults()));
    }

}
