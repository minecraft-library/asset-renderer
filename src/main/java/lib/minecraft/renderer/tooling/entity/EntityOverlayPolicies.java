package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Declared facts for the overlay / layer resolvers - the escape hatch the generic engines
 * consult only after a structural miss; never fetches ({@code PolicyPurityTest}).
 */
enum EntityOverlayPolicies implements NavigationPolicy {

    /**
     * The collar side-channel routing: a collar-shaped layer (submit reads a
     * null-gated {@code DyeColor} state field and re-renders the PARENT model) is a
     * {@code layers[]} row tinted at render from {@code collar_color}, never a composite
     * overlay - the generic gate would emit a coloured collar at zero state where vanilla
     * draws none.
     */
    COLLAR_ROUTING(
        Boolean.TRUE,
        "P6: CatCollarLayer bakes ModelLayers.CAT_COLLAR; state.collarColor == null draws none in"
            + " vanilla, so the collar rides the option-gated layers row (legacy EntityOverlayResolver:356-360)"),

    /**
     * The warden full-mesh-reuse judgment plus its frozen-alpha epsilon: an
     * emissive-provider layer whose frozen-frame alpha is {@code >= 0.999f} reuses the
     * FAMILY mesh (no subset, no alpha node) - exact only because the glow texture is
     * transparent outside its retained parts, a texture-content fact no bytecode walk sees.
     */
    FULL_MESH_REUSE_ALPHA_EPSILON(
        0.999f,
        "P7: warden bioluminescent full-mesh reuse is exact because the glow texture is transparent"
            + " outside the retained parts (texture content, not bytecode); epsilon relocated from"
            + " P47b per doc-12 P1/09 T1 (legacy EntityOverlayResolver:1908)"),

    /**
     * The equipment default-decor bounds exclusion: the trader-llama carpet overlay is
     * excluded from the canvas-fit silhouette, mirroring the vanilla-reference-harness's
     * {@code NO_RENDER_LAYER_SUFFIXES} treatment of {@code LlamaDecorLayer}.
     */
    DECOR_SKIP_BOUNDS(
        Boolean.TRUE,
        "P8: harness NO_RENDER_LAYER_SUFFIXES contract for LlamaDecorLayer - a harness-side fact,"
            + " not vanilla bytecode (legacy EntityOverlayResolver:463)"),

    /**
     * The eye-texture stem suffixes plus the first-literal default: a state-driven
     * emissive texture provider's zero-state texture is the FIRST {@code *_eyes.png} /
     * {@code *_eye.png} literal in the reachable {@code <clinit>}s (enum-like data classes
     * allocate their default-state instance first - copper golem UNAFFECTED).
     */
    EYE_STEM_FIRST_LITERAL(
        List.of("_eyes", "_eye"),
        "P11: naming convention + enum-decl-order default-first assumption for the copper-golem"
            + " zero-state pick (legacy EntityOverlayResolver:2225-2243)"),

    /**
     * The frozen-frame contract the alpha evaluation runs at: {@code ageInTicks == 0}
     * and every render-state animation field {@code 0}, the state the sibling harness's
     * {@code FreezeAnimationStateMixin} pins the reference render to.
     */
    FROZEN_FRAME(
        0f,
        "P12: harness FreezeAnimationStateMixin contract - alpha lambdas evaluate at ageInTicks=0,"
            + " state fields 0 (legacy EntityOverlayResolver:2070-2083)"),

    /**
     * The presence-gate fixed-vs-selectable semantics: a literal block bind gated on
     * an entity {@code ()Z} presence predicate ({@code hasPumpkin()}, default true) is a
     * FIXED always-present decoration; a timer-gated or literal-less bind
     * ({@code offerFlowerTick > 0}, {@code getCarriedBlock()}) is a SELECTABLE held block
     * the render-time caller supplies.
     */
    PRESENCE_GATE_FIXED_WHEN_GUARDED(
        Boolean.TRUE,
        "P17: hasPumpkin()-default-true vs offerFlowerTick>0-default-off vanilla semantics"
            + " (legacy EntityBlockOverlayResolver:239-263)"),

    /**
     * The multi-material equipment default picks: subdirs with more than one material
     * PNG default to the mapped material ({@code llama_body} white carpet,
     * {@code happy_ghast_body} white harness), else baseline dyeable leather. Sole-PNG
     * subdirs derive their default instead.
     */
    EQUIPMENT_DEFAULT_MATERIALS(
        Map.of("llama_body", "white", "happy_ghast_body", "white_harness"),
        "P23: genuine render-policy picks among the 16 dyes / harnesses; leather is the baseline"
            + " dyeable body material (legacy EntityEquipmentResolver:57-82, audit 03 rows 39/41/42)");

    private final @NotNull Object value;
    private final @NotNull String provenance;

    EntityOverlayPolicies(@NotNull Object value, @NotNull String provenance) {
        this.value = value;
        this.provenance = provenance;
    }

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        return new Navigation.Value<>(this.value, this.provenance);
    }

    /**
     * The declared float fact of a float-valued row.
     */
    float floatValue() {
        return (Float) this.value;
    }

    /**
     * The declared boolean fact of a boolean-valued row.
     */
    boolean booleanValue() {
        return (Boolean) this.value;
    }

    /**
     * The declared string-list fact of the eye-texture stem suffix row.
     */
    @SuppressWarnings("unchecked")
    @NotNull List<String> strings() {
        return (List<String>) this.value;
    }

    /**
     * The default material for an equipment texture subdir with multiple materials.
     *
     * @param subdir the equipment texture subdir ({@code llama_body})
     * @return the declared default material
     */
    @SuppressWarnings("unchecked")
    static @NotNull String defaultMaterialFor(@NotNull String subdir) {
        return ((Map<String, String>) EQUIPMENT_DEFAULT_MATERIALS.value).getOrDefault(subdir, "leather");
    }

}
