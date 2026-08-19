package lib.minecraft.renderer.option;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A three-source texture reference resolved in precedence order - raw PNG bytes (1), then an
 * absolute URL (2), then a pack-resolvable texture id (3).
 */
@Getter
@ClassBuilder
public class TextureOptions {

    /**
     * Raw PNG bytes (priority 1).
     */
    private final @NotNull Optional<byte[]> bytes = Optional.empty();

    /**
     * Absolute texture URL (priority 2).
     */
    private final @NotNull Optional<String> url = Optional.empty();

    /**
     * Pack-resolvable texture id (priority 3).
     */
    private final @NotNull Optional<String> id = Optional.empty();

    /**
     * Builds an instance with every source empty.
     *
     * @return the default unset texture reference
     */
    public static @NotNull TextureOptions defaults() {
        return builder().build();
    }
}
