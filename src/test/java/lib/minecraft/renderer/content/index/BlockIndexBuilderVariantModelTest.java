package lib.minecraft.renderer.content.index;

import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of the model a blockstate variant draws, over the real vanilla corpus. A variant names its
 * model by id, and vanilla reads an id written without a namespace as {@code minecraft:}; the index
 * holds every model under its qualified id, so a variant that kept the id as written would find
 * nothing and draw the element-less stand-in an unresolved id gets.
 * <p>
 * Vanilla's {@code item_frame} and {@code glow_item_frame} are the blockstates that write their ids
 * that way, so they are the ones that show it: at {@code map=true} each draws its map frame.
 * <p>
 * Needs a real client, which it reads through the shared client-assets extension rather than
 * acquiring one of its own.
 */
@DisplayName("A blockstate variant draws the model it names")
@ExtendWith(ClientAssetsExtension.class)
class BlockIndexBuilderVariantModelTest {

    private static RendererContext context;

    @BeforeAll
    static void setup() {
        context = ClientAssetsExtension.context();
    }

    @Test
    @DisplayName("vanilla's item frame, whose blockstate names its models without a namespace, draws its map model at map=true")
    void itemFrameDrawsItsMapModel() {
        assertVariantDraws("minecraft:item_frame", "map=true", "minecraft:block/item_frame_map");
        assertVariantDraws("minecraft:item_frame", "map=false", "minecraft:block/item_frame");
    }

    @Test
    @DisplayName("vanilla's glow item frame draws its map model at map=true the same way")
    void glowItemFrameDrawsItsMapModel() {
        assertVariantDraws("minecraft:glow_item_frame", "map=true", "minecraft:block/glow_item_frame_map");
        assertVariantDraws("minecraft:glow_item_frame", "map=false", "minecraft:block/glow_item_frame");
    }

    /**
     * Asserts that a block's variant names a model id and draws that model - the same loaded instance
     * the context answers for the id, rather than the element-less stand-in an unresolved id gets.
     *
     * @param blockId the block whose variant is read
     * @param key the variant key
     * @param modelId the qualified model id the variant names
     */
    private static void assertVariantDraws(@NotNull String blockId, @NotNull String key, @NotNull String modelId) {
        Block.Variant variant = context.findBlock(blockId).orElseThrow().variants().get(key);
        String label = blockId + "[" + key + "]";

        assertThat(label + " model id", variant.modelId(), is(modelId));
        assertThat(label + " geometry", variant.geometry(), is(instanceOf(Block.ElementGeometry.class)));
        assertThat(label + " model", ((Block.ElementGeometry) variant.geometry()).model(),
            is(sameInstance(context.findItemModel(modelId).orElseThrow())));
    }

}
