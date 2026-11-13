package lib.minecraft.refharness.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Opens the special renderer one layer of an item stack render state draws with, which vanilla keeps
 * private to the layer's own draw.
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface LayerRenderStateAccessor {

    /**
     * The special renderer the layer draws with.
     *
     * @return the renderer, or {@code null} for a layer of quads
     */
    @Accessor("specialRenderer")
    @Nullable SpecialModelRenderer<Object> refharness$specialRenderer();
}
