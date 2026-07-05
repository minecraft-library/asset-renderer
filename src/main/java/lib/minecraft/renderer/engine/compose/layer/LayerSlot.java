package lib.minecraft.renderer.engine.compose.layer;

/**
 * Marker for an {@code enum} whose constants name the stable insertion points of a {@link LayerStack}.
 * <p>
 * Built-in layers register under a slot so consumer-supplied layers can be positioned relative to them
 * via {@link LayerStack#addBefore} / {@link LayerStack#addAfter} without depending on concrete layer
 * classes. A slot's {@linkplain Enum#ordinal() ordinal} is its default render order - lower renders
 * first - and its {@linkplain Enum#name() name} is its diagnostic id; {@link LayerStack} reads both
 * generically through its {@code <S extends Enum<S> & LayerSlot>} bound, so slots are always enums and
 * carry no accessor boilerplate.
 */
public interface LayerSlot {

}
