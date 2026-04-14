package dev.sbs.renderer.asset.pack;

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
public class TexturePack {

    private @NotNull String id = "";

    private @NotNull String namespace = "minecraft";

    private @NotNull String description = "";

    private @NotNull String rootPath = "";

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
