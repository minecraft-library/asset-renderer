package lib.minecraft.renderer.asset;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A fully-parsed entity definition - geometry and vanilla texture reference - for use by
 * the entity renderer's {@code ENTITY_3D} mode. Player skins are never stored on this DTO; they
 * are supplied at render time through the {@code EntityOptions.skinBytes}/{@code skinUrl}/
 * {@code skinTextureId} fields.
 */
@Getter
@AllArgsConstructor
@EqualsAndHashCode
public final class Entity {

    /**
     * The entity's namespaced identifier (e.g. {@code minecraft:zombie}).
     */
    private final @NotNull ResourceId id;

    /**
     * The entity's base bone/cube geometry.
     */
    private final @NotNull EntityModelData model;

    /**
     * The vanilla {@code textures/entity/} sub-path (without {@code .png}), or empty when the
     * entity has no default texture binding. Resolved at render time through the active pack
     * stack as {@code minecraft:entity/<ref>}.
     */
    private final @NotNull Optional<String> textureRef;

    /**
     * Additional geometry/texture pairs rendered on top of the base {@link #model} in declared
     * order. Drives layered entities that vanilla composes from multiple Java layers - charged
     * creeper's translucent armor mesh over the base creeper, copper golem holding a flower mesh
     * on top of the body. Each layer is built with the same auto-fit transform as the base so
     * coordinates stay co-registered after the unit-cube fit.
     */
    private final @NotNull ConcurrentList<Layer> overlays;

    /**
     * One overlay layer attached to an {@link Entity}. Carries an independent bone tree and its
     * own bundled texture sub-path; combined with the base model under one shared auto-fit
     * transform at render time.
     *
     * @param model the overlay's bone/cube tree, in the same Y-down entity-root coordinate frame
     *     as the base model so the layers register without per-overlay placement
     * @param textureRef the vanilla {@code textures/entity/} sub-path (without {@code .png}),
     *     resolved through the active pack stack as {@code minecraft:entity/<ref>}, or empty when the
     *     overlay reuses the base texture
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
