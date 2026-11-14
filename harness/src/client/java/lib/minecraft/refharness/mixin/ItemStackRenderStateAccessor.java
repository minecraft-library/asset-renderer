package lib.minecraft.refharness.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Opens the layers an item stack render state holds - what {@code ItemModelResolver} fills it with for
 * one stack - so the block sweep can ask whether the client draws a stack in a slot through special
 * renderers alone. Vanilla exposes neither the count nor the array, because nothing outside the
 * state's own draw reads them.
 */
@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {

    /**
     * The number of the state's layers the resolver filled.
     *
     * @return the active layer count
     */
    @Accessor("activeLayerCount")
    int refharness$activeLayerCount();

    /**
     * The state's layers, the first {@link #refharness$activeLayerCount()} of them filled.
     *
     * @return the layer array
     */
    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] refharness$layers();
}
