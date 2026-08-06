package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.geometry.YAxis;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The block-models {@code geometry} authority: enumerates a subject's family splits and
 * registers each split's primary mesh with the manifest, embedding the {@code GeometryIds} key -
 * no parse here (parse happens in {@code GeometryFlow}).
 *
 * <p>Generic detection first: the renderer's {@code GETSTATIC ModelLayers.*} references (super
 * chain included) are looked up in the shared {@link LayerDefinitionIndex} and filtered to
 * primary factory methods. Each primary layer becomes a split - its id from
 * {@link BlockFamilyPolicies} ({@code bed_head} / {@code bell_body} / the 4-way skull), or the
 * subject id for single-mesh families. The banner's four fields carry their standing/wall +
 * flag/pole {@code withStick} through the same policy. When NO primary layer resolves (signs
 * bake their meshes through a {@code WoodType.values()} stream the {@code createRoots} walk can't
 * follow), the fallback scans the renderer's own static {@code LayerDefinition}-returning primary
 * methods and applies the sign attachment / {@code withStick} splits.
 *
 * <p>The {@code y_axis} is a pivot-band heuristic: a factory whose largest
 * {@code PartPose.offset} pivot Y falls in the {@code [8, 16)} half-block band is block-space
 * authored ({@code UP}); everything else stays {@code DOWN}.
 */
final class BlockGeometrySourceResolver {

    private final @NotNull ClassNodeCache cache;
    private final @NotNull BlockEntitySubject subject;
    private final @NotNull LayerDefinitionIndex layerDefinitions;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull Diagnostics diagnostics;

    BlockGeometrySourceResolver(
        @NotNull ToolingSession session,
        @NotNull BlockEntitySubject subject,
        @NotNull LayerDefinitionIndex layerDefinitions,
        @NotNull GeometryManifest manifest
    ) {
        this.cache = session.cache();
        this.subject = subject;
        this.layerDefinitions = layerDefinitions;
        this.manifest = manifest;
        this.diagnostics = session.diagnostics().child(subject.beTypeId());
    }

    /**
     * One resolved family split: the models key plus its registered geometry key, Y-axis
     * convention, and mesh factory class (the {@code tinted} flag reads the {@code *FlagModel}
     * name off it).
     *
     * @param splitId the {@code models} key
     * @param geometryKey the minted geometry manifest key (the {@code geometry} node)
     * @param yAxis the split's Y-axis convention
     * @param factoryClass the mesh factory class internal name
     */
    record Split(@NotNull String splitId, @NotNull String geometryKey, @NotNull YAxis yAxis, @NotNull String factoryClass) {}

    /**
     * Enumerates the subject's splits, registering each split's primary mesh with the manifest.
     *
     * @return the ordered splits (empty when nothing resolves, a WARN recorded)
     */
    @NotNull List<Split> resolveSplits() {
        List<Split> splits = new ArrayList<>();
        Set<String> seenSplitIds = new LinkedHashSet<>();
        boolean anyPrimary = false;

        for (String field : collectLayerRefs(this.subject.rendererClass())) {
            LayerDefinitionIndex.Entry entry = this.layerDefinitions.get(field);
            if (entry == null || !BlockGeometryPolicies.isPrimary(entry.factoryMethod())) continue;
            anyPrimary = true;
            emitGenericSplit(field, entry, splits, seenSplitIds);
        }
        if (!anyPrimary) emitFallbackSplits(splits, seenSplitIds);

        if (splits.isEmpty())
            this.diagnostics.warn("no primary geometry resolved for renderer '%s' - no models entry", this.subject.rendererClass());
        return splits;
    }

    /**
     * Emits the split for one resolved primary layer. A banner field carries its own
     * {@code withStick} + split id; otherwise the split id is the method-suffix mapping or the
     * subject id for single-mesh families.
     */
    private void emitGenericSplit(
        @NotNull String field,
        @NotNull LayerDefinitionIndex.Entry entry,
        @NotNull List<Split> splits,
        @NotNull Set<String> seenSplitIds
    ) {
        YAxis yAxis = inferYAxis(entry.factoryClass(), entry.factoryMethod());
        BlockFamilyPolicies.BannerVariant banner = BlockFamilyPolicies.bannerVariant(field);
        if (banner != null) {
            register(splits, seenSplitIds, banner.splitId(), yAxis, GeometryRequest.blockGeometry(
                entry.factoryClass(), entry.factoryMethod(), this.subject.beTypeId(), yAxis,
                entry.texWidthOverride(), entry.texHeightOverride(), new int[]{banner.withStick()}, null));
            return;
        }
        String splitId = BlockFamilyPolicies.methodSplitId(this.subject.localId(), entry.factoryMethod());
        register(splits, seenSplitIds, splitId != null ? splitId : this.subject.beTypeId(), yAxis,
            GeometryRequest.blockGeometry(entry.factoryClass(), entry.factoryMethod(), this.subject.beTypeId(),
                yAxis, entry.texWidthOverride(), entry.texHeightOverride(), null, null));
    }

    /**
     * The fallback: scans the renderer's own static {@code LayerDefinition}-returning primary
     * methods (the sign renderers reach their mesh through a {@code WoodType.values()} stream the
     * {@code createRoots} walk can't follow). Sign methods fan into the attachment /
     * {@code withStick} variants; any other static primary emits a single method-named split.
     */
    private void emitFallbackSplits(@NotNull List<Split> splits, @NotNull Set<String> seenSplitIds) {
        ClassNode renderer = this.cache.load(this.subject.rendererClass());
        if (renderer == null) return;
        for (MethodNode method : renderer.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0) continue;
            if (!AsmKit.descriptorReturns(method.desc, VanillaSourceClasses.Types.LAYER_DEFINITION)) continue;
            if (!BlockGeometryPolicies.isPrimary(method.name)) continue;

            YAxis yAxis = inferYAxis(this.subject.rendererClass(), method.name);
            List<BlockFamilyPolicies.SignVariant> variants = BlockFamilyPolicies.signVariants(method.name);
            if (variants != null) {
                for (BlockFamilyPolicies.SignVariant variant : variants) {
                    GeometryRequest.RefParam ref = variant.attachment() == null ? null
                        : new GeometryRequest.RefParam(0, VanillaSourceClasses.Types.HANGING_SIGN_ATTACHMENT, variant.attachment());
                    int[] paramInt = variant.withStick() == null ? null : new int[]{variant.withStick()};
                    register(splits, seenSplitIds, variant.splitId(), yAxis, GeometryRequest.blockGeometry(
                        this.subject.rendererClass(), method.name, this.subject.beTypeId(), yAxis, null, null, paramInt, ref));
                }
                continue;
            }
            String splitId = BlockFamilyPolicies.methodSplitId(this.subject.localId(), method.name);
            register(splits, seenSplitIds, splitId != null ? splitId : this.subject.beTypeId(), yAxis,
                GeometryRequest.blockGeometry(this.subject.rendererClass(), method.name, this.subject.beTypeId(),
                    yAxis, null, null, null, null));
        }
    }

    /** Registers a split's request with the manifest, deduping by split id (first wins). */
    private void register(
        @NotNull List<Split> splits,
        @NotNull Set<String> seenSplitIds,
        @NotNull String splitId,
        @NotNull YAxis yAxis,
        @NotNull GeometryRequest request
    ) {
        if (!seenSplitIds.add(splitId)) return;
        splits.add(new Split(splitId, this.manifest.register(request), yAxis, request.factoryClass()));
    }

    /**
     * The unique, insertion-ordered {@code ModelLayers} field names the renderer (and its super
     * chain) references via {@code GETSTATIC}.
     */
    private @NotNull Set<String> collectLayerRefs(@NotNull String rendererClass) {
        Set<String> out = new LinkedHashSet<>();
        AsmKit.walkSuperChain(this.cache, rendererClass, cn -> {
            for (MethodNode method : cn.methods)
                AsmWalker.over(method)
                    .where(in -> AsmWalker.isGetStatic(in, VanillaSourceClasses.Types.MODEL_LAYERS))
                    .names()
                    .forEach(out::add);
        });
        return out;
    }

    /**
     * The pivot-band Y-axis heuristic: {@code UP} when the factory method's largest
     * {@code PartPose.offset(x, y, z)} pivot Y falls in the declared half-block band
     * ({@link BlockTransformPolicies#Y_AXIS_BAND} - band constants and counter-examples live
     * there); else {@code DOWN}.
     */
    private @NotNull YAxis inferYAxis(@NotNull String factoryClass, @NotNull String factoryMethod) {
        ClassNode cn = this.cache.load(factoryClass);
        MethodNode method = cn == null ? null : AsmKit.findMethod(cn, factoryMethod);
        if (method == null) return YAxis.DOWN;

        // offset(x, y, z) -> y is second-to-last in the float window at the call
        float maxPivotY = AsmWalker.over(method)
            .gather(AsmWalker::floatLiteral)
            .keep(12)
            .retain()
            .resetAt(Insn.ofType(MethodInsnNode.class))
            .commitAt(MethodInsnNode.class, mi -> mi.owner.equals(VanillaSourceClasses.Types.PART_POSE)
                && mi.name.equals(VanillaSourceClasses.Methods.OFFSET)
                && mi.desc.startsWith("(FFF"))
            .mapNotNull(c -> c.values().size() >= 3 ? c.values().get(c.values().size() - 2) : null)
            .reduce(Float.NEGATIVE_INFINITY, (m, y) -> y > m ? y : m);
        return maxPivotY >= BlockTransformPolicies.yAxisBandMin() && maxPivotY < BlockTransformPolicies.yAxisBandMax()
            ? YAxis.UP : YAxis.DOWN;
    }

}
