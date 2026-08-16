package lib.minecraft.refharness.api;

import dev.simplified.annotations.UtilityClass;

import java.util.Optional;

/**
 * The appearance the subject currently being measured or drawn was asked for.
 *
 * <p>A mixin that normalises an appearance away has no other way to learn that the appearance was
 * requested on purpose. Several of them exist because vanilla derives what they pin from state a
 * transient entity never reaches - a zombie villager rolls a random profession, a pufferfish never
 * puffs - and pinning is right for every subject that selects nothing. It stops being right the
 * moment a reference names that axis, so each such mixin asks here first and pins only when nothing
 * was asked for.
 *
 * <p>Held statically and cleared after each subject, on the same reasoning as the glint phase
 * override: the mixin runs deep inside vanilla's own render-state extraction, with no argument the
 * harness controls. A selection left set would silently reach the next subject.
 */
@UtilityClass
public final class AppearanceRequest {

    private static volatile Appearance current;

    /**
     * Records the appearance the next measurement or draw was asked for.
     *
     * @param appearance what the subject should look like
     */
    public static void set(Appearance appearance) {
        current = appearance;
    }

    /** Forgets the current request, so nothing leaks into the next subject. */
    public static void clear() {
        current = null;
    }

    /** The appearance currently requested, empty between subjects. */
    public static Optional<Appearance> current() {
        return Optional.ofNullable(current);
    }

    /**
     * Whether the current request selects any of these axes.
     *
     * @param axes the axis tokens to look for
     * @return whether one of them was selected
     */
    public static boolean selectsAny(String... axes) {
        Appearance appearance = current;
        if (appearance == null) return false;
        for (Appearance.Trait trait : appearance.traits())
            for (String axis : axes)
                if (trait.axis().equals(axis)) return true;
        return false;
    }
}
