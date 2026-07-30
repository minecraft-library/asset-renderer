package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Node {@code axes} - the five-axis dispatcher: appends the {@code variant}, {@code state},
 * {@code age}, {@code size} and {@code shape} sub-nodes in that fixed order, omitting the ones
 * that decline. The node itself is always emitted, since {@code age} is mandatory - it carries
 * the family baseline in {@code options.adult} - so at most four of the five can be absent.
 *
 * <p>The variant axis resolves FIRST and separately ({@link #resolveVariant()}) because the
 * family's {@code texture} member depends on it: variant-axis families carry per-option
 * textures and no top-level texture.
 */
final class EntityAxesResolver {

    private final @NotNull EntityVariantAxisResolver variant;
    private final @NotNull EntityStateAxisResolver state;
    private final @NotNull EntityAgeAxisResolver age;
    private final @NotNull EntitySizeAxisResolver size;
    private final @NotNull EntityShapeAxisResolver shape;

    /** The variant node held between the two resolve steps. */
    private @Nullable JsonTree variantNode;

    EntityAxesResolver(
        @NotNull ToolingSession session,
        @NotNull EntitySubject subject,
        @NotNull LayerDefinitionIndex layerDefinitions,
        @NotNull VariantIndex variants,
        @NotNull EntityGeometryRefResolver geometryRef,
        @NotNull GeometryManifest manifest,
        @NotNull BlockRegistryIndex blocks,
        @NotNull Diagnostics diagnostics
    ) {
        this.variant = new EntityVariantAxisResolver(session.cache(), subject, variants, layerDefinitions,
            geometryRef, manifest, blocks, diagnostics.child("variant"));
        this.state = new EntityStateAxisResolver(subject, variants, diagnostics.child("state"));
        this.age = new EntityAgeAxisResolver(session.cache(), subject, layerDefinitions, geometryRef,
            manifest, diagnostics.child("age"));
        this.size = new EntitySizeAxisResolver(subject, layerDefinitions, geometryRef,
            manifest, diagnostics.child("size"));
        this.shape = new EntityShapeAxisResolver(session.cache(), subject, layerDefinitions, geometryRef,
            manifest, diagnostics.child("shape"));
    }

    /**
     * Resolves the variant axis ahead of the family's {@code texture} member (the
     * variant-driven texture-null rule), holding the node for {@link #resolve}.
     *
     * @return the variant node, or {@code null} when the family has no variant axis
     */
    @Nullable JsonTree resolveVariant() {
        this.variantNode = this.variant.resolve();
        return this.variantNode;
    }

    /**
     * The {@code axes} node in fixed sub-node order. Never {@code null}: the {@code age} axis is
     * mandatory ({@code options.adult} holds the family baseline), so every family carries at
     * least an age axis. {@link #resolveVariant()} must have run; the caller resolves the
     * overlays FIRST (the shape option clones the family's pattern overlays onto its mesh) while
     * the put chain keeps the on-disk order.
     *
     * @param baseGeometry the family's resolved primary geometry key (the adult mesh), or {@code null}
     * @param adultTexture the family's resolved adult texture, or {@code null}
     * @param overlays the family's resolved {@code overlays} rows, or {@code null}
     * @return the axes node
     */
    @NotNull JsonTree resolve(
        @Nullable String baseGeometry, @Nullable String adultTexture, @Nullable JsonTree overlays,
        float @Nullable [] setupYShift
    ) {
        return JsonTree.object()
            .putIf("variant", this.variantNode)
            .putIf("state", this.state.resolve())
            .put("age", this.age.resolve(baseGeometry, adultTexture, this.variantNode != null, setupYShift))
            .putIf("size", this.size.resolve())
            .putIf("shape", this.shape.resolve(adultTexture, overlays));
    }

}
