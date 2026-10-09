package lib.minecraft.renderer.content.index;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.call.result.Substitution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of {@link SubstitutionCollector}: it keeps each stand-in once, answers what it holds sorted
 * by the natural order whatever order the adds came in, keeps every add of many threads adding at once,
 * and hands back a copy no caller can write through. {@link SubstitutionCollector#DISCARD} keeps nothing.
 */
@DisplayName("A substitution collector keeps each stand-in once, sorted, from any thread")
class SubstitutionCollectorTest {

    @Test
    @DisplayName("a stand-in added many times is kept once")
    void keepsARepeatOnce() {
        SubstitutionCollector collector = new SubstitutionCollector();
        for (int i = 0; i < 3; i++)
            collector.add(Substitution.texture("minecraft:block/stone", Possible.State.ABSENT));

        assertThat(collector.snapshot(), is(List.of(Substitution.texture("minecraft:block/stone", Possible.State.ABSENT))));
    }

    @Test
    @DisplayName("the snapshot is sorted whatever order the stand-ins were added in")
    void snapshotsSorted() {
        List<Substitution> sorted = List.of(
            Substitution.texture("minecraft:block/a", Possible.State.EMPTY),
            Substitution.texture("minecraft:block/b", Possible.State.ABSENT),
            Substitution.subject("minecraft:a"),
            Substitution.leafModel("minecraft:item/a", "minecraft:stick"),
            Substitution.citModel("minecraft:item/a", "minecraft:stick"),
            Substitution.special("mod:a", "minecraft:stick"));
        SubstitutionCollector collector = new SubstitutionCollector();
        sorted.reversed().forEach(collector::add);

        assertThat(collector.snapshot(), is(sorted));
    }

    @Test
    @DisplayName("every add of many threads adding at once is kept, once")
    void keepsEveryConcurrentAdd() throws Exception {
        List<Substitution> distinct = IntStream.range(0, 4_000)
            .mapToObj(i -> Substitution.texture("minecraft:block/collector_test_" + i, Possible.State.ABSENT))
            .sorted()
            .toList();
        SubstitutionCollector collector = new SubstitutionCollector();
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            List<Future<?>> adds = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                List<Substitution> order = new ArrayList<>(distinct);
                Collections.shuffle(order, new Random(thread));
                adds.add(pool.submit(() -> {
                    start.await();
                    order.forEach(collector::add);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> add : adds)
                add.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(collector.snapshot(), is(distinct));
    }

    @Test
    @DisplayName("the snapshot is a copy no caller can write through")
    void snapshotsACopy() {
        SubstitutionCollector collector = new SubstitutionCollector();
        collector.add(Substitution.subject("minecraft:a"));
        ConcurrentList<Substitution> first = collector.snapshot();
        collector.add(Substitution.subject("minecraft:b"));

        assertThat("a later add leaves an earlier snapshot alone", first, is(List.of(Substitution.subject("minecraft:a"))));
        assertThrows(UnsupportedOperationException.class, () -> first.add(Substitution.subject("minecraft:c")));
    }

    @Test
    @DisplayName("the discarding collector keeps nothing")
    void theDiscardingCollectorKeepsNothing() {
        SubstitutionCollector.DISCARD.add(Substitution.subject("minecraft:collector_test_discarded"));

        assertThat(SubstitutionCollector.DISCARD.snapshot(), is(empty()));
    }

}
