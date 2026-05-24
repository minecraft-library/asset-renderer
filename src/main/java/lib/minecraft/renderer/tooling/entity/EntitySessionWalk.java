package lib.minecraft.renderer.tooling.entity;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.zip.ZipFile;

/**
 * Per-entity walker that fans every binding-stage resolver out over a single
 * {@link EntityRegistryDiscovery.Result.Entry} and folds the per-entity outputs into one
 * {@link Result} record. Owns the per-entity orchestration so
 * {@link lib.minecraft.renderer.tooling.ToolingEntityModels#main(String[])} keeps to a
 * single loop over registry entries.
 *
 * <p>The walk sequence: texture binding ({@link EntityTextureResolver}) - variant-default
 * enum lookup ({@link EntityVariantResolver#resolveEnumDefault}) - class-bytes fallback -
 * overlay layer scan ({@link EntityBoneResolver}) - variant-stem decision - renderer
 * overrides ({@link EntityRendererOverrides}). Each step's output lands in one field on the
 * returned {@link Result}.
 *
 * <p>Stateless per-walk: one instance binds the context + registry entry + variant tables,
 * then {@link #walk()} runs the full per-entity sequence and returns the {@link Result}.
 */
@RequiredArgsConstructor
public final class EntitySessionWalk {

    /**
     * Namespace prefix applied to every emitted entity id. Mirrors
     * {@code ToolingEntityModels.MINECRAFT_NAMESPACE}; kept in sync because both files
     * synthesize the same {@code "minecraft:<id>"} for downstream JSON.
     */
    private static final @NotNull String MINECRAFT_NAMESPACE = "minecraft:";

    private final @NotNull EntityToolingContext context;
    private final @NotNull EntityRegistryDiscovery.Result.Entry registryEntry;
    private final @NotNull ConcurrentMap<String, ConcurrentList<EntityVariantResolver.Result>> variantTables;

    /**
     * Per-entity output bundling every binding-stage resolver's result for one entity.
     *
     * @param entityId the namespaced id, e.g. {@code "minecraft:zombie"}
     * @param entityFieldName the {@code EntityType} static field name, e.g. {@code "ZOMBIE"}
     * @param rendererInternalName the renderer class internal name
     * @param binding the texture-binding result (primary path / baby / variant source class)
     * @param layers the overlay-layer field-class names scanned from the renderer constructor
     * @param variantStem the data-driven variant-table directory name (e.g. {@code "cow"}),
     *     or {@code null} when the entity is not variant-driven
     * @param setupYawAddend yaw addend from the renderer's {@code setupRotations} override;
     *     {@code 0f} when the override is absent or returns the unmodified body rotation
     * @param rendererScale uniform scale extracted from the renderer's {@code scale} override
     *     {@code poseStack.scale(F,F,F)} chain, or {@code null} when no chain is present or
     *     the chain product equals {@code 1.0}
     */
    public record Result(
        @NotNull String entityId,
        @NotNull String entityFieldName,
        @NotNull String rendererInternalName,
        @NotNull EntityTextureResolver.Result binding,
        @NotNull ConcurrentList<String> layers,
        @Nullable String variantStem,
        float setupYawAddend,
        @Nullable Float rendererScale
    ) {}

    /**
     * Runs the per-entity resolver fan-out and returns the folded {@link Result}.
     */
    public @NotNull Result walk() {
        ZipFile zip = this.context.zip();
        Diagnostics diagnostics = this.context.diagnostics();
        String renderer = this.registryEntry.rendererInternalName();
        String entityId = MINECRAFT_NAMESPACE + this.registryEntry.entityId();

        EntityTextureResolver.Result binding = EntityTextureResolver.resolve(
            this.context.classNodes(), renderer, this.registryEntry.entityId(), this.registryEntry.lambdaTypeArgs(), diagnostics
        );
        // Enum-default variant detection: AxolotlRenderer / RabbitRenderer read their
        // texture via state.variant lookup into a Map<XVariant, Identifier>. The resolver
        // walks the variant enum class for its DEFAULT static field and computes the
        // canonical texture stem (axolotl/axolotl_lucy, rabbit/rabbit_brown).
        EntityVariantResolver.EnumDefault variantDefault =
            EntityVariantResolver.resolveEnumDefault(this.context.classNodes(), renderer, diagnostics);
        if (variantDefault != null) {
            String stem = this.registryEntry.entityId();
            String candidate = "textures/entity/" + stem + "/" + stem + "_" + variantDefault.defaultName() + ".png";
            // Existence-gating keeps the override narrow to entities whose convention
            // matches (axolotl, llama, parrot, rabbit); the others fall through to the
            // binding-extracted path or the hardcoded fallback.
            if (zip.getEntry("assets/minecraft/" + candidate) != null) {
                binding = new EntityTextureResolver.Result(
                    candidate,
                    null,
                    binding.variantSourceClass(),
                    "(enum-default '" + variantDefault.defaultName() + "')"
                );
            }
        }
        if (binding.primaryTexturePath() == null) {
            // Class-bytes fallback for renderers the main resolver leaves unresolved.
            String fallback = EntityTextureResolver.findBaseTextureFallback(this.context.classNodes(), renderer);
            if (fallback != null) {
                binding = new EntityTextureResolver.Result(
                    "textures/entity/" + fallback + ".png",
                    null,
                    binding.variantSourceClass(),
                    "(class-bytes fallback)"
                );
            }
        }

        ConcurrentList<String> layers = EntityBoneResolver.scanOverlayLayers(this.context.classNodes(), renderer);

        String variantStem;
        if (binding.variantSourceClass() != null) {
            variantStem = EntityVariantResolver.directoryFor(binding.variantSourceClass());
        } else if (binding.isUnresolved() && this.variantTables.containsKey(this.registryEntry.entityId())) {
            // Fallback for state-field-read patterns (WolfRenderer, CatRenderer, etc.):
            // getTextureLocation returns state.texture, which is populated upstream from a
            // variant lookup we can't trace from getTextureLocation alone. The variant
            // directory's existence is a reliable signal the entity is variant-driven.
            variantStem = this.registryEntry.entityId();
            binding = new EntityTextureResolver.Result(null, null, "(state-field-driven)", binding.hierarchySource());
        } else {
            variantStem = null;
        }

        EntityRendererOverrides.Result overrides = EntityRendererOverrides.resolve(this.context.classNodes(), renderer, diagnostics);

        return new Result(
            entityId,
            this.registryEntry.entityFieldName(),
            renderer,
            binding,
            layers,
            variantStem,
            overrides.setupYawAddend(),
            overrides.rendererScale()
        );
    }

}
