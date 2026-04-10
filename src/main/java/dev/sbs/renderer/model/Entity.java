package dev.sbs.renderer.model;

import dev.sbs.renderer.model.asset.EntityModelData;
import dev.simplified.persistence.JpaModel;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * A fully-parsed entity definition - geometry and texture reference - for use by the entity
 * renderer's {@code ENTITY_3D} mode. Player skins are never stored on this entity; they are
 * supplied at render time through the {@code EntityOptions.skinBytes}/{@code skinUrl}/
 * {@code skinTextureId} fields.
 */
@Getter
@jakarta.persistence.Entity
@Table(name = "renderer_entities")
public class Entity implements JpaModel {

    @Id
    @Column(name = "id", nullable = false)
    private @NotNull String id = "";

    @Column(name = "namespace", nullable = false)
    private @NotNull String namespace = "minecraft";

    @Column(name = "name", nullable = false)
    private @NotNull String name = "";

    @Column(name = "model", nullable = false)
    private @NotNull EntityModelData model = new EntityModelData();

    @Column(name = "texture_id")
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
