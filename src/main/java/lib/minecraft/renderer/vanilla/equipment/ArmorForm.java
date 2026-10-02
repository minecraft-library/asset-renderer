package lib.minecraft.renderer.vanilla.equipment;

import dev.simplified.annotations.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The two shapes vanilla's worn armor comes in - the shell an adult humanoid is dressed in and the
 * one a baby is - and what differs between them by shape.
 *
 * <p>Vanilla builds both from the same four-slot fan-out but hands each a different base mesh, a
 * different per-slot part table, and a different equipment layer, and it draws a trim on only one of
 * them. The base mesh is the shell's own and is not held here. The other three facts belong together:
 * they are a property of the shape rather than of the wearer, and reading them off one constant keeps
 * the render path free of the age branch that would otherwise have to repeat in each of them. Each
 * constant holds its part table; the layer and the trim are derived from the constant and the slot.
 *
 * <ul>
 *   <li><b>Parts</b> - a mirror of vanilla's {@code ADULT_ARMOR_PARTS_PER_SLOT} and
 *       {@code BABY_ARMOR_PARTS_PER_SLOT}. They are not the same table with different names: a baby's
 *       leggings reach a {@code waist} part the adult shell has no counterpart for, and its boots
 *       reach a pair of feet parented under the legs rather than the legs themselves.</li>
 *   <li><b>Layer</b> - a baby draws every one of its four slots from {@code humanoid_baby}, leggings
 *       included, which is why vanilla ships no baby leggings sheet.</li>
 *   <li><b>Trim</b> - a baby draws none. Vanilla's equipment renderer returns before submitting one
 *       whenever the layer is the baby layer, and there is no baby trim atlas to sample either.</li>
 * </ul>
 */
@RequiredArgsConstructor
public enum ArmorForm {

    /** The shell every armored humanoid wears at full size, on the {@code humanoid} sheets. */
    ADULT(Map.of(
        ArmorSlot.HELMET, List.of("head"),
        ArmorSlot.CHESTPLATE, List.of("body", "right_arm", "left_arm"),
        ArmorSlot.LEGGINGS, List.of("body", "right_leg", "left_leg"),
        ArmorSlot.BOOTS, List.of("right_leg", "left_leg")
    )),

    /** The distinct shell a baby wears, drawn from {@code humanoid_baby} and never trimmed. */
    BABY(Map.of(
        ArmorSlot.HELMET, List.of("head"),
        ArmorSlot.CHESTPLATE, List.of("body", "right_arm", "left_arm"),
        ArmorSlot.LEGGINGS, List.of("waist", "right_leg", "left_leg"),
        ArmorSlot.BOOTS, List.of("right_foot", "left_foot")
    ));

    private final @NotNull Map<ArmorSlot, List<String>> parts;

    /**
     * The shell parts a slot's armor names. A helmet also covers those parts' children
     * ({@link ArmorSlot#keepsChildren()}); the other three cover exactly what they name.
     *
     * @param slot the armor slot
     * @return the part names that slot names
     */
    public @NotNull List<String> parts(@NotNull ArmorSlot slot) {
        return this.parts.get(slot);
    }

    /**
     * The equipment layer a slot's armor texture is composited from. A baby draws every one of its four
     * slots from the one baby sheet; on the adult shell the layer is the one the slot wears, which the
     * slot answers for itself.
     *
     * @param slot the armor slot
     * @return the layer the slot draws through
     */
    public @NotNull LayerType layerType(@NotNull ArmorSlot slot) {
        if (this == BABY) return LayerType.HUMANOID_BABY;
        return slot.onLayer(LayerType.HUMANOID_LEGGINGS, LayerType.HUMANOID);
    }

    /**
     * The {@code trims/entity/} atlas a slot's trim is permuted from, empty when this form draws no
     * trim. Named after the layer the slot draws through, which is what vanilla keys its trim assets
     * by.
     *
     * <p>Read off that layer rather than off a flag the form carries, which is what vanilla's own early
     * return keys on: its equipment renderer returns before submitting a trim whenever the layer is the
     * baby one, and no baby trim atlas ships to sample.
     *
     * @param slot the armor slot
     * @return the trim atlas name, or empty when this form is never trimmed
     */
    public @NotNull Optional<String> trimLayer(@NotNull ArmorSlot slot) {
        LayerType layer = layerType(slot);
        return layer == LayerType.HUMANOID_BABY ? Optional.empty() : Optional.of(layer.getId());
    }

}
