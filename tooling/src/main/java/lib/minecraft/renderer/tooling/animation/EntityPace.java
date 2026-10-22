package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.tooling.exception.ToolingException;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The walks of one entity, by age.
 *
 * <p>Keyed by age because a baby's walk is its own rather than the adult's scaled: a baby's movement
 * modifier, its own medium's floors and its walk feed's scale each move it apart, so seven babies
 * would walk wrong as "the adult's times three".
 *
 * @param adult the adult's walks, empty where the resolver derives no pace for the age
 * @param baby the baby's walks, on the same terms
 */
public record EntityPace(@NotNull Optional<AgePace> adult, @NotNull Optional<AgePace> baby) {

    /** The pace of a subject nothing resolves - no walk at either age. */
    public static final @NotNull EntityPace NONE = new EntityPace(Optional.empty(), Optional.empty());

    /**
     * The walks of one age side - {@code adult} for a row that names no age.
     *
     * @param age the age token a style row carries, {@code adult}, {@code baby} or empty for both
     * @return that side's walks, empty where it has none
     * @throws ToolingException if the token names no age
     */
    public @NotNull Optional<AgePace> at(@NotNull String age) {
        return switch (age) {
            case "", "adult" -> this.adult;
            case "baby" -> this.baby;
            default -> throw new ToolingException("No age side is spelled '%s'", age);
        };
    }

}
