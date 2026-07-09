package lib.minecraft.renderer.tooling2.blockentity;

import lib.minecraft.renderer.tooling2.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling2.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling2.geometry.YAxis;
import lib.minecraft.renderer.tooling2.kernel.AsmKit;
import lib.minecraft.renderer.tooling2.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling2.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The block-models {@code geometry} authority (SPINE 3.3 row 2 + the {@code y_axis} helper,
 * row 3): enumerates a subject's family splits and registers each split's primary mesh with the
 * manifest, embedding the {@code GeometryIds} key - no parse here (parse happens in
 * {@code GeometryFlow}).
 *
 * <p>Generic detection first (decision 7): the renderer's {@code GETSTATIC ModelLayers.*}
 * references (super chain included) are looked up in the shared {@link LayerDefinitionIndex} and
 * filtered to primary factory methods. Each primary layer becomes a split - its id from
 * {@link BlockFamilyPolicies} ({@code bed_head} / {@code bell_body} / the 4-way skull), or the
 * subject id for single-mesh families. The banner's four fields carry their standing/wall +
 * flag/pole {@code withStick} through the same policy. When NO primary layer resolves (signs
 * bake their meshes through a {@code WoodType.values()} stream the {@code createRoots} walk can't
 * follow), the fallback scans the renderer's own static {@code LayerDefinition}-returning primary
 * methods and applies the sign attachment / {@code withStick} splits [P39].
 *
 * <p>The {@code y_axis} is the pivot-band heuristic [P40]: a factory whose largest
 * {@code PartPose.offset} pivot Y falls in the {@code [8, 16)} half-block band is block-space
 * authored ({@code UP}); everything else stays {@code DOWN}.
 */
final class BlockGeometrySourceResolver {

    /**
     * The primary layer-factory method names - "which layer is the whole-block geometry vs a
     * decorative sub-layer" (conduit eye/wind/cage, copper-golem poses). Not a vanilla type
     * literal, so it lives here rather than the policy enum; the roster is the legacy
     * {@code PRIMARY_METHOD_NAMES} (SourceDiscovery.java:162-178).
     */
    private static final @NotNull Set<String> PRIMARY_METHOD_NAMES = Set.of(
        "createSingleBodyLayer", "createBodyLayer", "createBoxLayer", "createShellLayer",
        "createShellMesh", "createBaseLayer", "createSidesLayer", "createHeadLayer",
        "createFootLayer", "createHeadModel", "createFlagLayer", "createSignLayer",
        "createHangingSignLayer", "createMobHeadLayer", "createHumanoidHeadLayer");

    private final @NotNull ClassNodeCache cache;
    private final @NotNull BlockEntitySubject subject;
    private final @NotNull LayerDefinitionIndex layerDefinitions;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull Diagnostics diagnostics;

    BlockGeometrySourceResolver(
        @NotNull lib.minecraft.renderer.tooling2.kernel.ToolingSession session,
        @NotNull BlockEntitySubject subject,
        @NotNull LayerDefinitionIndex layerDefinitions,
        @NotNull GeometryManifest manifest,
        @NotNull Diagnostics diagnostics
    ) {
        this.cache = session.cache();
        this.subject = subject;
        this.layerDefinitions = layerDefinitions;
        this.manifest = manifest;
        this.diagnostics = diagnostics;
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
            if (entry == null || !PRIMARY_METHOD_NAMES.contains(entry.factoryMethod())) continue;
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
     * {@code withStick} + split id ([P34] BANNER_FIELDS); otherwise the split id is the
     * method-suffix mapping ([P34/P31]) or the subject id for single-mesh families.
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
     * {@code createRoots} walk can't follow). Sign methods fan into the [P39] attachment /
     * {@code withStick} variants; any other static primary emits a single method-named split.
     */
    private void emitFallbackSplits(@NotNull List<Split> splits, @NotNull Set<String> seenSplitIds) {
        ClassNode renderer = this.cache.load(this.subject.rendererClass());
        if (renderer == null) return;
        for (MethodNode method : renderer.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0) continue;
            if (!AsmKit.descriptorReturns(method.desc, VanillaSourceClasses.Types.LAYER_DEFINITION)) continue;
            if (!PRIMARY_METHOD_NAMES.contains(method.name)) continue;

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
                for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext())
                    if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.MODEL_LAYERS))
                        out.add(((FieldInsnNode) in).name);
        });
        return out;
    }

    /**
     * The pivot-band Y-axis heuristic [P40]: {@code UP} when the factory method's largest
     * {@code PartPose.offset(x, y, z)} pivot Y falls in the {@code [8, 16)} half-block band
     * (block-space authored - ChestModel {@code (0,9,1)}, BellModel {@code (8,12,8)}); else
     * {@code DOWN} (ShulkerModel's {@code y=24} mob root, banner-flag / dragon-head pure-entity
     * meshes, {@code offsetAndRotation} users like the decorated pot). Legacy
     * SourceDiscovery.inferYAxisFromMethod:877-911 (the code applies the band, not the javadoc's
     * bare {@code >= 8}).
     */
    private @NotNull YAxis inferYAxis(@NotNull String factoryClass, @NotNull String factoryMethod) {
        ClassNode cn = this.cache.load(factoryClass);
        MethodNode method = cn == null ? null : AsmKit.findMethod(cn, factoryMethod);
        if (method == null) return YAxis.DOWN;

        List<Float> floats = new ArrayList<>();
        float maxPivotY = Float.NEGATIVE_INFINITY;
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            Float literal = AsmKit.readFloatLiteral(in);
            if (literal != null) {
                floats.add(literal);
                if (floats.size() > 12) floats.removeFirst();
                continue;
            }
            if (!(in instanceof MethodInsnNode mi)) continue;
            if (mi.owner.equals(VanillaSourceClasses.Types.PART_POSE)
                && mi.name.equals(VanillaSourceClasses.Methods.OFFSET)
                && mi.desc.startsWith("(FFF")
                && floats.size() >= 3) {
                float pivotY = floats.get(floats.size() - 2);   // offset(x, y, z) -> y is second-to-last
                if (pivotY > maxPivotY) maxPivotY = pivotY;
            }
            floats.clear();
        }
        return maxPivotY >= 8f && maxPivotY < 16f ? YAxis.UP : YAxis.DOWN;
    }

}
