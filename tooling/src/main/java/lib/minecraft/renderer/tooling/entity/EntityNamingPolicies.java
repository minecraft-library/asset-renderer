package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The entity tooling flow's declared naming conventions and tuning thresholds - Mojang naming
 * conventions with no more-principled source, and the thresholds a derivation is judged
 * against - alongside the coordinate of the one epsilon vanilla carries itself. Never fetches
 * ({@code PolicyPurityTest}).
 *
 * <p>A row whose fact sits at a walkable member declares that {@link Navigation} coordinate in
 * place of a value; the consuming resolver re-enters the engine there and reads it.
 */
enum EntityNamingPolicies implements NavigationPolicy {

    /**
     * The uniform-scale tolerance for {@code poseStack.scale(F,F,F)} triples: vanilla
     * writes literal uniform triples; drift beyond this implies a non-uniform expression
     * treated as identity. The coordinate names the field the tolerance is read off.
     */
    UNIFORM_SCALE_TOLERANCE(
        new Navigation.At(VanillaSourceClasses.Types.MTH, "EPSILON", "F"),
        "the float ConstantValue on Mth.EPSILON - the tolerance vanilla's own Mth.equal comparison holds a"
            + " difference to"),

    /**
     * The {@code $Variant;} descriptor suffix + {@code DEFAULT} constant selection the
     * block-overlay resolver anchors on.
     */
    VARIANT_DESCRIPTOR_SUFFIX(
        "$Variant;",
        "stable Mojang naming convention"),

    /**
     * The {@code "DEFAULT"} enum-field anchor: variant holder classes
     * ({@code WolfVariants.DEFAULT}) and variant enums ({@code Axolotl$Variant.DEFAULT})
     * bind their canonical default under this static name. Supplied as a parameter to the
     * {@code AsmWalker.findEnumDefaultName} primitive so the kit itself stays vanilla-agnostic.
     */
    ENUM_DEFAULT_FIELD(
        "DEFAULT",
        "Mojang naming convention baked into a kit parameter"),

    /**
     * The data-driven-variant detection suffix policy: the state field's owner class ends
     * {@code Variant} and the texture accessors are {@code modelAndTexture} / {@code *Texture}.
     * Generalizable but convention-anchored - the texture resolver's data-driven-chain gate
     * consults it.
     */
    DATA_VARIANT_SUFFIXES(
        List.of("Variant", "modelAndTexture", "Texture"),
        "declared suffix policy"),

    /**
     * The derived non-base-suffix audit threshold: a texture suffix qualifies when a base +
     * suffixed sibling pair co-exists in at least this many distinct texture directories
     * (state overlays recur; data-variant names appear once).
     */
    SUFFIX_MIN_RECURRENCE(
        2,
        "tuning parameter; the derivation proved set-equal to the hand-maintained 14-entry list on 26.1"),

    /**
     * The {@code left_} / {@code right_} toggle-stem grouping: vanilla names symmetric bones
     * with these prefixes, so a stripped pair flips under one toggle ({@code left_horn} +
     * {@code right_horn} to {@code horn}).
     */
    LEFT_RIGHT_STEMS(
        List.of("left_", "right_"),
        "vanilla symmetric-bone naming");

    private final @NotNull Object value;
    private final @NotNull String provenance;

    EntityNamingPolicies(@NotNull Object value, @NotNull String provenance) {
        this.value = value;
        this.provenance = provenance;
    }

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        if (this.value instanceof Navigation coordinate) return coordinate;
        return new Navigation.Value<>(this.value, this.provenance);
    }

    /**
     * The declared string fact of a string-valued row.
     */
    @NotNull String stringValue() {
        return (String) this.value;
    }

    /**
     * The declared string-list fact of a list-valued row.
     */
    @SuppressWarnings("unchecked")
    @NotNull List<String> strings() {
        return (List<String>) this.value;
    }

    /**
     * The declared bytecode coordinate of a coordinate-valued row.
     */
    Navigation.@NotNull At coordinate() {
        return (Navigation.At) this.value;
    }

    /**
     * The declared int fact of an int-valued row.
     */
    int intValue() {
        return (Integer) this.value;
    }

}
