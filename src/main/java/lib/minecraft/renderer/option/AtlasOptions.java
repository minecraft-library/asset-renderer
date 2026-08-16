package lib.minecraft.renderer.option;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.image.Background;
import lib.minecraft.renderer.AtlasRenderer;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Configures a single {@code AtlasRenderer} invocation. Selects which model sources to render,
 * what tile size to output, and how the renderer should report progress.
 * <p>
 * Output paths are deliberately not part of these options - the renderer produces an
 * {@code AtlasResult} carrying the composed image data and the {@link AtlasSidecar} placing every
 * tile in it, and the caller (typically the {@code generateAtlas} Gradle task) decides where to
 * write them and in what form.
 */
@Parity(as = AtlasRenderer.class, mode = Mode.SUPPRESS)
@Getter
@ClassBuilder
public class AtlasOptions implements RenderOptions {

    /**
     * Which model kind(s) to include in the atlas - blocks, items, or both
     */
    private final @NotNull Source source = Source.BOTH;

    /**
     * Optional predicate filter evaluated per model id; only ids passing the predicate are
     * rendered. Empty (default) admits every id from the selected {@link #source}
     */
    private final @NotNull Optional<java.util.function.Predicate<String>> filter = Optional.empty();

    /**
     * Output tile dimensions in pixels (square)
     */
    private final int tileSize = 128;

    /**
     * Number of tile columns per row in the output atlas
     */
    private final int columns = 16;

    /**
     * Background fill for empty tile areas (solid colour or checkerboard), defaulting to
     * {@link Background#TRANSPARENT}
     */
    private final @NotNull Background background = Background.TRANSPARENT;

    /**
     * When {@code true}, animated textures are rendered as full animation strips. When
     * {@code false} (default), only the first frame (tick 0) is used, producing a static
     * snapshot suitable for atlas previews.
     */
    private final boolean animated = false;

    /**
     * When {@code true}, the renderer prints per-100-tile progress lines and per-failure
     * warnings to stdout / stderr. CLI consumers (e.g. the {@code generateAtlas} Gradle task)
     * leave this enabled; programmatic consumers that don't want their logs cluttered can flip
     * it off via the builder.
     */
    private final boolean progressLogging = true;

    /**
     * Builds an instance with every field at its default value.
     *
     * @return the default options
     */
    public static @NotNull AtlasOptions defaults() {
        return builder().build();
    }

    /**
     * Which model kind(s) the atlas includes.
     */
    public enum Source {

        /** Block models only. */
        BLOCK,

        /** Item models only. */
        ITEM,

        /** Both block and item models. */
        BOTH

    }

}
