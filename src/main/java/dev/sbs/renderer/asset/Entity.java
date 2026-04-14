package dev.sbs.renderer.asset;

import dev.sbs.renderer.asset.model.EntityModelData;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * A fully-parsed entity definition - geometry and texture reference - for use by the entity
 * renderer's {@code ENTITY_3D} mode. Player skins are never stored on this DTO; they are
 * supplied at render time through the {@code EntityOptions.skinBytes}/{@code skinUrl}/
 * {@code skinTextureId} fields.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Entity {

    private @NotNull String id = "";

    private @NotNull String namespace = "minecraft";

    private @NotNull String name = "";

    private @NotNull EntityModelData model = new EntityModelData();

    private @NotNull Optional<String> textureId = Optional.empty();

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Entity entity = (Entity) o;
        return Objects.equals(this.getId(), entity.getId())
            && Objects.equals(this.getNamespace(), entity.getNamespace())
            && Objects.equals(this.getName(), entity.getName())
            && Objects.equals(this.getModel(), entity.getModel())
            && Objects.equals(this.getTextureId(), entity.getTextureId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getId(), this.getNamespace(), this.getName(), this.getModel(), this.getTextureId());
    }

}
