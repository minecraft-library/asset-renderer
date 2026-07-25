package lib.minecraft.renderer.asset.equipment;

import lib.minecraft.renderer.option.spec.ArmorTrim;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The two shapes vanilla's worn armor comes in - the shell an adult humanoid is dressed in and the
 * one a baby is - and everything that differs between them.
 *
 * <p>Vanilla builds both from the same four-slot fan-out but hands each a different base mesh, a
 * different per-slot part table, and a different equipment layer, and it draws a trim on only one of
 * them. Those four facts belong together: they are a property of the shell rather than of the wearer,
 * and reading them off one constant keeps the render path free of the age branch that would
 * otherwise have to repeat in each of them.
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
public enum ArmorForm {

    /** The shell every armored humanoid wears at full size, on the {@code humanoid} sheets. */
    ADULT(Map.of(
        ArmorTrim.Slot.HELMET, List.of("head"),
        ArmorTrim.Slot.CHESTPLATE, List.of("body", "right_arm", "left_arm"),
        ArmorTrim.Slot.LEGGINGS, List.of("body", "right_leg", "left_leg"),
        ArmorTrim.Slot.BOOTS, List.of("right_leg", "left_leg")
    ), true),

    /** The distinct shell a baby wears, drawn from {@code humanoid_baby} and never trimmed. */
    BABY(Map.of(
        ArmorTrim.Slot.HELMET, List.of("head"),
        ArmorTrim.Slot.CHESTPLATE, List.of("body", "right_arm", "left_arm"),
        ArmorTrim.Slot.LEGGINGS, List.of("waist", "right_leg", "left_leg"),
        ArmorTrim.Slot.BOOTS, List.of("right_foot", "left_foot")
    ), false);

    private final @NotNull Map<ArmorTrim.Slot, List<String>> parts;
    private final boolean trimmed;

    ArmorForm(@NotNull Map<ArmorTrim.Slot, List<String>> parts, boolean trimmed) {
        this.parts = parts;
        this.trimmed = trimmed;
    }

    /**
     * The shell parts a slot's armor covers. A helmet keeps its part <em>and that part's
     * children</em>, matching vanilla; the other three keep exactly the parts they name.
     *
     * @param slot the armor slot
     * @return the part names that slot covers
     */
    public @NotNull List<String> parts(@NotNull ArmorTrim.Slot slot) {
        return this.parts.get(slot);
    }

    /**
     * The equipment layer a slot's armor texture is composited from.
     *
     * @param slot the armor slot
     * @return the layer the slot draws through
     */
    public @NotNull LayerType layerType(@NotNull ArmorTrim.Slot slot) {
        if (this == BABY) return LayerType.HUMANOID_BABY;
        return slot == ArmorTrim.Slot.LEGGINGS ? LayerType.HUMANOID_LEGGINGS : LayerType.HUMANOID;
    }

    /**
     * The {@code trims/entity/} atlas a slot's trim is permuted from, empty when this form draws no
     * trim. Named after the layer the slot draws through, which is what vanilla keys its trim assets
     * by.
     *
     * @param slot the armor slot
     * @return the trim atlas name, or empty when this form is never trimmed
     */
    public @NotNull Optional<String> trimLayer(@NotNull ArmorTrim.Slot slot) {
        return this.trimmed ? Optional.of(layerType(slot).getId()) : Optional.empty();
    }

}
