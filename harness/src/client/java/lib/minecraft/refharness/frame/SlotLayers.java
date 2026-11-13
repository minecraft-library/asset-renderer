package lib.minecraft.refharness.frame;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.refharness.mixin.ItemStackRenderStateAccessor;
import lib.minecraft.refharness.mixin.LayerRenderStateAccessor;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Reads the layers the client builds for a stack in a slot - what {@link ItemModelResolver#updateForTopItem}
 * fills an {@link ItemStackRenderState} with for {@link ItemDisplayContext#GUI}, the state
 * {@code GuiItemAtlas} draws.
 * <p>
 * A stack whose every layer is a special is one the client draws through special renderers alone - a
 * chest, a bed, a banner, a head, a copper golem statue - each layer posed by its special's own
 * {@code transformation} and its base's {@code display.gui}, which no other draw reproduces. The block
 * sweep draws such a block's reference as that slot drawing, through {@link ItemFrameRenderer}.
 * <p>
 * The state's layers are read through {@link ItemStackRenderStateAccessor} and
 * {@link LayerRenderStateAccessor}, which the client checks when it loads the harness's mixins, so a
 * field the client renames fails the boot rather than routing every such block to another drawing.
 */
@Parity(claim = "harness-block-sweep", mode = Mode.DEMOTE)
@UtilityClass
public final class SlotLayers {

    /**
     * Answers whether the client draws a stack in a slot through special renderers alone: it builds at
     * least one layer for the stack, and every layer it builds draws with a special renderer.
     *
     * @param client the active client, source of the item model resolver and the level
     * @param stack the stack
     * @return whether every slot layer of the stack is a special
     */
    public static boolean specialsAlone(Minecraft client, ItemStack stack) {
        if (stack.isEmpty()) return false;

        ItemStackRenderState rendered = new ItemStackRenderState();
        client.getItemModelResolver().updateForTopItem(rendered, stack, ItemDisplayContext.GUI, client.level, /*owner*/ null, /*seed*/ 0);
        ItemStackRenderStateAccessor state = (ItemStackRenderStateAccessor) (Object) rendered;
        int count = state.refharness$activeLayerCount();
        if (count == 0) return false;

        ItemStackRenderState.LayerRenderState[] layers = state.refharness$layers();
        for (int i = 0; i < count; i++)
            if (((LayerRenderStateAccessor) (Object) layers[i]).refharness$specialRenderer() == null) return false;

        return true;
    }

}
