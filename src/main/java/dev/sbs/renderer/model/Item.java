package dev.sbs.renderer.model;

import com.google.gson.annotations.SerializedName;
import dev.sbs.renderer.model.asset.ItemModelData;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
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
 * A fully-parsed item definition backed by its vanilla model JSON.
 * <p>
 * Every field is populated once during {@code AssetPipeline} bootstrap and stored verbatim. A
 * non-zero {@link #getMaxDurability()} gates the GUI damage bar overlay at render time.
 */
@Getter
@Entity
@Table(name = "renderer_items")
public class Item implements JpaModel {

    @Id
    @Column(name = "id", nullable = false)
    private @NotNull String id = "";

    @Column(name = "namespace", nullable = false)
    private @NotNull String namespace = "minecraft";

    @Column(name = "name", nullable = false)
    private @NotNull String name = "";

    @Column(name = "model", nullable = false)
    private @NotNull ItemModelData model = new ItemModelData();

    @Column(name = "textures", nullable = false)
    private @NotNull ConcurrentMap<String, String> textures = Concurrent.newMap();

    @SerializedName("max_durability")
    @Column(name = "max_durability", nullable = false)
    private int maxDurability = 0;

    @SerializedName("stack_size")
    @Column(name = "stack_size", nullable = false)
    private int stackSize = 64;

    @SerializedName("overlay_binding_id")
    @Column(name = "overlay_binding_id")
    private @NotNull Optional<String> overlayBindingId = Optional.empty();

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Item item = (Item) o;
        return this.getMaxDurability() == item.getMaxDurability()
            && this.getStackSize() == item.getStackSize()
            && Objects.equals(this.getId(), item.getId())
            && Objects.equals(this.getNamespace(), item.getNamespace())
            && Objects.equals(this.getName(), item.getName())
            && Objects.equals(this.getModel(), item.getModel())
            && Objects.equals(this.getTextures(), item.getTextures())
            && Objects.equals(this.getOverlayBindingId(), item.getOverlayBindingId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getId(), this.getNamespace(), this.getName(), this.getModel(), this.getTextures(), this.getMaxDurability(), this.getStackSize(), this.getOverlayBindingId());
    }

}
