package lib.minecraft.renderer.options;

import lib.minecraft.renderer.request.DyeColor;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

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
     * Carried-block selector for entities with attached block overlays (snow golem's carved pumpkin,
     * mooshroom's mushrooms). Empty (default) renders the entity's authored blocks; {@code "none"}
     * drops them (a sheared snow golem), removing both their geometry and their canvas-bounds
     * contribution.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<String> carried = Optional.empty();

    /**
     * Dyed-collar colour for collar-bearing entities (wolf, cat). When present and the resolved
     * entity carries a collar texture, a collar overlay is drawn on the body geometry tinted by this
     * colour's {@link DyeColor#argb() ARGB}; empty (default) draws no collar.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<DyeColor> collar = Optional.empty();

    /**
     * Wool colour for dyeable-wool entities (sheep). When present and the resolved entity carries a
     * {@code tint_by: wool_color} overlay, that overlay is multiplied by this colour's
     * {@link DyeColor#argb() ARGB} instead of its baked default white-wool tint; empty (default)
     * renders the entity's default wool colour.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<DyeColor> woolColor = Optional.empty();

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
