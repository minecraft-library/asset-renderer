package lib.minecraft.renderer.asset;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * A fully-parsed entity definition - geometry and Bedrock-sourced texture reference - for use by
 * the entity renderer's {@code ENTITY_3D} mode. Player skins are never stored on this DTO; they
 * are supplied at render time through the {@code EntityOptions.skinBytes}/{@code skinUrl}/
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

    /**
     * The bundled texture sub-path under {@code /lib/minecraft/renderer/entity_textures/}
     * (without {@code .png}), or empty when the entity has no default texture binding. Refers
     * to a classpath PNG copied verbatim from the Bedrock resource pack by
     * {@code ToolingEntityModels} - no Java atlas involvement.
     */
    private @NotNull Optional<String> textureRef = Optional.empty();

    /**
     * Additional geometry/texture pairs rendered on top of the base {@link #model} in declared
     * order. Drives layered entities that vanilla composes from multiple Java layers - charged
     * creeper's translucent armor mesh over the base creeper, copper golem holding a flower mesh
     * on top of the body. Each layer is built with the same auto-fit transform as the base so
     * coordinates stay co-registered after the unit-cube fit.
     */
    private @NotNull ConcurrentList<Layer> overlays = Concurrent.newList();

    /**
     * When {@code true} the entity's bundled base texture has every partial-alpha texel bumped
     * to {@code alpha=255} at load time. Used by entities whose Bedrock texture is authored at
     * low alpha for an additive-blending pass that the static iso renderer can't reproduce
     * (blaze rods at alpha=90, magma cube), or for aesthetic partial alpha that should render
     * opaque under this renderer's binary-canvas convention (sheep wool fluff). Replaces the
     * legacy {@code ToolingEntityModels.OPAQUE_ALPHA_TEXTURE_REFS} hardcoded set with a
     * per-entity opt-in, so the bundled PNG stays exactly as Bedrock ships it.
     */
    private boolean forceOpaque = false;

    /**
     * Convenience constructor for the no-overlay case so existing call sites don't have to
     * supply an empty list.
     */
    public Entity(
        @NotNull String id,
        @NotNull String namespace,
        @NotNull String name,
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureRef
    ) {
        this(id, namespace, name, model, textureRef, Concurrent.newList(), false);
    }

    /**
     * Convenience constructor for entities with overlays but no force-opaque flag.
     */
    public Entity(
        @NotNull String id,
        @NotNull String namespace,
        @NotNull String name,
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureRef,
        @NotNull ConcurrentList<Layer> overlays
    ) {
        this(id, namespace, name, model, textureRef, overlays, false);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Entity entity = (Entity) o;
        return Objects.equals(this.getId(), entity.getId())
            && Objects.equals(this.getNamespace(), entity.getNamespace())
            && Objects.equals(this.getName(), entity.getName())
            && Objects.equals(this.getModel(), entity.getModel())
            && Objects.equals(this.getTextureRef(), entity.getTextureRef())
            && Objects.equals(this.getOverlays(), entity.getOverlays())
            && this.isForceOpaque() == entity.isForceOpaque();
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getId(), this.getNamespace(), this.getName(), this.getModel(), this.getTextureRef(), this.getOverlays(), this.isForceOpaque());
    }

    /**
     * One overlay layer attached to an {@link Entity}. Carries an independent bone tree and its
     * own bundled texture sub-path; combined with the base model under one shared auto-fit
     * transform at render time.
     *
     * @param model the overlay's bone/cube tree, in the same Bedrock-native coordinate frame as
     *     the base model so the layers register without per-overlay placement
     * @param textureRef the bundled texture sub-path under
     *     {@code /lib/minecraft/renderer/entity_textures/} (without {@code .png}), or empty when
     *     the overlay reuses the base texture
     * @param emissive when {@code true} the overlay renders full-bright + additive (vanilla
     *     Java's {@code RenderType.eyes} pattern - spider eyes, ender dragon eyes) instead of
     *     the default shaded src-over. Tagged through every triangle the overlay produces; the
     *     rasterizer keys off the per-triangle flag to pick blend mode and skip the ambient
     *     shading pass
     */
    public record Layer(
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureRef,
        boolean emissive
    ) {

        /**
         * Convenience constructor for non-emissive overlays - the common case ({@code emissive}
         * defaults to {@code false}).
         */
        public Layer(@NotNull EntityModelData model, @NotNull Optional<String> textureRef) {
            this(model, textureRef, false);
        }

    }

}
