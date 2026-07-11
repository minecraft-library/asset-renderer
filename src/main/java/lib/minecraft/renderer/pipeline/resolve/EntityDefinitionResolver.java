package lib.minecraft.renderer.pipeline.resolve;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.option.AppearanceGate;
import lib.minecraft.renderer.option.EntityAppearance;
import lib.minecraft.renderer.option.TintAxis;
import lib.minecraft.renderer.option.TropicalFishPattern;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Entity.BlockOverlayLayer;
import lib.minecraft.renderer.asset.Entity.BoneToggle;
import lib.minecraft.renderer.asset.Entity.LargeShape;
import lib.minecraft.renderer.asset.Entity.OverlayLayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Folds an {@link EntityAppearance}'s render-axis selections into a single resolved
 * {@link Entity} the renderer iterates unconditionally, with no scattered {@code !baby}
 * gates. This is the render-time policy that used to live on {@code Entity.resolveFor} in
 * the loader - relocated to a renderer-owned home so the reader stays a pure v2 -&gt; data mapper.
 *
 * <p>The nine axis semantics apply in a fixed short-circuit order (byte-identical to the historic
 * fold): (1) a baby swaps in the baby mesh and DROPS overlays / block overlays / collar / equipment -
 * each carries adult geometry that would render adult-sized around the smaller baby body, and the
 * whole non-baby branch is skipped; else (2) sheared drops the wool overlay and charged gates the
 * swirl; (3) the sheared axis additionally activates a {@code "sheared"} bone toggle (bogged); (4)
 * selected bone toggles flip their bones' visibility (donkey / mule / llama chest reveal, goat horns
 * hide); (5) block overlays resolve against the carried selection; (6) the shape axis swaps to the
 * tropical-fish large body; (7) the size axis swaps to the selected size's mesh (pufferfish); (8) the
 * size axis multiplies the render scale (salmon / slime / magma_cube); (9) the base-color axis
 * overrides the baked base tint (tropical-fish dye), applied OUTSIDE the baby fork so it affects both.
 * A non-baby, non-carried appearance returns an equivalent definition unchanged.
 */
public final class EntityDefinitionResolver {

    private EntityDefinitionResolver() {}

    /**
     * Resolves a definition for the given appearance, folding the render-axis policy into the returned
     * definition (see the class documentation for the nine semantics and their order).
     *
     * @param definition the base definition loaded for the entity id
     * @param appearance the axis selections to resolve against
     * @return the age / carried / sheared / shape / size / tint-resolved definition
     */
    public static @NotNull Entity resolve(@NotNull Entity base, @NotNull EntityAppearance appearance) {
        // Variant fold (option-encoded coat / colour, axis-unification #3): a selected variant resolves
        // against that option's fully-built sub-definition - byte-identical to the id-encoded pseudo-id it
        // replaced - so every later axis (baby / size / tint) folds on top exactly as it did when each coat
        // was a first-class pseudo-id. Absent, an unknown option, and the id-encoded / non-variant case
        // (empty variants map) all keep the family default coat, so the default appearance is byte-identical.
        Entity definition = appearance.getVariant()
            .map(coat -> base.axes().variants().getOrDefault(coat, base))
            .orElse(base);
        Entity.EntityBuilder builder = definition.toBuilder();
        if (appearance.isBaby() && definition.axes().babyModel().isPresent()) {
            builder.model(definition.axes().babyModel().get()).overlays(List.of()).blockOverlays(List.of())
                .layers(new Entity.Layers(Optional.empty(), List.of(), definition.layers().markings(), definition.layers().humanoidArmor(), definition.layers().markingTextures()));
        } else {
            // Drop overlays the appearance doesn't activate: shearable overlays (the sheep wool) when
            // sheared - both the rendered geometry and its canvas-bounds contribution - and charged-only
            // overlays (the creeper energy swirl) unless the charged axis is set. A charged overlay renders
            // only for a lightning-struck entity, so the default (uncharged) render is byte-identical. Only
            // rebuilds the list when there is something to drop, so an entity with no shearable / charged
            // overlay keeps its exact overlay list.
            boolean hasCharged = definition.overlays().stream()
                .anyMatch(overlay -> overlay.gate().filter(gate -> gate instanceof AppearanceGate.ChargedGate).isPresent());
            if (appearance.isSheared() || hasCharged)
                builder.overlays(definition.overlays().stream()
                    .filter(overlay -> rendersAtResolve(overlay, appearance))
                    .toList());
            // Selected bone toggles flip their bones' visibility (donkey/mule/llama chest reveal, goat
            // horns hide). Guarded to the non-baby path - the baby mesh has its own bones. The sheared axis
            // additionally activates the "sheared" toggle for entities that declare one (bogged drops its
            // mushrooms); entities whose sheared handling is overlay-only (sheep wool) declare no such
            // toggle and are left byte-identical.
            Set<String> selectedToggles = appearance.getToggles();
            if (appearance.isSheared() && definition.boneToggles().containsKey("sheared")) {
                selectedToggles = new LinkedHashSet<>(selectedToggles);
                selectedToggles.add("sheared");
            }
            EntityModelData toggled = applyBoneToggles(definition, selectedToggles);
            if (toggled != null) builder.model(toggled);
            builder.blockOverlays(resolveBlockOverlays(definition, appearance));
            // The shape axis (tropical fish) swaps to the large body when the selected pattern's Shape is
            // large: the large mesh, tropical_b base texture, and the pattern overlays cloned onto the
            // large geometry (the pattern axis still picks the concrete overlay texture via texture_by). A
            // small/default pattern leaves the small body untouched, so the default render is byte-identical.
            if (definition.axes().largeShape().isPresent()
                && appearance.getPattern().map(p -> p.shape() == TropicalFishPattern.Shape.LARGE).orElse(false)) {
                LargeShape large = definition.axes().largeShape().get();
                builder.model(large.model()).textureRef(large.textureRef()).overlays(large.overlays());
            }
            // The size axis (pufferfish) swaps to the selected size's distinct baked mesh. An unset size, or
            // the entity's default size (pufferfish large = the base mesh, absent from the map), leaves the
            // base model untouched, so the default render is byte-identical.
            appearance.getSize().map(definition.axes().sizeModels()::get).ifPresent(builder::model);
            // The size axis (salmon / slime / magma_cube) instead multiplies rendererScale by the selected
            // size's factor. An unset / default size (scale 1.0, absent from the map) leaves rendererScale
            // untouched, so the default render is byte-identical. A uniform scale is a visual no-op under the
            // auto-fit renderer (self-similar); the factor is applied for a future absolute-scale renderer.
            appearance.getSize().map(definition.axes().sizeScales()::get)
                .ifPresent(scale -> builder.rendererScale(definition.rendererScale() * scale));
        }
        // The base_color axis (tropical fish) overrides the family base_tint with the selected dye; absent
        // (default) keeps the baked base_tint, so the default render is byte-identical.
        appearance.tint(TintAxis.BASE).ifPresent(color -> builder.baseTintArgb(color.argb()));
        return builder.build();
    }

    /**
     * Whether an overlay survives the resolve-stage gate filter: an unconditional or tint-gated overlay
     * is kept here (a {@link AppearanceGate.TintedGate} is evaluated at render, mirroring the historic
     * two-stage split), while a flag / charged gate that fails for this appearance drops the overlay
     * (the sheared wool, the uncharged creeper swirl).
     *
     * @param overlay the overlay to test
     * @param appearance the render-axis selections
     * @return {@code true} when the overlay is kept in the resolved list
     */
    private static boolean rendersAtResolve(@NotNull OverlayLayer overlay, @NotNull EntityAppearance appearance) {
        return overlay.gate()
            .filter(gate -> !(gate instanceof AppearanceGate.TintedGate))
            .map(gate -> gate.test(appearance))
            .orElse(true);
    }

    /**
     * Resolves the definition's block overlays against the appearance's carried selection. A
     * <b>fixed</b> overlay (mooshroom mushrooms, snow golem pumpkin) is kept unless {@code carried ==
     * "none"} drops it; a <b>selectable</b> overlay (enderman carried block, iron golem flower) is kept
     * only when a block is selected, with its block id replaced by that selection. The default (empty)
     * appearance therefore renders the fixed decorations and no selectable held block.
     */
    private static @NotNull List<BlockOverlayLayer> resolveBlockOverlays(@NotNull Entity definition, @NotNull EntityAppearance appearance) {
        if (definition.blockOverlays().isEmpty()) return definition.blockOverlays();
        Optional<String> selected = appearance.selectedCarriedBlock();
        boolean dropsFixed = appearance.dropsCarried();
        List<BlockOverlayLayer> out = new ArrayList<>(definition.blockOverlays().size());
        for (BlockOverlayLayer overlay : definition.blockOverlays()) {
            if (overlay.selectable())
                selected.ifPresent(id -> out.add(overlay.withBlockId(id)));
            else if (!dropsFixed)
                out.add(overlay);
        }
        return List.copyOf(out);
    }

    /**
     * Rebuilds the definition's model with the appearance's selected bone toggles flipped, or
     * {@code null} when no selected toggle applies (leaving the default model). A default-hidden toggle
     * re-adds its bones (chest); a default-visible toggle removes them (goat horns). Re-added bones'
     * parents are already present, so the kit resolves them by name; the rebuilt model grows / shrinks
     * the canvas bounds automatically.
     */
    private static @Nullable EntityModelData applyBoneToggles(@NotNull Entity definition, @NotNull Set<String> toggles) {
        if (toggles.isEmpty() || definition.boneToggles().isEmpty()) return null;
        LinkedHashMap<String, EntityModelData.Bone> bones = null;
        for (String toggle : toggles) {
            BoneToggle spec = definition.boneToggles().get(toggle);
            if (spec == null || spec.bones().isEmpty()) continue;
            if (bones == null) bones = new LinkedHashMap<>(definition.model().getBones());
            if (spec.defaultVisible())
                spec.bones().keySet().forEach(bones::remove);
            else
                bones.putAll(spec.bones());
        }
        if (bones == null) return null;
        return new EntityModelData(
            definition.model().getTextureWidth(), definition.model().getTextureHeight(),
            definition.model().getInventoryYRotation(), Concurrent.adoptLinkedMap(bones), definition.model().isCull());
    }
}
