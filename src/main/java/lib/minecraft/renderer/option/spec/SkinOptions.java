package lib.minecraft.renderer.option.spec;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import org.jetbrains.annotations.NotNull;

/**
 * The player skin and cape texture sources plus their render toggles - the skin / cape each a
 * three-source {@link TextureOptions}, with cape rendering gated by {@code renderCape} and the
 * second skin layer by {@code renderOverlay}.
 */
@Getter
@ClassBuilder(style = NamingStyle.LOMBOK)
public class SkinOptions {

    /**
     * The skin texture source.
     */
    private final @NotNull TextureOptions skin = TextureOptions.defaults();

    /**
     * The cape texture source (rendered only when {@code renderCape}).
     */
    private final @NotNull TextureOptions cape = TextureOptions.defaults();

    /**
     * Whether to render the cape behind the torso (3D bust / full only).
     */
    private final boolean renderCape = false;

    /**
     * The elytra wing texture source (rendered only when {@code renderElytra}). Falls back to the
     * static {@code minecraft:elytra} wing skin when it supplies no source, mirroring how the cape
     * degrades.
     */
    private final @NotNull TextureOptions elytra = TextureOptions.defaults();

    /**
     * Whether to render elytra wings behind the torso (3D bust / full only).
     */
    private final boolean renderElytra = false;

    /**
     * Whether to render the second skin layer (hat, jacket, sleeves, trousers).
     */
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
