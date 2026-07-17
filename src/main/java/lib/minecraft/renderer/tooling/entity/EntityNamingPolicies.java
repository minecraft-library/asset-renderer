package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Declared naming conventions and tuning epsilons for the entity tooling flow - Mojang naming
 * conventions with no more-principled source, declared fallbacks behind derivations, and
 * heuristic thresholds. Never fetches ({@code PolicyPurityTest}).
 */
enum EntityNamingPolicies implements NavigationPolicy {

    /**
     * The synthetic geometry-source id scheme ({@code #large} / {@code #baby} /
     * {@code __overlay_<FIELD>}), retired now that factory-coordinate keys generate ids
     * instead. The entry is kept so the retirement is recorded, not silent.
     */
    SYNTHETIC_SOURCE_IDS(
        "retired-by-key-grammar",
        "P2: legacy tooling-own id vocabulary (ToolingEntityModels:376,425,444) - dissolved by"
            + " GeometryIds factory-coordinate keys (decision 15); no runtime consumer"),

    /**
     * The entity {@link lib.minecraft.renderer.tooling.geometry.GeometryRequest} coordinate
     * convention: {@code YAxis.DOWN}, no inventory rotation, {@code null} refParam. A vanilla
     * authoring convention, not per-class bytecode - baked into the entity request factories
     * ({@code GeometryRequest.body} / {@code shape} / {@code overlay} / {@code equipment}),
     * declared here.
     */
    ENTITY_REQUEST_CONVENTION(
        "YAxis.DOWN, inventoryYRotation=0, refParam=null",
        "P3: vanilla entity models author Y-down with no GUI facts (legacy EntitySourceFactory:96-97)"),

    /**
     * The uniform-scale tolerance for {@code poseStack.scale(F,F,F)} triples: vanilla
     * writes literal uniform triples; drift beyond this implies a non-uniform expression
     * treated as identity.
     */
    UNIFORM_SCALE_TOLERANCE(
        1e-5f,
        "P4: heuristic epsilon (legacy EntityRendererOverrides:72)"),

    /**
     * The baby naming fallbacks: the {@code _BABY} {@code ModelLayers} field-name suffix
     * (when the isBaby dataflow misses) and the {@code <adult>_baby} texture-sibling suffix
     * (existence-probed). Declared fallback, never silent - each take logs at INFO.
     */
    BABY_NAMING_FALLBACK(
        "_BABY / _baby",
        "P10: vanilla baby naming convention, demoted to fallback behind D26/D36"
            + " (legacy EntityLayerDefinitionResolver:252, EntityFamilyJsonWriter:176)"),

    /**
     * The {@code $Variant;} descriptor suffix + {@code DEFAULT} constant selection the
     * block-overlay resolver anchors on.
     */
    VARIANT_DESCRIPTOR_SUFFIX(
        "$Variant;",
        "P13: stable Mojang naming convention (legacy EntityBlockOverlayResolver:234,665)"),

    /**
     * The {@code XP} / {@code XN} / {@code YP} / {@code YN} axis field-name parsing on
     * block-overlay transforms; a {@code Z}-axis name is diagnosed, never silently skipped.
     */
    AXIS_FIELD_NAMES(
        List.of("XP", "XN", "YP", "YN"),
        "P14: vanilla direction-constant naming (legacy EntityBlockOverlayResolver:447-453)"),

    /**
     * The {@code "DEFAULT"} enum-field anchor: variant holder classes
     * ({@code WolfVariants.DEFAULT}) and variant enums ({@code Axolotl$Variant.DEFAULT})
     * bind their canonical default under this static name. Supplied as a parameter to the
     * {@code AsmKit.findEnumDefaultName} primitive so the kit itself stays vanilla-agnostic.
     */
    ENUM_DEFAULT_FIELD(
        "DEFAULT",
        "P15: Mojang naming convention baked into a kit parameter (legacy AsmKit:473)"),

    /**
     * The getter-name / snake_case bone-name fallbacks when the {@code getChild} trace fails.
     */
    BONE_NAME_FALLBACKS(
        "get<Bone>() / snake_case",
        "P16: declared fallback behind the getChild trace (legacy EntityBlockOverlayResolver:518-520,550)"),

    /**
     * The data-driven-variant detection suffix policy: the state field's owner class ends
     * {@code Variant} and the texture accessors are {@code modelAndTexture} / {@code *Texture}.
     * Generalizable but convention-anchored - the texture resolver's data-driven-chain gate
     * consults it.
     */
    DATA_VARIANT_SUFFIXES(
        List.of("Variant", "modelAndTexture", "Texture"),
        "P18: declared suffix policy (legacy EntityTextureResolver:351-353)"),

    /**
     * The derived non-base-suffix audit threshold: a texture suffix qualifies when a base +
     * suffixed sibling pair co-exists in at least this many distinct texture directories
     * (state overlays recur; data-variant names appear once).
     */
    SUFFIX_MIN_RECURRENCE(
        2,
        "P19: tuning parameter (legacy EntityTextureResolver:816); the derivation proved"
            + " set-equal to the hand-maintained 14-entry list on 26.1 [D27]"),

    /**
     * The variant-enum naming-convention family: the {@code <X>Variants.class} holder stem,
     * the {@code $ModelType} inner-enum suffix, and the {@code directoryFor} class-to-data-directory
     * mapping ({@code CowVariant} to {@code data/minecraft/cow_variant/}). One declared policy,
     * validated against directory existence at consult sites.
     */
    VARIANT_ENUM_CONVENTIONS(
        List.of("Variants", "$ModelType", "Variant"),
        "P20: naming-convention family (legacy EntityTextureResolver:1135;"
            + " EntityVariantResolver:253,371,523-533)"),

    /**
     * The entityId-basename texture match for shared renderers: a {@code <clinit>} texture
     * field whose path basename equals {@code /<entityId>.png} is that entity's branch
     * (PiglinRenderer serves piglin + piglin_brute).
     */
    ENTITY_ID_BASENAME_MATCH(
        "/<entityId>.png",
        "P21: id-to-texture naming convention (legacy EntityTextureResolver:1229)"),

    /**
     * The enum-default stem texture convention: {@code <entity>/<entity>_<default_lowercase>}
     * names the canonical texture of a pure-enum-DEFAULT entity (axolotl {@code lucy}).
     * Existence-gated at the consult site, so only conforming entities take it.
     */
    ENUM_DEFAULT_STEM(
        "<entity>/<entity>_<default>",
        "P24: naming convention, self-limiting via the jar existence gate (legacy EntitySessionWalk:100-104)"),

    /**
     * The {@code left_} / {@code right_} toggle-stem grouping: vanilla names symmetric bones
     * with these prefixes, so a stripped pair flips under one toggle ({@code left_horn} +
     * {@code right_horn} to {@code horn}).
     */
    LEFT_RIGHT_STEMS(
        List.of("left_", "right_"),
        "P25: vanilla symmetric-bone naming (legacy EntityBoneResolver:404-406)");

    private final @NotNull Object value;
    private final @NotNull String provenance;

    EntityNamingPolicies(@NotNull Object value, @NotNull String provenance) {
        this.value = value;
        this.provenance = provenance;
    }

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
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
     * The declared float fact of a float-valued row.
     */
    float floatValue() {
        return (Float) this.value;
    }

    /**
     * The declared int fact of an int-valued row.
     */
    int intValue() {
        return (Integer) this.value;
    }

}
