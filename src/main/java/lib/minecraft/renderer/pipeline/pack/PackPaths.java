package lib.minecraft.renderer.pipeline.pack;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Shared container-path templates for the {@code assets/<namespace>/textures} tree. The single
 * source of the directory shape both the forward probe ({@link PackStack} - id to
 * {@code <dir>/<path>.png}) and the reverse scan ({@link TextureIndexer} - {@code <dir>} prefix
 * stripped back to an id) build against, so the two stay in lockstep.
 */
@UtilityClass
class PackPaths {

    /**
     * The pack-relative {@code textures} directory for a namespace - {@code assets/<namespace>/textures}.
     * A root prefix goes in front (scan) and a {@code /<path>.png} leaf goes after (probe).
     *
     * @param namespace the resource namespace
     * @return the pack-relative textures directory for the namespace
     */
    static @NotNull String texturesDir(@NotNull String namespace) {
        return "assets/" + namespace + "/textures";
    }

}
