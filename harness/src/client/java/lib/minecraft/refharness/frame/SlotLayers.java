package lib.minecraft.refharness.frame;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * Reads the layers the client builds for a stack in a slot - what {@link ItemModelResolver#updateForTopItem}
 * fills an {@link ItemStackRenderState} with for {@link ItemDisplayContext#GUI}, the state
 * {@code GuiItemAtlas} draws.
 * <p>
 * A stack whose every layer is a special is one the client draws through special renderers alone - a
 * chest, a bed, a banner, a head, a copper golem statue - each layer posed by its special's own
 * {@code transformation} and its base's {@code display.gui}, which no other draw reproduces. The block
 * sweep draws such a block's reference as that slot drawing, through {@link ItemFrameRenderer}.
 */
@Parity(claim = "harness-block-sweep", mode = Mode.DEMOTE)
@UtilityClass
public final class SlotLayers {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /** The number of layers a render state holds, {@code ItemStackRenderState.activeLayerCount}. */
    private static final Field ACTIVE_LAYER_COUNT = field(ItemStackRenderState.class, "activeLayerCount");

    /** A render state's layers, {@code ItemStackRenderState.layers}. */
    private static final Field LAYERS = field(ItemStackRenderState.class, "layers");

    /** The special renderer a layer draws with, null for a layer of quads, {@code LayerRenderState.specialRenderer}. */
    private static final Field SPECIAL_RENDERER = field(ItemStackRenderState.LayerRenderState.class, "specialRenderer");

    /**
     * Answers whether the client draws a stack in a slot through special renderers alone: it builds at
     * least one layer for the stack, and every layer it builds draws with a special renderer.
     *
     * @param client the active client, source of the item model resolver and the level
     * @param stack the stack
     * @return whether every slot layer of the stack is a special, false where its layers cannot be read
     */
    public static boolean specialsAlone(Minecraft client, ItemStack stack) {
        if (stack.isEmpty() || ACTIVE_LAYER_COUNT == null || LAYERS == null || SPECIAL_RENDERER == null) return false;

        try {
            ItemStackRenderState rendered = new ItemStackRenderState();
            client.getItemModelResolver().updateForTopItem(rendered, stack, ItemDisplayContext.GUI, client.level, /*owner*/ null, /*seed*/ 0);
            int count = ACTIVE_LAYER_COUNT.getInt(rendered);
            ItemStackRenderState.LayerRenderState[] layers = (ItemStackRenderState.LayerRenderState[]) LAYERS.get(rendered);
            if (count == 0) return false;

            for (int i = 0; i < count; i++)
                if (SPECIAL_RENDERER.get(layers[i]) == null) return false;

            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("SlotLayers: the slot layers of {} cannot be read", stack, e);
            return false;
        }
    }

    /**
     * Opens a private field of a client class, or answers null where the class declares none of that
     * name, so a layout change degrades the read rather than failing the class's load.
     *
     * @param owner the class declaring it
     * @param name the field's name
     * @return the field, accessible, or null where there is none
     */
    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | RuntimeException e) {
            LOG.warn("SlotLayers: no readable field '{}' on {}; no stack reads as drawn by specials alone", name, owner.getName(), e);
            return null;
        }
    }

}
