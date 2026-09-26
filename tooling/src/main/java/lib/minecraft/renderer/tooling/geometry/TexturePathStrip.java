package lib.minecraft.renderer.tooling.geometry;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.ToolingException;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import org.jetbrains.annotations.NotNull;

/**
 * The reduction every texture member in the finished models tree takes - the full namespaced
 * vanilla asset path down to the {@code textures/entity/} sub-path a reader resolves.
 *
 * <p>The prefix and suffix every row repeats are settled here, once, ahead of the write, rather
 * than re-stripped at every load.
 */
@UtilityClass
public final class TexturePathStrip {

    /** What every entity texture path in the finished tree starts with, refused where one does not. */
    private static final @NotNull String TEXTURE_ROOT =
        SourceClasses.Paths.MINECRAFT_NAMESPACE + SourceClasses.Paths.TEXTURES_ENTITY;

    /** What every entity texture path ends with. */
    private static final @NotNull String TEXTURE_EXTENSION = ".png";

    /**
     * Reduces every texture member in the finished models tree to the {@code textures/entity/}
     * sub-path the reader resolves - {@code zombie/zombie} for
     * {@code minecraft:textures/entity/zombie/zombie.png} - covering {@code texture},
     * {@code baby_texture}, and the values of a {@code textures} or {@code textures_by_value} map.
     *
     * <p>A path outside that shape refuses the flow: every entity texture vanilla ships lives under
     * one root, so a row that does not is a walk defect, and this is where it fails loudly rather
     * than at the first load of the shipped table.
     *
     * @param models the model table, rewritten in place ahead of being written
     * @throws ToolingException if a texture path is not {@code minecraft:textures/entity/*.png}
     */
    public static void stripTexturePaths(@NotNull JsonTree models) {
        models.members().forEach(TexturePathStrip::stripBelow);
    }

    /** One node's texture members reduced, everything below it walked. */
    private static void stripBelow(@NotNull String entity, @NotNull JsonTree node) {
        if (node.isArray()) {
            node.elements().toList().forEach(entry -> stripBelow(entity, entry));
            return;
        }
        if (!node.isObject()) return;
        for (String member : node.keys().toList()) {
            JsonTree held = node.find(member).orElseThrow();
            switch (member) {
                case "texture", "baby_texture" ->
                    held.asString().ifPresent(path -> node.put(member, stripped(entity, path)));
                case "textures", "textures_by_value" -> {
                    for (String key : held.keys().toList()) {
                        String path = held.findString(key).orElse(null);
                        if (path != null) held.put(key, stripped(entity, path));
                    }
                }
                default -> stripBelow(entity, held);
            }
        }
    }

    /** One path's sub-path under the entity texture root, or a refusal. */
    private static @NotNull String stripped(@NotNull String entity, @NotNull String path) {
        if (!path.startsWith(TEXTURE_ROOT) || !path.endsWith(TEXTURE_EXTENSION))
            throw new ToolingException("'%s' names texture '%s', which is not under '%s*%s'",
                entity, path, TEXTURE_ROOT, TEXTURE_EXTENSION);
        return path.substring(TEXTURE_ROOT.length(), path.length() - TEXTURE_EXTENSION.length());
    }

}
