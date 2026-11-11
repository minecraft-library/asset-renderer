package lib.minecraft.renderer.bake.armor;

import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.content.index.CitResult;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.draw.SampledTexture;
import lib.minecraft.renderer.engine.draw.Sampler;
import lib.minecraft.renderer.fixture.TooltipFixtures;
import lib.minecraft.renderer.support.RecordingContext;
import lib.minecraft.renderer.vanilla.DyeColor;
import lib.minecraft.renderer.vanilla.equipment.LayerType;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of {@link EquipmentKit}'s dye handling on the two shapes the vanilla corpus ships: a dyeable
 * base with an undyed fallback colour (leather body armor) and a dyeable overlay with none (the wolf's
 * armadillo scute). The second is the load-bearing one - undyed it resolves to colour 0 and must be
 * skipped, so the overlay draws only once the wearer supplies a dye.
 * <p>
 * Every layer is answered as a pass of its own - its own texture, read by the sampler its sidecar
 * selects, and its colour as the vertex tint - so nothing is pasted or tinted into one picture first.
 */
class EquipmentKitTest {

    private static final @NotNull ResourceId ASSET = new ResourceId("minecraft", "armadillo_scute");

    /** An opaque mid-grey every layer texture holds. */
    private static final int GREY = 0xFF808080;

    @Test
    @DisplayName("a dyeable layer with no undyed fallback is skipped when the wearer supplies no dye")
    void undyedSkipsTheFallbackLessLayer() {
        RecordingContext context = greyContext(scuteLayers());
        List<EquipmentKit.Layer> layers = EquipmentKit.layers(
            context, ASSET, LayerType.WOLF_BODY, Optional.empty(), CitResult.NONE, OptionalInt.empty());

        assertThat("the base alone draws - the overlay contributes no pass", layers, hasSize(1));
        assertThat("the base is untinted", layers.getFirst().tint(), is(ColorMath.WHITE));
        assertThat("the overlay's texture is never read", context.getResolved(),
            equalTo(List.of("minecraft:entity/equipment/wolf_body/armadillo_scute")));
    }

    @Test
    @DisplayName("a dyeable layer with no undyed fallback draws tinted by the wearer's dye")
    void dyedTintsTheFallbackLessLayer() {
        RecordingContext context = greyContext(scuteLayers());
        List<EquipmentKit.Layer> layers = EquipmentKit.layers(
            context, ASSET, LayerType.WOLF_BODY,
            Optional.of(DyeColor.Vanilla.RED.argb()), CitResult.NONE, OptionalInt.empty());

        assertThat("both layer textures were resolved", context.getResolved(), equalTo(List.of(
            "minecraft:entity/equipment/wolf_body/armadillo_scute",
            "minecraft:entity/equipment/wolf_body/armadillo_scute_overlay")));
        assertThat("the base draws first, untinted", layers.getFirst().tint(), is(ColorMath.WHITE));
        assertThat("the overlay draws over it in the dye", layers.getLast().tint(), is(DyeColor.Vanilla.RED.argb()));
        assertThat("its texture untinted, the dye left to the draw",
            layers.getLast().texture().pixels().getPixel(0, 0), is(GREY));
    }

    @Test
    @DisplayName("a dyeable layer with an undyed fallback takes that colour, opaque, when the wearer supplies no dye")
    void undyedTakesTheLayerFallbackColour() {
        RecordingContext context = greyContext(List.of(dyeable(Optional.of(0xA06540))));
        List<EquipmentKit.Layer> layers = EquipmentKit.layers(
            context, ASSET, LayerType.HORSE_BODY, Optional.empty(), CitResult.NONE, OptionalInt.empty());

        assertThat(layers.getFirst().tint(), is(0xFFA06540));
    }

    @Test
    @DisplayName("the wearer's dye overrides a layer's own undyed fallback")
    void dyeOverridesTheLayerFallbackColour() {
        RecordingContext context = greyContext(List.of(dyeable(Optional.of(0xFFA06540))));
        List<EquipmentKit.Layer> layers = EquipmentKit.layers(
            context, ASSET, LayerType.HORSE_BODY,
            Optional.of(DyeColor.Vanilla.CYAN.argb()), CitResult.NONE, OptionalInt.empty());

        assertThat(layers.getFirst().tint(), is(DyeColor.Vanilla.CYAN.argb()));
    }

    @Test
    @DisplayName("two flat layers are two untinted passes, back to front, each its own texture")
    void flatLayersAreTwoPasses() {
        PixelBuffer base = PixelBuffer.ofStraight(new int[]{ 0x80FF0000 }, 1, 1);
        PixelBuffer detail = PixelBuffer.ofStraight(new int[]{ 0x800000FF }, 1, 1);
        RecordingContext context = RecordingContext.over(RendererContext.builder()
                .textures(Map.of(
                    "minecraft:entity/equipment/wolf_body/armadillo_scute", base,
                    "minecraft:entity/equipment/wolf_body/armadillo_scute_overlay", detail))
                .build())
            .answeringEquipment(List.of(
                new EquipmentModel.Layer(new ResourceId("minecraft", "armadillo_scute"), Optional.empty(), false),
                new EquipmentModel.Layer(new ResourceId("minecraft", "armadillo_scute_overlay"), Optional.empty(), false)));
        List<EquipmentKit.Layer> layers = EquipmentKit.layers(
            context, ASSET, LayerType.WOLF_BODY, Optional.empty(), CitResult.NONE, OptionalInt.empty());

        assertThat(layers, hasSize(2));
        assertThat("the base first", layers.getFirst().texture().pixels().getPixel(0, 0), is(0x80FF0000));
        assertThat("the detail over it", layers.getLast().texture().pixels().getPixel(0, 0), is(0x800000FF));
        assertThat("neither tinted", layers.stream().map(EquipmentKit.Layer::tint).toList(),
            equalTo(List.of(ColorMath.WHITE, ColorMath.WHITE)));
    }

    @Test
    @DisplayName("an asset declaring no layers for the render layer draws no pass")
    void noLayersDrawsNothing() {
        RecordingContext context = greyContext(List.of());
        assertThat(EquipmentKit.layers(context, ASSET, LayerType.WOLF_BODY,
            Optional.empty(), CitResult.NONE, OptionalInt.empty()), is(empty()));
    }

    @Test
    @DisplayName("a single flat layer is its own texture, read by the sampler its sidecar selects")
    void aSingleFlatLayerIsReadByItsOwnSampler() {
        PixelBuffer served = PixelBuffer.ofStraight(new int[]{ 0xFF336699, 0xFF996633 }, 2, 1);
        List<EquipmentModel.Layer> declared = List.of(
            new EquipmentModel.Layer(new ResourceId("minecraft", "armadillo_scute"), Optional.empty(), false));
        SampledTexture texture = EquipmentKit.layers(blurredContext(declared, served), ASSET, LayerType.WOLF_BODY,
            Optional.empty(), CitResult.NONE, OptionalInt.empty()).getFirst().texture();

        assertThat("the layer's own sampler", texture.sampler(), is(Sampler.of(true, false)));
        assertThat("the layer's own pixels", texture.pixels(), is(sameInstance(served)));
    }

    @Test
    @DisplayName("every layer of several keeps the sampler its sidecar selects, a dyed one included")
    void everyLayerKeepsItsOwnSampler() {
        List<EquipmentKit.Layer> flat = EquipmentKit.layers(blurredContext(List.of(
                new EquipmentModel.Layer(new ResourceId("minecraft", "armadillo_scute"), Optional.empty(), false),
                new EquipmentModel.Layer(new ResourceId("minecraft", "armadillo_scute_overlay"), Optional.empty(), false)), grey()),
            ASSET, LayerType.WOLF_BODY, Optional.empty(), CitResult.NONE, OptionalInt.empty());
        List<EquipmentKit.Layer> dyed = EquipmentKit.layers(blurredContext(scuteLayers(), grey()), ASSET, LayerType.WOLF_BODY,
            Optional.of(DyeColor.Vanilla.RED.argb()), CitResult.NONE, OptionalInt.empty());

        assertThat("two flat layers", flat, hasSize(2));
        assertThat("a base and a dyed overlay", dyed, hasSize(2));
        for (EquipmentKit.Layer layer : flat)
            assertThat("a flat layer's own sampler", layer.texture().sampler(), is(Sampler.of(true, false)));
        for (EquipmentKit.Layer layer : dyed)
            assertThat("a dyed layer's own sampler", layer.texture().sampler(), is(Sampler.of(true, false)));
    }

    /**
     * Serves the declared layers, every layer texture the given pixels beside a sidecar whose texture section
     * blurs.
     *
     * @param layers the layers the asset declares
     * @param pixels the pixels every layer texture holds
     * @return the context
     */
    private static @NotNull RecordingContext blurredContext(@NotNull List<EquipmentModel.Layer> layers, @NotNull PixelBuffer pixels) {
        Map<String, PixelBuffer> textures = new HashMap<>();
        Map<String, MCMeta> metas = new HashMap<>();
        for (EquipmentModel.Layer layer : layers) {
            String id = layer.textureLocation(LayerType.WOLF_BODY).id();
            textures.put(id, pixels);
            metas.put(id, new MCMeta(ResourceId.parse(id), Optional.empty(), Possible.absent(),
                Possible.of(new MCMeta.TextureSampling(true, false, MCMeta.TextureSampling.MipmapStrategy.AUTO, 0f)),
                Possible.absent(), Optional.empty()));
        }
        return RecordingContext.over(TooltipFixtures.stubContext(textures, metas)).answeringEquipment(layers);
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

    /** A one-pixel opaque grey source. */
    private static @NotNull PixelBuffer grey() {
        PixelBuffer buffer = PixelBuffer.straight(1, 1);
        buffer.setPixel(0, 0, GREY);
        return buffer;
    }

    /** Serves the declared layers and a flat grey for every texture, recording resolution order. */
    private static @NotNull RecordingContext greyContext(@NotNull List<EquipmentModel.Layer> layers) {
        return RecordingContext.over(RendererContext.builder()
                .textures(id -> Possible.of(grey()))
                .build())
            .answeringEquipment(layers);
    }

}
