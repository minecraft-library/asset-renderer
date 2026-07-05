package lib.minecraft.renderer.options.spec;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The player skin and cape texture sources plus their render toggles - the skin / cape each a
 * three-source {@link TextureOptions}, with cape rendering gated by {@code renderCape} and the
 * second skin layer by {@code renderOverlay}.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class SkinOptions {

    /**
     * The skin texture source.
     */
    @lombok.Builder.Default
    private final @NotNull TextureOptions skin = TextureOptions.defaults();

    /**
     * The cape texture source (rendered only when {@code renderCape}).
     */
    @lombok.Builder.Default
    private final @NotNull TextureOptions cape = TextureOptions.defaults();

    /**
     * Whether to render the cape behind the torso (3D bust / full only).
     */
    @lombok.Builder.Default
    private final boolean renderCape = false;

    /**
     * Whether to render the second skin layer (hat, jacket, sleeves, trousers).
     */
    @lombok.Builder.Default
    private final boolean renderOverlay = true;

    /**
     * Opens a builder seeded from this instance's current values, for deriving a variant with a
     * few fields changed.
     *
     * @return a builder pre-populated from this instance
     */
    public @NotNull SkinOptionsBuilder mutate() {
        return this.toBuilder();
    }

    /**
     * Builds an instance with empty skin / cape sources, the cape hidden and the overlay layer on.
     *
     * @return the default skin options
     */
    public static @NotNull SkinOptions defaults() {
        return builder().build();
    }
}
