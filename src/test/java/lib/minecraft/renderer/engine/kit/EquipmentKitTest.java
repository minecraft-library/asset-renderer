package lib.minecraft.renderer.engine.kit;

import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.pack.rule.CitResult;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.option.spec.DyeColor;
import lib.minecraft.renderer.support.StubRendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link EquipmentKit}'s dye handling on the two shapes the vanilla corpus ships: a dyeable
 * base with an undyed fallback colour (leather body armor) and a dyeable overlay with none (the wolf's
 * armadillo scute). The second is the load-bearing one - undyed it resolves to colour 0 and must be
 * skipped, so the overlay draws only once the wearer supplies a dye.
 */
class EquipmentKitTest {

    private static final @NotNull ResourceId ASSET = new ResourceId("minecraft", "armadillo_scute");

    /** An opaque mid-grey the tint multiply is measurable against. */
    private static final int GREY = 0xFF808080;

    @Test
    @DisplayName("a dyeable layer with no undyed fallback is skipped when the wearer supplies no dye")
    void undyedSkipsTheFallbackLessLayer() {
        StubRendererContext context = greyContext(scuteLayers());
        Optional<PixelBuffer> composited = EquipmentKit.composite(
            new Textures(context), ASSET, LayerType.WOLF_BODY, Optional.empty(), CitResult.NONE, OptionalInt.empty());

        assertThat("the base still draws", composited.isPresent(), is(true));
        assertThat("the composite carries the untinted base alone - the overlay contributed nothing",
            composited.get().getPixel(0, 0), is(GREY));
    }

    @Test
    @DisplayName("a dyeable layer with no undyed fallback draws tinted by the wearer's dye")
    void dyedTintsTheFallbackLessLayer() {
        StubRendererContext context = greyContext(scuteLayers());
        Optional<PixelBuffer> composited = EquipmentKit.composite(
            new Textures(context), ASSET, LayerType.WOLF_BODY,
            Optional.of(DyeColor.Vanilla.RED.argb()), CitResult.NONE, OptionalInt.empty());

        assertThat("both layer textures were resolved", context.getResolved(), equalTo(List.of(
            "minecraft:entity/equipment/wolf_body/armadillo_scute",
            "minecraft:entity/equipment/wolf_body/armadillo_scute_overlay")));
        assertThat("the overlay lands tinted over the base",
            composited.orElseThrow().getPixel(0, 0), is(ColorMath.tint(grey(), DyeColor.Vanilla.RED.argb()).getPixel(0, 0)));
    }

    @Test
    @DisplayName("a dyeable layer with an undyed fallback takes that colour when the wearer supplies no dye")
    void undyedTakesTheLayerFallbackColour() {
        int fallback = 0xFFA06540;
        StubRendererContext context = greyContext(List.of(dyeable(Optional.of(fallback))));
        Optional<PixelBuffer> composited = EquipmentKit.composite(
            new Textures(context), ASSET, LayerType.HORSE_BODY, Optional.empty(), CitResult.NONE, OptionalInt.empty());

        assertThat(composited.orElseThrow().getPixel(0, 0), is(ColorMath.tint(grey(), fallback).getPixel(0, 0)));
    }

    @Test
    @DisplayName("the wearer's dye overrides a layer's own undyed fallback")
    void dyeOverridesTheLayerFallbackColour() {
        StubRendererContext context = greyContext(List.of(dyeable(Optional.of(0xFFA06540))));
        Optional<PixelBuffer> composited = EquipmentKit.composite(
            new Textures(context), ASSET, LayerType.HORSE_BODY,
            Optional.of(DyeColor.Vanilla.CYAN.argb()), CitResult.NONE, OptionalInt.empty());

        assertThat(composited.orElseThrow().getPixel(0, 0),
            is(ColorMath.tint(grey(), DyeColor.Vanilla.CYAN.argb()).getPixel(0, 0)));
    }

    @Test
    @DisplayName("an asset declaring no layers for the render layer composites nothing")
    void noLayersCompositesNothing() {
        StubRendererContext context = greyContext(List.of());
        assertThat(EquipmentKit.composite(new Textures(context), ASSET, LayerType.WOLF_BODY,
            Optional.empty(), CitResult.NONE, OptionalInt.empty()).isPresent(), is(false));
    }

    /** The wolf-armor shape: an opaque base plus a dyeable overlay with no undyed fallback. */
    private static @NotNull List<EquipmentModel.Layer> scuteLayers() {
        return List.of(
            new EquipmentModel.Layer(new ResourceId("minecraft", "armadillo_scute"), Optional.empty(), false),
            new EquipmentModel.Layer(new ResourceId("minecraft", "armadillo_scute_overlay"),
                Optional.of(new EquipmentModel.Dyeable(Optional.empty())), false));
    }

    /** A single dyeable layer carrying the given undyed fallback. */
    private static @NotNull EquipmentModel.Layer dyeable(@NotNull Optional<Integer> colorWhenUndyed) {
        return new EquipmentModel.Layer(new ResourceId("minecraft", "leather"),
            Optional.of(new EquipmentModel.Dyeable(colorWhenUndyed)), false);
    }

    /** A one-pixel opaque grey source, the tint multiply's measurable input. */
    private static @NotNull PixelBuffer grey() {
        PixelBuffer buffer = PixelBuffer.create(1, 1);
        buffer.setPixel(0, 0, GREY);
        return buffer;
    }

    /** Serves the declared layers and a flat grey for every texture, recording resolution order. */
    private static @NotNull StubRendererContext greyContext(@NotNull List<EquipmentModel.Layer> layers) {
        return StubRendererContext.builder()
            .equipmentLayers(layers)
            .everyTexture(EquipmentKitTest::grey)
            .build();
    }

}
