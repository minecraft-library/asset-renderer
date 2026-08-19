package lib.minecraft.renderer.support;

import dev.simplified.annotations.Getter;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.ArmorMaterial;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.pack.rule.CitResult;
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
import lib.minecraft.renderer.engine.RendererContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * An in-memory {@link RendererContext} whose every lookup answers empty until a {@link Builder} call
 * wires one live, so a test states only the seam it is exercising - {@code builder().build()} is the
 * wholly empty context.
 * <p>
 * Every {@link #resolveTexture} call is recorded in order on {@link #getResolved()}, which is what makes
 * a kit's resolution order and per-layer id selection observable, and {@link #isArmorOverrideConsulted()}
 * separates a lookup that answered {@link CitResult#NONE} from one that was never made at all.
 */
public final class StubRendererContext implements RendererContext {

    /** every texture id {@link #resolveTexture} has been asked for, in call order */
    @Getter private final @NotNull List<String> resolved = new ArrayList<>();

    /** whether {@link #resolveArmorTextureOverride} has been reached, however it answered */
    @Getter private boolean armorOverrideConsulted = false;

    /** the texture source every resolve consults */
    private final @NotNull Function<String, Optional<PixelBuffer>> textures;

    /** the layers every equipment lookup answers with, whatever asset and layer type it names */
    private final @NotNull List<EquipmentModel.Layer> equipmentLayers;

    /** the effect every armour override lookup answers with */
    private final @NotNull CitResult armorOverride;

    /** the colormaps this context supplies, keyed by the tint target each serves */
    private final @NotNull Map<Block.TintTarget, ColorMap> colorMaps;

    /** the pack colour overrides this context supplies, keyed by their {@code color.properties} key */
    private final @NotNull Map<String, Integer> colorOverrides;

    private StubRendererContext(@NotNull Builder builder) {
        this.textures = builder.textures;
        this.equipmentLayers = builder.equipmentLayers;
        this.armorOverride = builder.armorOverride;
        this.colorMaps = builder.colorMaps;
        this.colorOverrides = builder.colorOverrides;
    }

    /**
     * Opens a fresh builder over a context whose every lookup answers empty.
     *
     * @return a new builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<Block> findBlock(@NotNull String id) {
        return Optional.empty();
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<ColorMap> findColorMap(Block.@NotNull TintTarget target) {
        return Optional.ofNullable(this.colorMaps.get(target));
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<Integer> findColorOverride(@NotNull String key) {
        return Optional.ofNullable(this.colorOverrides.get(key));
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<Entity> findEntity(@NotNull String id) {
        return Optional.empty();
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<Item> findItem(@NotNull String id) {
        return Optional.empty();
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull CitResult resolveArmorTextureOverride(
        @NotNull ArmorMaterial material, @NotNull LayerType layerType, @NotNull ItemContext item) {
        this.armorOverrideConsulted = true;
        return this.armorOverride;
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull List<EquipmentModel.Layer> resolveEquipmentLayers(
        @NotNull ResourceId assetId, @NotNull LayerType layerType) {
        return this.equipmentLayers;
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
        this.resolved.add(textureId);
        return this.textures.apply(textureId);
    }

    /**
     * A mutable builder for {@link StubRendererContext}. Every lookup starts empty, so a call here is a
     * statement that the test under construction reaches that seam.
     */
    public static final class Builder {

        private @NotNull Function<String, Optional<PixelBuffer>> textures = textureId -> Optional.empty();
        private @NotNull List<EquipmentModel.Layer> equipmentLayers = List.of();
        private @NotNull CitResult armorOverride = CitResult.NONE;
        private @NotNull Map<Block.TintTarget, ColorMap> colorMaps = Map.of();
        private @NotNull Map<String, Integer> colorOverrides = Map.of();

        private Builder() {}

        /**
         * Answers every texture id with a fresh buffer from the given factory, for a test that cares which
         * ids were asked for rather than what came back.
         *
         * @param factory the source of each answered buffer, called once per resolve
         * @return this builder
         */
        public @NotNull Builder everyTexture(@NotNull Supplier<PixelBuffer> factory) {
            this.textures = textureId -> Optional.of(factory.get());
            return this;
        }

        /**
         * Answers a texture id from the given fixture map, and every id absent from it with empty - so a
         * test can prove which of two candidate ids a kit reached for.
         *
         * @param byId the fixture buffers keyed by namespaced texture id
         * @return this builder
         */
        public @NotNull Builder texturesById(@NotNull Map<String, PixelBuffer> byId) {
            Map<String, PixelBuffer> fixtures = Map.copyOf(byId);
            this.textures = textureId -> Optional.ofNullable(fixtures.get(textureId));
            return this;
        }

        /**
         * Answers every equipment lookup with the given layers.
         *
         * @param layers the base-to-overlay layers every asset and layer type resolves to
         * @return this builder
         */
        public @NotNull Builder equipmentLayers(@NotNull List<EquipmentModel.Layer> layers) {
            this.equipmentLayers = layers;
            return this;
        }

        /**
         * Answers every armour override lookup with the given effect.
         *
         * @param override the effect a hit resolves to, or {@link CitResult#NONE} for a miss
         * @return this builder
         */
        public @NotNull Builder armorOverride(@NotNull CitResult override) {
            this.armorOverride = override;
            return this;
        }

        /**
         * Supplies the biome colormaps, keyed by the tint target each serves; a target absent from
         * the map answers empty.
         *
         * @param colorMaps the colormaps this context serves
         * @return this builder
         */
        public @NotNull Builder colorMaps(@NotNull Map<Block.TintTarget, ColorMap> colorMaps) {
            this.colorMaps = colorMaps;
            return this;
        }

        /**
         * Supplies the pack colour overrides, keyed by their raw {@code color.properties} key; a key
         * absent from the map answers empty.
         *
         * @param colorOverrides the overrides this context serves
         * @return this builder
         */
        public @NotNull Builder colorOverrides(@NotNull Map<String, Integer> colorOverrides) {
            this.colorOverrides = colorOverrides;
            return this;
        }

        /**
         * Materialises the context.
         *
         * @return the configured stub
         */
        public @NotNull StubRendererContext build() {
            return new StubRendererContext(this);
        }

    }

}
