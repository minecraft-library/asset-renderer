package dev.sbs.renderer.model;

import com.google.gson.annotations.SerializedName;
import dev.sbs.renderer.model.asset.ItemModelData;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
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
@NoArgsConstructor
@AllArgsConstructor
public class Item {

    private @NotNull String id = "";

    private @NotNull String namespace = "minecraft";

    private @NotNull String name = "";

    private @NotNull ItemModelData model = new ItemModelData();

    private @NotNull ConcurrentMap<String, String> textures = Concurrent.newMap();

    @SerializedName("max_durability")
    private int maxDurability = 0;

    @SerializedName("stack_size")
    private int stackSize = 64;

    @SerializedName("overlay_binding_id")
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
