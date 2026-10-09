package lib.minecraft.renderer.support;

import dev.simplified.annotations.Getter;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.content.index.CitResult;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.vanilla.equipment.ArmorMaterial;
import lib.minecraft.renderer.vanilla.equipment.LayerType;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The observing counterpart of {@link RendererContext#builder()}, for a test that asserts what a context
 * was asked rather than only what it answered. It wraps any context and forwards every lookup to it,
 * recording on the way; it sees only what reaches it, so a lookup the wrapped context answers by calling
 * itself goes unrecorded.
 * <p>
 * Every {@link #resolveTexture} call is recorded in order on {@link #getResolved()}, which is what makes a
 * kit's resolution order and per-layer id selection observable, and {@link #isArmorOverrideConsulted()}
 * separates an armour override lookup that answered {@link CitResult#NONE} from one that was never made at
 * all.
 * <p>
 * The equipment and armour-override answers are for a kit test that needs a lookup the builder does not
 * serve: {@link #answeringEquipment} fixes what every equipment lookup answers and
 * {@link #answeringArmorOverride} what every armour override lookup answers, and a lookup given no answer
 * forwards to the wrapped context.
 */
public final class RecordingContext implements RendererContext.Forwarding {

    /** the context every lookup forwards to */
    private final @NotNull RendererContext delegate;

    /** every texture id {@link #resolveTexture} has been asked for, in call order */
    @Getter private final @NotNull List<String> resolved = new ArrayList<>();

    /** whether {@link #resolveArmorTextureOverride} has been reached, however it answered */
    @Getter private boolean armorOverrideConsulted;

    /** the layers every equipment lookup answers with, whatever asset and layer type it names */
    private @NotNull Optional<List<EquipmentModel.Layer>> equipment = Optional.empty();

    /** the effect every armour override lookup answers with */
    private @NotNull Optional<CitResult> armorOverride = Optional.empty();

    private RecordingContext(@NotNull RendererContext delegate) {
        this.delegate = delegate;
    }

    /**
     * Wraps a context in a recorder that has recorded nothing and answers every lookup through it.
     *
     * @param delegate the context every lookup forwards to
     * @return a new recorder over the context
     */
    public static @NotNull RecordingContext over(@NotNull RendererContext delegate) {
        return new RecordingContext(delegate);
    }

    /**
     * Answers every equipment lookup with the given layers, whatever asset and layer type it names.
     *
     * @param layers the base-to-overlay layers every lookup resolves to
     * @return this recorder
     */
    public @NotNull RecordingContext answeringEquipment(@NotNull List<EquipmentModel.Layer> layers) {
        this.equipment = Optional.of(layers);
        return this;
    }

    /**
     * Answers every armour override lookup with the given effect.
     *
     * @param override the effect a hit resolves to, or {@link CitResult#NONE} for a miss
     * @return this recorder
     */
    public @NotNull RecordingContext answeringArmorOverride(@NotNull CitResult override) {
        this.armorOverride = Optional.of(override);
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull RendererContext delegate() {
        return this.delegate;
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull CitResult resolveArmorTextureOverride(
        @NotNull ArmorMaterial material, @NotNull LayerType layerType, @NotNull ItemContext item) {
        this.armorOverrideConsulted = true;
        return this.armorOverride.orElseGet(
            () -> this.delegate.resolveArmorTextureOverride(material, layerType, item));
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull List<EquipmentModel.Layer> resolveEquipmentLayers(
        @NotNull ResourceId assetId, @NotNull LayerType layerType) {
        return this.equipment.orElseGet(() -> this.delegate.resolveEquipmentLayers(assetId, layerType));
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Possible<PixelBuffer> resolveTexture(@NotNull String textureId) {
        this.resolved.add(textureId);
        return this.delegate.resolveTexture(textureId);
    }

}
