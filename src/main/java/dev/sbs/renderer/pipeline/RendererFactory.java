package dev.sbs.renderer.pipeline;

import dev.sbs.renderer.model.Block;
import dev.simplified.persistence.RepositoryFactory;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Produces a {@link RepositoryFactory} scoped to the renderer's {@code dev.sbs.renderer.model}
 * package, so the {@code SessionManager} picks up every {@code Block}, {@code Item},
 * {@code Entity}, {@code Texture}, {@code TexturePack}, {@code ColorMap}, and
 * {@code OverlayBinding} entity in a single registration call.
 * <p>
 * Consumers call {@link #create()} once during application bootstrap and register the returned
 * factory with their active {@code SessionManager}.
 */
@UtilityClass
public class RendererFactory {

    /**
     * Builds a repository factory whose package scan is anchored at {@link Block}. The
     * simplified-dev persistence layer walks the package and topologically sorts every
     * {@code JpaModel} it finds.
     *
     * @return a new repository factory
     */
    public static @NotNull RepositoryFactory create() {
        return RepositoryFactory.builder()
            .withPackageOf(Block.class)
            .build();
    }

}
