package lib.minecraft.renderer.asset.equipment;

import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.face.HumanoidPart;
import lib.minecraft.renderer.option.spec.ArmorSlot;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        ArmorSlot.HELMET, List.of("head"),
        ArmorSlot.CHESTPLATE, List.of("body", "right_arm", "left_arm"),
        ArmorSlot.LEGGINGS, List.of("body", "right_leg", "left_leg"),
        ArmorSlot.BOOTS, List.of("right_leg", "left_leg")
    ), true),

    /** The distinct shell a baby wears, drawn from {@code humanoid_baby} and never trimmed. */
    BABY(Map.of(
        ArmorSlot.HELMET, List.of("head"),
        ArmorSlot.CHESTPLATE, List.of("body", "right_arm", "left_arm"),
        ArmorSlot.LEGGINGS, List.of("waist", "right_leg", "left_leg"),
        ArmorSlot.BOOTS, List.of("right_foot", "left_foot")
    ), false);

    private final @NotNull Map<ArmorSlot, List<String>> parts;
    private final boolean trimmed;

    ArmorForm(@NotNull Map<ArmorSlot, List<String>> parts, boolean trimmed) {
        this.parts = parts;
        this.trimmed = trimmed;
    }

    /**
     * The shell parts a slot's armor names. A helmet also covers those parts' <em>children</em>,
     * which {@link #covers} resolves against a mesh; the other three cover exactly what they name.
     *
     * @param slot the armor slot
     * @return the part names that slot names
     */
    public @NotNull List<String> parts(@NotNull ArmorSlot slot) {
        return this.parts.get(slot);
    }

    /**
     * Whether a slot's armor covers this bone of a shell.
     *
     * <p>Vanilla keeps the helmet's part <em>and its children</em>, and exactly the named parts for the
     * other three slots - so the head's overlay box rides a helmet and nothing else, and a baby's boots
     * reach the feet parented under its legs without dragging the legs along.
     *
     * <p>The parent walk is bounded by the set of bones already visited rather than by a depth cap. The
     * armor shells are two hops deep at most, so the two agree on everything shipped; a visiting set is
     * what actually rules out the cycle the cap was standing in for.
     *
     * @param tree the shell whose bone hierarchy resolves the parent chain
     * @param slot the armor slot
     * @param bone the bone name to test
     * @return {@code true} when that slot's armor draws this bone's cubes
     */
    public boolean covers(@NotNull EntityModelData tree, @NotNull ArmorSlot slot, @NotNull String bone) {
        List<String> named = parts(slot);
        if (named.contains(bone)) return true;
        if (!slot.keepsChildren()) return false;

        Set<String> visited = new HashSet<>();
        String cursor = bone;
        while (visited.add(cursor)) {
            EntityModelData.Bone node = tree.getBones().get(cursor);
            if (node == null || node.getParent() == null) return false;
            cursor = node.getParent();
            if (named.contains(cursor)) return true;
        }
        return false;
    }

    /**
     * The equipment layer a slot's armor texture is composited from.
     *
     * @param slot the armor slot
     * @return the layer the slot draws through
     */
    public @NotNull LayerType layerType(@NotNull ArmorSlot slot) {
        if (this == BABY) return LayerType.HUMANOID_BABY;
        return slot == ArmorSlot.LEGGINGS ? LayerType.HUMANOID_LEGGINGS : LayerType.HUMANOID;
    }

    /**
     * The {@code trims/entity/} atlas a slot's trim is permuted from, empty when this form draws no
     * trim. Named after the layer the slot draws through, which is what vanilla keys its trim assets
     * by.
     *
     * @param slot the armor slot
     * @return the trim atlas name, or empty when this form is never trimmed
     */
    public @NotNull Optional<String> trimLayer(@NotNull ArmorSlot slot) {
        return this.trimmed ? Optional.of(layerType(slot).getId()) : Optional.empty();
    }

    /**
     * The slots covering each of the player's own body parts, resolved once from {@link #ADULT}'s part
     * table by reading each bone name back to the body box it dresses.
     */
    private static final @NotNull Map<HumanoidPart, Set<ArmorSlot>> PLAYER_SLOTS = new EnumMap<>(HumanoidPart.class);

    static {
        for (HumanoidPart part : HumanoidPart.CACHED_VALUES) {
            EnumSet<ArmorSlot> slots = EnumSet.noneOf(ArmorSlot.class);

            for (ArmorSlot slot : ArmorSlot.CACHED_VALUES)
                if (ADULT.parts(slot).contains(part.boneName())) slots.add(slot);

            PLAYER_SLOTS.put(part, Set.copyOf(slots));
        }
    }

    /**
     * The slots whose armor covers one of the player's own body parts.
     *
     * <p><b>Restricted to {@link #ADULT}, and the restriction is the guard rather than an oversight.</b>
     * The player is always drawn at adult proportions, and only six of the twelve bone names in the
     * armor corpus have a body part at all - a baby shell's {@code waist} and its two feet have none.
     * A form-parameterised accessor would make those reachable, where the miss would drop a box
     * silently rather than raising anything.
     *
     * @param part the player body part
     * @return the slots whose armor draws that part, empty when none does
     */
    public static @NotNull Set<ArmorSlot> playerSlots(@NotNull HumanoidPart part) {
        return PLAYER_SLOTS.get(part);
    }

}
