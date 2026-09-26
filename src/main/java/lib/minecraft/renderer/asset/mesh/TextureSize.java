package lib.minecraft.renderer.asset.mesh;

import com.google.gson.annotations.JsonAdapter;
import lib.minecraft.renderer.content.json.TextureSizeAdapter;
import org.jetbrains.annotations.NotNull;

/**
 * The texture atlas dimensions a model's cube UVs resolve against, carried as the geometry dialect's
 * {@code texture_size:[w, h]} array. The {@link TextureSizeAdapter} reads and writes that
 * two-element array form so the record deserialises directly, without a pre-split of the geometry
 * document.
 *
 * @param width the atlas width in pixels
 * @param height the atlas height in pixels
 */
@JsonAdapter(TextureSizeAdapter.class)
public record TextureSize(int width, int height) {

    /** The vanilla default atlas dimensions ({@code 64 x 64}). */
    public static final @NotNull TextureSize DEFAULT = new TextureSize(64, 64);

}
