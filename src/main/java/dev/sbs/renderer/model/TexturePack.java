package dev.sbs.renderer.model;

import dev.simplified.persistence.JpaModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A registered texture pack - vanilla or a user-supplied override.
 * <p>
 * The {@code priority} field orders pack lookups at render time; the highest priority pack wins
 * for any texture id collision. Vanilla has priority {@code 0}; user packs load at higher
 * priorities as they are supplied to the renderer.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "renderer_texture_packs")
public class TexturePack implements JpaModel {

    @Id
    @Column(name = "id", nullable = false)
    private @NotNull String id = "";

    @Column(name = "namespace", nullable = false)
    private @NotNull String namespace = "minecraft";

    @Column(name = "description", nullable = false)
    private @NotNull String description = "";

    @Column(name = "root_path", nullable = false)
    private @NotNull String rootPath = "";

    @Column(name = "priority", nullable = false)
    private int priority = 0;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        TexturePack that = (TexturePack) o;
        return this.getPriority() == that.getPriority()
            && Objects.equals(this.getId(), that.getId())
            && Objects.equals(this.getNamespace(), that.getNamespace())
            && Objects.equals(this.getDescription(), that.getDescription())
            && Objects.equals(this.getRootPath(), that.getRootPath());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getId(), this.getNamespace(), this.getDescription(), this.getRootPath(), this.getPriority());
    }

}
