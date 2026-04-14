package dev.sbs.renderer.asset;

import com.google.gson.annotations.SerializedName;
import dev.sbs.renderer.asset.model.ItemModelData;
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
 * non-zero {@link #getMaxDurability()} gates the GUI damage bar overlay at render time. The
 * optional {@link #getOverlay() overlay} carries per-item metadata for vanilla overlay renders
 * (leather armor dye layers, potion liquid, spawn egg spots, firework star center, tipped-arrow
 * head); see {@link Overlay} for the supported shapes.
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

    /**
     * Per-item overlay rendering metadata synthesised by the pipeline (leather, potion, spawn egg,
     * firework, tipped arrow). Empty for items that render as plain layered sprites.
     */
    private @NotNull Optional<Overlay> overlay = Optional.empty();

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
            && Objects.equals(this.getOverlay(), item.getOverlay());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getId(), this.getNamespace(), this.getName(), this.getModel(), this.getTextures(), this.getMaxDurability(), this.getStackSize(), this.getOverlay());
    }

    /**
     * A per-item overlay rendering rule. Vanilla uses a handful of shapes that all boil down to
     * "one base texture + one overlay texture + a tint source," with spawn eggs as the odd sibling
     * that tints both layers with separate colors.
     * <p>
     * The enclosing {@link Item} supplies identity ({@code id}), so overlay records only carry
     * per-shape metadata.
     */
    public sealed interface Overlay {

        /**
         * The leather-armor default dye color ({@code #A06540}) - mirrors the constant in
         * {@code net.minecraft.world.item.component.DyedItemColor}. Stable since 1.8.
         */
        int LEATHER_DEFAULT_ARGB = 0xFFA06540;

        /**
         * The firework-star default overlay color when no NBT {@code Colors} entries are supplied.
         * Gray, chosen as a visible placeholder rather than a vanilla constant.
         */
        int FIREWORK_DEFAULT_ARGB = 0xFF808080;

        /**
         * The untinted base-layer texture id.
         *
         * @return the base texture id
         */
        @NotNull String baseTexture();

        /**
         * The tinted overlay-layer texture id.
         *
         * @return the overlay texture id
         */
        @NotNull String overlayTexture();

        /**
         * The overlay kind, exposed for coarse dispatch and JSON round-tripping.
         *
         * @return the kind
         */
        @NotNull Kind kind();

        /**
         * Coarse classification of the overlay shape. Present for dispatch convenience; the record
         * type itself is the authoritative shape.
         */
        enum Kind {

            LEATHER,
            POTION,
            TIPPED_ARROW,
            FIREWORK,
            SPAWN_EGG

        }

        /**
         * Leather armor: base hide texture untinted, overlay dye texture tinted with
         * {@link #defaultColor()} or a caller-supplied override.
         *
         * @param baseTexture the hide base texture id
         * @param overlayTexture the dye overlay texture id
         * @param defaultColor the fallback dye ARGB, typically {@link Overlay#LEATHER_DEFAULT_ARGB}
         */
        record Leather(
            @NotNull String baseTexture,
            @NotNull String overlayTexture,
            int defaultColor
        ) implements Overlay {

            @Override
            public @NotNull Kind kind() {
                return Kind.LEATHER;
            }

        }

        /**
         * Potion: glass bottle base untinted, liquid overlay tinted by the potion effect color.
         *
         * @param baseTexture the bottle base texture id
         * @param overlayTexture the liquid overlay texture id
         */
        record Potion(
            @NotNull String baseTexture,
            @NotNull String overlayTexture
        ) implements Overlay {

            @Override
            public @NotNull Kind kind() {
                return Kind.POTION;
            }

        }

        /**
         * Tipped arrow: shaft base untinted, head overlay tinted by the potion effect color.
         *
         * @param baseTexture the shaft base texture id
         * @param overlayTexture the head overlay texture id
         */
        record TippedArrow(
            @NotNull String baseTexture,
            @NotNull String overlayTexture
        ) implements Overlay {

            @Override
            public @NotNull Kind kind() {
                return Kind.TIPPED_ARROW;
            }

        }

        /**
         * Firework star: gunpowder base untinted, star overlay tinted by the firework color.
         *
         * @param baseTexture the base texture id
         * @param overlayTexture the star overlay texture id
         * @param defaultColor the fallback firework ARGB, typically {@link Overlay#FIREWORK_DEFAULT_ARGB}
         */
        record Firework(
            @NotNull String baseTexture,
            @NotNull String overlayTexture,
            int defaultColor
        ) implements Overlay {

            @Override
            public @NotNull Kind kind() {
                return Kind.FIREWORK;
            }

        }

        /**
         * Spawn egg: base egg tinted with {@link #defaultPrimary()}, spots overlay tinted with
         * {@link #defaultSecondary()}. Both defaults come from the vanilla {@code SpawnEggItem}
         * color table keyed by entity id.
         *
         * @param baseTexture the egg base texture id (shared across all spawn eggs)
         * @param overlayTexture the spots overlay texture id (shared across all spawn eggs)
         * @param defaultPrimary the egg body ARGB
         * @param defaultSecondary the spots ARGB
         */
        record SpawnEgg(
            @NotNull String baseTexture,
            @NotNull String overlayTexture,
            int defaultPrimary,
            int defaultSecondary
        ) implements Overlay {

            @Override
            public @NotNull Kind kind() {
                return Kind.SPAWN_EGG;
            }

        }

    }

}
