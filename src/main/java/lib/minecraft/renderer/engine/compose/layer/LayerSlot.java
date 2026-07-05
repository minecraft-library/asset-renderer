package lib.minecraft.renderer.engine.compose.layer;

import org.jetbrains.annotations.NotNull;

/**
 * Stable, named insertion point within a {@link LayerStack}.
 * <p>
 * Built-in layers register under a slot so consumer-supplied layers can be positioned relative to
 * them via {@link LayerStack#addBefore} / {@link LayerStack#addAfter} without depending on concrete
 * layer classes. Slots are typically declared as an {@code enum} whose declaration order is the
 * default render order; {@link #order()} returns that order and lower values render first.
 */
public interface LayerSlot {

    /**
     * Returns the relative render order of this slot; lower values render first.
     *
     * @return the render order, conventionally the enum {@linkplain Enum#ordinal() ordinal}
     */
    int order();

    /**
     * Returns a stable identifier for this slot, used for diagnostics.
     *
     * @return the slot identifier, conventionally the enum constant name
     */
    @NotNull String id();
}
