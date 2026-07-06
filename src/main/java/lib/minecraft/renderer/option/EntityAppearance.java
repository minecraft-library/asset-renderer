package lib.minecraft.renderer.option;

import lib.minecraft.renderer.request.DyeColor;
import lib.minecraft.renderer.request.TintAxis;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The entity-specific axis selections for a single {@code EntityRenderer} invocation, held as one
 * cohesive value on {@link EntityOptions#getAppearance()} so {@code EntityOptions} does not accrete a
 * loose field per axis. Each selection maps onto the {@code entity_models.json} family form: the
 * typed {@link #getAge() age} axis, the option-sourced dyed {@link #getCollar() collar} tint, and the
 * id-encoded / option-encoded string axes ({@link #getState() state}, {@link #getCarried() carried})
 * whose valid values are declared per-entity in the family JSON rather than a hard-coded enum.
 *
 * <p>Every axis is empty / default unless explicitly set, so the default appearance leaves a render
 * byte-identical to one built without any appearance at all. An axis a given entity does not support
 * is simply ignored at render (an unknown {@code state} falls back to the default texture).
 */
@Getter
@Builder(toBuilder = true)
public class EntityAppearance {

    /**
     * Age selector. {@link Age#BABY} renders the entity's distinct baby mesh when it has one;
     * {@link Age#ADULT} (default) renders the adult mesh. Only affects entities with a dedicated
     * baby model.
     */
    @lombok.Builder.Default
    private final @NotNull Age age = Age.ADULT;

    /**
     * Behavioural-state selector for entities that ship per-state textures (wolf {@code "tame"} /
     * {@code "angry"}; the default {@code "wild"} is equivalent to empty). Swaps the base texture to
     * the matching state entry when the resolved entity carries one, else the default texture is
     * used.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<String> state = Optional.empty();

    /**
     * Carried-block selector. Two roles depending on the entity's block overlays:
     *
     * <ul>
     *   <li>For always-present body decorations (snow golem's carved pumpkin, mooshroom's
     *       mushrooms): empty (default) renders the authored blocks; {@code "none"} drops them (a
     *       sheared snow golem), removing both their geometry and their canvas-bounds contribution.</li>
     *   <li>For caller-selected held blocks (enderman carried block, iron golem flower): a block id
     *       ({@code "minecraft:poppy"}) renders that block in the entity's selectable overlay slot;
     *       empty (default) and {@code "none"} draw no held block, matching vanilla's empty-handed
     *       default. See {@link #selectedCarriedBlock()}.</li>
     * </ul>
     */
    @lombok.Builder.Default
    private final @NotNull Optional<String> carried = Optional.empty();

    /**
     * The selected dye per {@link TintAxis tint axis} - the body base tint ({@link TintAxis#BASE},
     * tropical fish) and each named overlay tint ({@link TintAxis#WOOL} sheep wool,
     * {@link TintAxis#PATTERN} tropical fish pattern, {@link TintAxis#COLLAR} wolf / cat collar).
     * An axis absent from the map uses its target's baked default (the family {@code base_tint} or
     * the overlay's {@code tint_color}), so the default appearance renders byte-identically; a
     * present axis multiplies its target by the dye's {@link DyeColor#argb() ARGB}. One map rather
     * than a loose {@link Optional} field per dye axis - see {@link TintAxis}.
     */
    @lombok.Builder.Default
    private final @NotNull Map<TintAxis, DyeColor> tints = Map.of();

    /**
     * Whether the entity renders sheared. When {@code true} the resolved definition drops its
     * shearable overlays (the sheep wool) - both the rendered geometry and its canvas-bounds
     * contribution; {@code false} (default) renders the entity's wool.
     */
    @lombok.Builder.Default
    private final boolean sheared = false;

    /**
     * The set of bone-toggle names to un-hide for entities with toggleable bones (donkey / mule /
     * llama {@code chest}). Each name matches a family-form {@code bone_toggles} key; a toggle a
     * given entity does not declare is ignored. Empty (default) leaves every toggleable bone hidden.
     */
    @lombok.Builder.Default
    private final @NotNull Set<String> toggles = Set.of();

    /**
     * Equipment selection keyed by slot ({@code saddle} / {@code body}) for entities with equipment
     * overlays (pig/horse/camel/strider/happy_ghast/nautilus saddle; horse/nautilus/wolf armor). The
     * value is the material/asset ({@code leather}, {@code iron}, {@code diamond}; {@code saddle} for
     * the single saddle item), or an empty string to use the layer's default material (leather armor,
     * the saddle). A slot a given entity does not offer is ignored; empty (default) renders no
     * equipment. See {@link #equipmentMaterial(String)}.
     */
    @lombok.Builder.Default
    private final @NotNull java.util.Map<String, String> equipment = java.util.Map.of();

    /**
     * The dye selected for a {@link TintAxis tint axis}, or empty when the axis uses its baked
     * default.
     *
     * @param axis the tint axis to look up
     * @return the selected dye for {@code axis}, or empty
     */
    public @NotNull Optional<DyeColor> tint(@NotNull TintAxis axis) {
        return Optional.ofNullable(this.tints.get(axis));
    }

    /**
     * Whether this appearance selects the baby mesh.
     *
     * @return {@code true} when {@link #getAge() age} is {@link Age#BABY}
     */
    public boolean isBaby() {
        return this.age == Age.BABY;
    }

    /**
     * Whether the carried block overlays should be dropped (a sheared snow golem, an empty-handed
     * enderman).
     *
     * @return {@code true} when {@link #getCarried() carried} is {@code "none"}
     */
    public boolean dropsCarried() {
        return this.carried.filter("none"::equals).isPresent();
    }

    /**
     * The block id to render in a {@code selectable} block overlay (enderman carried block, iron
     * golem flower), or empty when no held block is selected. A selectable overlay renders only when
     * this is present; the default (empty) and {@code "none"} both leave the entity empty-handed.
     *
     * @return the selected carried block id, or empty for the default / dropped state
     */
    public @NotNull Optional<String> selectedCarriedBlock() {
        return this.carried.filter(id -> !"none".equals(id));
    }

    /**
     * The selected material for an equipment {@code slot}, or empty when the slot is not equipped.
     * A present-but-blank value means "use the layer's default material" (leather armor, the saddle).
     *
     * @param slot the equipment slot ({@code saddle} / {@code body})
     * @return the selected material (possibly blank for "default"), or empty when the slot is unequipped
     */
    public @NotNull Optional<String> equipmentMaterial(@NotNull String slot) {
        return Optional.ofNullable(this.equipment.get(slot));
    }

    /**
     * Builds an appearance with every axis at its default (adult, no state / carried / collar).
     *
     * @return the default appearance
     */
    public static @NotNull EntityAppearance defaults() {
        return builder().build();
    }

    /**
     * Opens a builder seeded from this instance's current values, for deriving a variant with a few
     * axes changed.
     *
     * @return a builder pre-populated from this instance
     */
    public @NotNull EntityAppearanceBuilder mutate() {
        return this.toBuilder();
    }

}
