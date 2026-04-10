package dev.sbs.renderer.model;

import dev.sbs.renderer.model.asset.AnimationData;
import dev.simplified.persistence.JpaModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * A texture reference within a texture pack. The entity stores only metadata and a relative file
 * path under the asset cache root - raw PNG bytes stay on disk to keep the database lean.
 */
@Getter
@Entity
@Table(name = "renderer_textures")
public class Texture implements JpaModel {

    @Id
    @Column(name = "id", nullable = false)
    private @NotNull String id = "";

    @Column(name = "pack_id", nullable = false)
    private @NotNull String packId = "";

    @Column(name = "relative_path", nullable = false)
    private @NotNull String relativePath = "";

    @Column(name = "width", nullable = false)
    private int width = 0;

    @Column(name = "height", nullable = false)
    private int height = 0;

    @Column(name = "animation")
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
