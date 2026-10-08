package lib.minecraft.renderer.dump;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.content.index.RendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of how the pipeline dump writes an id its key source lists but whose lookup holds no row.
 * <p>
 * The context lists every block and item it knows, the ones that draw nothing included, so the dump's
 * blocks and items sections and its {@code icon_gui} probe each walk an id the lookup answers empty.
 * Such an id is written as its state alone and has no transform to probe; an id the lookup does not
 * know at all still fails the dump, since it means the index and its key source disagree.
 * <p>
 * The context is a stub listing one id the lookups answer empty and one they answer absent, so no
 * client assets are read.
 */
@DisplayName("The pipeline dump writes an id that draws nothing as its state, and fails one nothing knows")
class PipelineParityDumpTest {

    /** An id the stub's lookups answer empty, as the client's index answers air. */
    private static final String DRAWS_NOTHING = "minecraft:air";

    /** An id the stub's lookups answer absent. */
    private static final String UNKNOWN = "minecraft:definitely_not_a_real_id";

    @Test
    @DisplayName("the blocks and items sections write an id that draws nothing as its state alone")
    void anIdDrawingNothingIsWrittenAsItsState() {
        RendererContext context = listing(DRAWS_NOTHING);

        assertThat(PipelineParityDump.blocks(context).toString(), is("{\"minecraft:air\":{\"state\":\"empty\"}}"));
        assertThat(PipelineParityDump.items(context).toString(), is("{\"minecraft:air\":{\"state\":\"empty\"}}"));
    }

    @Test
    @DisplayName("the icon_gui probe skips a block that draws nothing, which has no transform")
    void theIconGuiProbeSkipsABlockDrawingNothing() {
        assertThat(PipelineParityDump.iconGui(listing(DRAWS_NOTHING)).toString(), is("{}"));
    }

    @Test
    @DisplayName("a listed id the lookup does not know still fails both sections")
    void anUnknownListedIdStillFails() {
        RendererContext context = listing(UNKNOWN);

        IllegalStateException blocks = assertThrows(IllegalStateException.class, () -> PipelineParityDump.blocks(context));
        assertThat(blocks.getMessage(), containsString("'" + UNKNOWN + "'"));
        IllegalStateException items = assertThrows(IllegalStateException.class, () -> PipelineParityDump.items(context));
        assertThat(items.getMessage(), containsString("'" + UNKNOWN + "'"));
    }

    /**
     * Builds a context listing one id as its only block and its only item, whose lookups answer the
     * id {@link #DRAWS_NOTHING} empty and every other id absent.
     *
     * @param id the id both lists hold
     * @return the stub context
     */
    private static @NotNull RendererContext listing(@NotNull String id) {
        RendererContext inMemory = RendererContext.builder().build();
        return new RendererContext.Forwarding() {

            @Override
            public @NotNull RendererContext delegate() {
                return inMemory;
            }

            @Override
            public @NotNull Possible<Block> findBlock(@NotNull String blockId) {
                return blockId.equals(DRAWS_NOTHING) ? Possible.empty() : Possible.absent();
            }

            @Override
            public @NotNull Possible<Item> findItem(@NotNull String itemId) {
                return itemId.equals(DRAWS_NOTHING) ? Possible.empty() : Possible.absent();
            }

            @Override
            public @NotNull ConcurrentList<String> knownBlockIds() {
                return Concurrent.newUnmodifiableList(id);
            }

            @Override
            public @NotNull ConcurrentList<String> knownItemIds() {
                return Concurrent.newUnmodifiableList(id);
            }

        };
    }

}
