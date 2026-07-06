package lib.minecraft.renderer.option.spec;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The worn-armor pieces (helmet, chestplate, leggings, boots) shared by the entity and player
 * renderers. Composed into each subject's options as one {@code armor} field so the four slots are
 * declared once rather than re-spelled per renderer.
 * <p>
 * Every slot defaults to {@link Optional#empty()}, so the default is an unarmored subject.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class ArmorOptions {

    /**
     * Helmet armor piece.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> helmet = Optional.empty();

    /**
     * Chestplate armor piece.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> chestplate = Optional.empty();

    /**
     * Leggings armor piece.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> leggings = Optional.empty();

    /**
     * Boots armor piece.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorPiece> boots = Optional.empty();

    /**
     * Opens a builder seeded from this instance's current values, for deriving a variant with a
     * few fields changed.
     *
     * @return a builder pre-populated from this instance
     */
    public @NotNull ArmorOptionsBuilder mutate() {
        return this.toBuilder();
    }

    /**
     * Builds an instance with every slot empty.
     *
     * @return the default unarmored options
     */
    public static @NotNull ArmorOptions defaults() {
        return builder().build();
    }
}
