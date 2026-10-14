package lib.minecraft.renderer.bench;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import org.jetbrains.annotations.NotNull;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ScalarResult;
import org.openjdk.jmh.runner.IterationType;

import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

/**
 * A JMH profiler measuring what one benchmark operation costs in processor time and memory, beside the
 * wall time JMH reports itself.
 * <p>
 * Every figure is taken over a whole iteration, then divided by the operations the iteration ran or kept
 * as its extreme, so it reads true for an operation long enough to dwarf the few calls the profiler makes
 * around an iteration - a bake of thousands of tiles rather than a nanosecond kernel. It reports:
 * <ul>
 *     <li><b>cpu.time.norm</b> - processor time per operation, summed over every thread of the process,
 *     the collector's and the compiler's included</li>
 *     <li><b>cpu.cores</b> - how many processors the iteration kept busy on average, its processor time
 *     over its wall time</li>
 *     <li><b>cpu.util</b> - that average as a share of the processors the JVM may use</li>
 *     <li><b>mem.alloc.norm</b> - heap allocated per operation, by every thread</li>
 *     <li><b>mem.heap.base</b> - heap in use at the iteration's start, right after a full collection -
 *     what stays resident between operations</li>
 *     <li><b>mem.heap.peak</b> - the most heap occupied at any point of the iteration, garbage included,
 *     which the collector's sizing bounds as much as the workload does</li>
 *     <li><b>mem.process</b> - memory the process has committed from the operating system at the
 *     iteration's end, the heap and everything native together</li>
 *     <li><b>gc.pause.norm</b> - collection pause time per operation</li>
 *     <li><b>gc.count.norm</b> - collections per operation</li>
 * </ul>
 * <p>
 * Heap occupancy rises only by allocation and falls only in a collection, so its highest point is either
 * the moment before some collection or the iteration's end. The collectors' notifications report the
 * first, which makes {@code mem.heap.peak} the true high-water mark where the sum of every pool's own
 * peak, each reached at a different moment, overstates it. A notification arrives after the collection
 * it reports, so the profiler waits for every collection the collectors have counted before it reads
 * one. An iteration whose notifications never caught up reports no peak and a {@code mem.heap.stalled}
 * of one instead, so the run's peak is the highest of the iterations that did and the stalled count says
 * how many did not.
 * <p>
 * No figure says how much heap an operation needs. What survives a young collection includes old garbage
 * the collector has not reached yet, and the one exact reading, a full collection, would have to land
 * inside the operation it measures.
 * <p>
 * Every iteration starts from a heap the profiler has just collected in full, before its own window
 * opens. JMH opens the profilers' windows in the order they are named and closes them in reverse, so this
 * one goes first in {@code -PjmhProfilers}: named after another, its collection lands inside the other's
 * window, and {@code gc} counts it as one of the benchmark's. The first iteration of a fork also runs the
 * benchmark's trial {@code @Setup}, inside every profiler's window though outside JMH's own score, so the
 * profiler refuses a run with no warmup iteration to absorb it. Named by its class, as JMH loads a
 * profiler it does not ship:
 * <pre>{@code
 * ./gradlew jmh -PjmhInclude=AtlasBakeBenchmark -PjmhProfilers=lib.minecraft.renderer.bench.CpuMemoryProfiler,gc
 * }</pre>
 */
public final class CpuMemoryProfiler implements InternalProfiler {

    /** Bytes in the megabyte every memory figure is reported in, the binary one JMH's own profilers use. */
    static final double MB = 1024.0 * 1024.0;

    /** How long the profiler waits for the collectors' notifications to catch up with their counts. */
    private static final long NOTIFICATION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    /** How long the profiler parks between two looks at whether the notifications have caught up. */
    private static final long NOTIFICATION_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    /** The process view, for its processor time and committed memory. */
    private final @NotNull OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

    /** The thread view, for the heap every thread has allocated. */
    private final @NotNull ThreadMXBean threads = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);

    /** The heap view, for its occupancy and the full collection each iteration starts from. */
    private final @NotNull MemoryMXBean memory = ManagementFactory.getMemoryMXBean();

    /** Every collector of the JVM, each notifying the profiler of the collections it runs inside an iteration. */
    private final @NotNull List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();

    /** The names of the memory pools that make up the heap, the ones a notification's occupancy is summed over. */
    private final @NotNull Set<String> heapPools = ManagementFactory.getMemoryPoolMXBeans()
        .stream()
        .filter(pool -> pool.getType() == MemoryType.HEAP)
        .map(MemoryPoolMXBean::getName)
        .collect(Collectors.toUnmodifiableSet());

    /** The one listener added to every collector and removed from it again, held so the removal names the same object. */
    private final @NotNull NotificationListener listener = this::onCollection;

    /** Each collector's count once the profiler began listening for the iteration, below which no notification is owed. */
    private final @NotNull Map<String, Long> listenedFrom = new HashMap<>();

    /** Each collector's highest collection id a notification has reported. */
    private final @NotNull Map<String, Long> notified = new ConcurrentHashMap<>();

    /** The most heap occupied before any collection notified since the iteration started. */
    private final @NotNull AtomicLong peak = new AtomicLong();

    /** Whether no iteration has started yet, the first one being where a fork runs the trial's setup. */
    private boolean first = true;

    /** Whether the notifications caught up before the iteration started, so its peak covers only its own collections. */
    private boolean settled;

    /** Heap in use at the iteration's start, after its full collection. */
    private long startHeap;

    /** Process processor time at the iteration's start, in nanoseconds. */
    private long startCpu;

    /** Bytes every thread had allocated at the iteration's start. */
    private long startAllocated;

    /** Collections every collector had run at the iteration's start. */
    private long startCollections;

    /** Pause time every collector had spent at the iteration's start, in milliseconds. */
    private long startPause;

    /** Wall clock at the iteration's start, in nanoseconds. */
    private long startWall;

    /** {@inheritDoc} */
    @Override
    public @NotNull String getDescription() {
        return "Processor time, cores kept busy, heap allocated, heap occupied and process memory per operation";
    }

    /**
     * Starts listening to every collector, collects the heap in full and waits for that collection to be
     * notified, then takes the iteration's starting readings - the wall clock last, so the window opens on
     * the iteration itself.
     *
     * @param benchmarkParams the benchmark's parameters
     * @param iterationParams the iteration's parameters
     * @throws IllegalStateException if the fork's first iteration is a measured one, which would carry the
     *     trial's setup
     */
    @Override
    public void beforeIteration(@NotNull BenchmarkParams benchmarkParams, @NotNull IterationParams iterationParams) {
        if (this.first && iterationParams.getType() == IterationType.MEASUREMENT)
            throw new IllegalStateException("CpuMemoryProfiler needs a warmup iteration - a fork's first iteration runs the trial's setup");

        this.first = false;
        listen();
        this.memory.gc();
        this.settled = awaitNotifications();
        this.startHeap = this.memory.getHeapMemoryUsage().getUsed();
        this.peak.set(this.startHeap);

        this.startAllocated = this.threads.getTotalThreadAllocatedBytes();
        this.startCollections = collections();
        this.startPause = pauseMillis();
        this.startCpu = this.os.getProcessCpuTime();
        this.startWall = System.nanoTime();
    }

    /**
     * Takes the iteration's closing readings - the wall clock first, so the window closes on the iteration
     * itself - then waits for its collections to be notified, stops listening, and reports every figure.
     *
     * @param benchmarkParams the benchmark's parameters
     * @param iterationParams the iteration's parameters
     * @param result the iteration's result, whose operation count every per-operation figure divides by
     * @return the iteration's figures
     */
    @Override
    public @NotNull Collection<? extends Result> afterIteration(
        @NotNull BenchmarkParams benchmarkParams,
        @NotNull IterationParams iterationParams,
        @NotNull IterationResult result
    ) {
        long wall = System.nanoTime() - this.startWall;
        long cpuNow = this.os.getProcessCpuTime();
        long allocatedNow = this.threads.getTotalThreadAllocatedBytes();
        long collections = collections() - this.startCollections;
        long pause = pauseMillis() - this.startPause;
        long heapNow = this.memory.getHeapMemoryUsage().getUsed();
        long committed = this.os.getCommittedVirtualMemorySize();
        boolean heapComplete = this.settled && awaitNotifications();
        unlisten();

        long ops = result.getMetadata().getAllOps();
        double cpu = cpuNow < 0 || this.startCpu < 0 ? Double.NaN : cpuNow - this.startCpu;
        double cores = cpu / wall;
        double allocated = allocatedNow < 0 || this.startAllocated < 0 ? Double.NaN : allocatedNow - this.startAllocated;

        List<Result> results = new ArrayList<>();
        results.add(new ScalarResult("cpu.time.norm", perOp(cpu / TimeUnit.MILLISECONDS.toNanos(1), ops), "ms/op", AggregationPolicy.AVG));
        results.add(new ScalarResult("cpu.cores", cores, "cores", AggregationPolicy.AVG));
        results.add(new ScalarResult("cpu.util", 100.0 * cores / Runtime.getRuntime().availableProcessors(), "%", AggregationPolicy.AVG));
        results.add(new ScalarResult("mem.alloc.norm", perOp(allocated / MB, ops), "MB/op", AggregationPolicy.AVG));
        results.add(new ScalarResult("mem.heap.base", this.startHeap / MB, "MB", AggregationPolicy.AVG));
        // JMH zero-fills a label an iteration left out, which a MAX over occupancies never picks, so the
        // run's peak is the highest of the iterations that settled and the stalled ones are counted apart.
        if (heapComplete)
            results.add(new ScalarResult("mem.heap.peak", Math.max(this.peak.get(), heapNow) / MB, "MB", AggregationPolicy.MAX));
        else
            results.add(new ScalarResult("mem.heap.stalled", 1, "#", AggregationPolicy.SUM));
        results.add(new ScalarResult("mem.process", committed < 0 ? Double.NaN : committed / MB, "MB", AggregationPolicy.MAX));
        results.add(new ScalarResult("gc.pause.norm", perOp(pause, ops), "ms/op", AggregationPolicy.AVG));
        results.add(new ScalarResult("gc.count.norm", perOp(collections, ops), "#/op", AggregationPolicy.AVG));
        return results;
    }

    /**
     * Adds the listener to every collector, then reads each one's count - in that order, so a collection
     * finishing between the two is one the profiler hears rather than one it waits on forever.
     */
    private void listen() {
        for (GarbageCollectorMXBean collector : this.collectors) {
            if (collector instanceof NotificationEmitter emitter)
                emitter.addNotificationListener(this.listener, null, null);

            this.listenedFrom.put(collector.getName(), collector.getCollectionCount());
        }
    }

    /**
     * Removes the listener from every collector, so an instance JMH builds and never runs, or one whose
     * iterations are over, hears nothing.
     *
     * @throws IllegalStateException if a collector no longer holds the listener {@link #listen()} added
     */
    private void unlisten() {
        for (GarbageCollectorMXBean collector : this.collectors) {
            if (!(collector instanceof NotificationEmitter emitter)) continue;

            try {
                emitter.removeNotificationListener(this.listener);
            } catch (ListenerNotFoundException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    /**
     * Folds one collection's occupancy into the iteration's peak, then records it as notified - in that
     * order, so a reader that sees the collection notified sees its occupancy too.
     *
     * @param notification the notification a collector sent
     * @param handback the handback the listener was registered with, always {@code null}
     */
    private void onCollection(@NotNull Notification notification, Object handback) {
        if (!notification.getType().equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) return;

        GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
        GcInfo collection = info.getGcInfo();
        this.peak.accumulateAndGet(heapUsed(collection.getMemoryUsageBeforeGc()), Math::max);
        this.notified.merge(info.getGcName(), collection.getId(), Math::max);
    }

    /**
     * Waits until every collection each collector has counted since the profiler began listening has
     * been notified.
     *
     * @return whether the notifications caught up before the timeout
     */
    private boolean awaitNotifications() {
        long deadline = System.nanoTime() + NOTIFICATION_TIMEOUT_NANOS;
        while (!caughtUp()) {
            if (System.nanoTime() - deadline >= 0) return false;
            LockSupport.parkNanos(NOTIFICATION_POLL_NANOS);
        }
        return true;
    }

    /**
     * Answers whether every collector has notified each collection it has counted since the profiler
     * began listening.
     *
     * @return whether no notification is still owed
     */
    private boolean caughtUp() {
        for (GarbageCollectorMXBean collector : this.collectors) {
            long count = collector.getCollectionCount();
            if (count > this.listenedFrom.get(collector.getName()) && this.notified.getOrDefault(collector.getName(), 0L) < count)
                return false;
        }
        return true;
    }

    /**
     * Sums the collections every collector has run.
     *
     * @return the collection count
     */
    private long collections() {
        return this.collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }

    /**
     * Sums the pause time every collector has spent.
     *
     * @return the pause time, in milliseconds
     */
    private long pauseMillis() {
        return this.collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
    }

    /**
     * Sums the heap pools' occupancy out of one side of a collection.
     *
     * @param usage every pool's usage, by pool name
     * @return the heap in use, in bytes
     */
    private long heapUsed(@NotNull Map<String, MemoryUsage> usage) {
        return usage.entrySet()
            .stream()
            .filter(entry -> this.heapPools.contains(entry.getKey()))
            .mapToLong(entry -> entry.getValue().getUsed())
            .sum();
    }

    /**
     * Divides an iteration's total by the operations it ran.
     *
     * @param total the iteration's total
     * @param ops the operations the iteration ran
     * @return the total per operation, or {@code NaN} for an iteration that ran none
     */
    private static double perOp(double total, long ops) {
        return ops == 0 ? Double.NaN : total / ops;
    }

}
