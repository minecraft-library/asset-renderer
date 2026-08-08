package lib.minecraft.renderer.pipeline.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.pipeline.pack.BlockStateLoader.ApplyDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Pins the entry vanilla draws from an authored weighted variants array when the draw has no world
 * position behind it - the block an entity holds or carries.
 * <p>
 * The draw is arithmetic rather than a choice this renderer makes, so it is pinned as a value: vanilla
 * seeds with a constant and takes {@code nextInt(entryCount)}, which for the four-entry arrays that
 * dominate the shipped blockstates lands on the <b>third</b> entry. A carried grass block is the
 * visible consequence - its four entries are the same model at {@code y} 0 / 90 / 180 / 270, so the
 * third is the one whose top face reads a half turn round from the first. Nothing else in the corpus
 * can show it, because the other carried blocks author a single variant.
 * <p>
 * These are pinned so that swapping the random source, or losing the seed, fails here rather than
 * silently as a moved parity row.
 */
@DisplayName("No-position weighted variant draw")
class NoPositionVariantDrawTest {

    @Test
    @DisplayName("a four-entry array draws its third entry")
    void fourEntryArrayDrawsThird() {
        assertThat(BlockIndexBuilder.drawWithoutPosition(applies("a", "b", "c", "d")).model(), is("c"));
    }

    @Test
    @DisplayName("a carried grass block draws the y=180 entry, not the authored first")
    void grassBlockDrawsHalfTurn() {
        ConcurrentList<ApplyDto> spin = Concurrent.newList();
        for (int y : new int[]{0, 90, 180, 270})
            spin.add(new ApplyDto("minecraft:block/grass_block", 0, y, false, Concurrent.newList()));

        assertThat(BlockIndexBuilder.drawWithoutPosition(spin).y(), is(180));
    }

    @Test
    @DisplayName("the draw is stable across calls - the seed is a constant, not a stream")
    void drawIsStable() {
        ConcurrentList<ApplyDto> entries = applies("a", "b", "c", "d");
        String first = BlockIndexBuilder.drawWithoutPosition(entries).model();

        for (int call = 0; call < 8; call++)
            assertThat(BlockIndexBuilder.drawWithoutPosition(entries).model(), is(first));
    }

    @Test
    @DisplayName("the draw spans the whole array, so each shipped array length answers differently")
    void drawScalesWithEntryCount() {
        // The three lengths 26.1 ships: 2 (twice), 4 (55 arrays), and netherrack's 16.
        assertThat(BlockIndexBuilder.drawWithoutPosition(sized(2)).model(), is("1"));
        assertThat(BlockIndexBuilder.drawWithoutPosition(sized(4)).model(), is("2"));
        assertThat(BlockIndexBuilder.drawWithoutPosition(sized(16)).model(), is("11"));
    }

    private static ConcurrentList<ApplyDto> sized(int entries) {
        ConcurrentList<ApplyDto> applies = Concurrent.newList();
        for (int index = 0; index < entries; index++)
            applies.add(new ApplyDto(String.valueOf(index), 0, 0, false, Concurrent.newList()));
        return applies;
    }

    private static ConcurrentList<ApplyDto> applies(String... models) {
        ConcurrentList<ApplyDto> entries = Concurrent.newList();
        for (String model : models)
            entries.add(new ApplyDto(model, 0, 0, false, Concurrent.newList()));
        return entries;
    }

}
