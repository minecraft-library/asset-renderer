package lib.minecraft.renderer.asset.equipment;

import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The parsed client-side equipment model for one equipment asset (a {@code minecraft:iron} /
 * {@code minecraft:leather} / {@code minecraft:elytra} asset id), mirroring vanilla's
 * {@code EquipmentClientInfo}. Maps each render {@link LayerType} to the ordered {@link Layer} list
 * composited for that slot - a single layer for a flat material, a dye-tinted base plus an undyed
 * overlay for leather.
 *
 * @param layers the render layers per layer type, in back-to-front composite order within each type
 */
public record EquipmentModel(@NotNull Map<LayerType, List<Layer>> layers) {

    /**
     * The absent-asset sentinel: zero layers for every layer type, so an unresolvable asset id
     * resolves to nothing rather than a load failure (matching the no-missing-texture-fallback
     * contract).
     */
    public static final @NotNull EquipmentModel MISSING = new EquipmentModel(Map.of());

    /**
     * Returns the ordered render layers for a layer type, an empty list when this model declares none.
     *
     * @param type the layer type to look up
     * @return the layers for {@code type}, or an empty list
     */
    public @NotNull List<Layer> getLayers(@NotNull LayerType type) {
        return this.layers.getOrDefault(type, List.of());
    }

    /**
     * One texture layer of an equipment model: a bare texture stem resolved under a layer type's
     * subdir, an optional dye tint, and the elytra-only player-texture flag.
     *
     * @param texture the bare texture stem id (e.g. {@code minecraft:iron}, {@code minecraft:leather_overlay}),
     *     resolved to a full path by {@link #textureLocation(LayerType)}
     * @param dyeable the dye tint applied to this layer, or empty for an untinted layer
     * @param usePlayerTexture whether this layer draws from the wearer's own texture rather than the
     *     equipment asset (elytra wings use the player cape / elytra texture); ignored on a headless render
     */
    public record Layer(
        @NotNull ResourceId texture,
        @NotNull Optional<Dyeable> dyeable,
        boolean usePlayerTexture
    ) {

        /**
         * Resolves the renderer texture id for this layer under a layer type, prepending the
         * {@code entity/equipment/<layer>/} subdir to the bare stem so {@code minecraft:iron} under
         * {@link LayerType#HUMANOID} becomes {@code minecraft:entity/equipment/humanoid/iron}.
         *
         * @param type the layer type whose subdir the texture sits under
         * @return the resolvable namespaced texture id
         */
        public @NotNull ResourceId textureLocation(@NotNull LayerType type) {
            return new ResourceId(this.texture.namespace(), "entity/equipment/" + type.getId() + "/" + this.texture.name());
        }

    }

    /**
     * The dye tint of a {@link Layer}: the ARGB fallback used when the wearer supplies no explicit
     * dye. An empty {@link #colorWhenUndyed} marks a layer that renders only once dyed (the wolf /
     * armadillo-scute overlay) - undyed it resolves to colour 0 and is skipped.
     *
     * @param colorWhenUndyed the ARGB fallback tint, or empty for a render-only-when-dyed layer
     */
    public record Dyeable(@NotNull Optional<Integer> colorWhenUndyed) {}

}
