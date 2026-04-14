package dev.sbs.renderer.model;

import dev.sbs.renderer.model.asset.AnimationData;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * A texture reference within a texture pack. The DTO stores only metadata and a relative file
 * path under the asset cache root - raw PNG bytes stay on disk to keep memory lean.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Texture {

    private @NotNull String id = "";

    private @NotNull String packId = "";

    private @NotNull String relativePath = "";

    private int width = 0;

    private int height = 0;

    private @NotNull Optional<AnimationData> animation = Optional.empty();

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Texture texture = (Texture) o;
        return this.getWidth() == texture.getWidth()
            && this.getHeight() == texture.getHeight()
            && Objects.equals(this.getId(), texture.getId())
            && Objects.equals(this.getPackId(), texture.getPackId())
            && Objects.equals(this.getRelativePath(), texture.getRelativePath())
            && Objects.equals(this.getAnimation(), texture.getAnimation());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getId(), this.getPackId(), this.getRelativePath(), this.getWidth(), this.getHeight(), this.getAnimation());
    }

}
