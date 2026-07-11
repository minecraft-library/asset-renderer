package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.JsonNode;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolves one entity in one pass - the {@link #resolve()} put-chain IS the on-disk key
 * order, declared once, here. Each per-node resolver owns exactly one JSON node and returns
 * null to omit its key (the empty-vs-absent rule).
 *
 * <p>The overlays node resolves BEFORE the axes node (the shape axis clones the family's
 * pattern overlays onto its large mesh), but the put chain keeps the on-disk order - axes
 * ahead of overlays. {@code family_of} is appended by the post-pass linker.
 */
final class EntityRendererResolver {

    private final @NotNull EntitySubject subject;
    private final @NotNull Diagnostics diagnostics;
    private final @NotNull EntityGeometryRefResolver geometryRef;
    private final @NotNull EntityTextureResolver texture;
    private final @NotNull EntityRenderTraitsResolver renderTraits;
    private final @NotNull EntityBoneResolver bones;
    private final @NotNull EntityAxesResolver axes;
    private final @NotNull EntityOverlayResolver overlays;
    private final @NotNull EntityBlockOverlayResolver blockOverlays;
    private final @NotNull EntityLayersResolver layers;
    private final @NotNull List<LayerSite> layerRoster;

    EntityRendererResolver(
        @NotNull ToolingSession session,
        @NotNull EntitySubject subject,
        @NotNull LayerDefinitionIndex layerDefinitions,
        @NotNull VariantIndex variants,
        @NotNull Set<String> nonBaseSuffixes,
        @NotNull BlockRegistryIndex blocks,
        @NotNull EntityPipelineTraits pipelineTraits,
        @NotNull GeometryManifest manifest
    ) {
        this.subject = subject;
        this.diagnostics = session.diagnostics().child(subject.entityId());
        // ONE renderer-ctor-chain scan produces the ordered addLayer roster every
        // layer-consuming node reads (overlays, block_overlays, layers - the latter also
        // carries the armor classification); a row's layer_index is its position here.
        // Same-class duplicates are kept - deduping by class would force the warden's
        // five-pass re-walk.
        this.layerRoster = scanLayerRoster(session);
        this.geometryRef = new EntityGeometryRefResolver(session.cache(), subject, layerDefinitions, manifest,
            this.diagnostics.child("geometry"));
        this.texture = new EntityTextureResolver(session.cache(), subject, variants, nonBaseSuffixes,
            this.diagnostics.child("texture"));
        this.renderTraits = new EntityRenderTraitsResolver(session.cache(), subject, this.diagnostics.child("render"));
        this.bones = new EntityBoneResolver(session.cache(), subject, this.geometryRef, this.diagnostics.child("bones"));
        this.axes = new EntityAxesResolver(session, subject, layerDefinitions, variants, this.geometryRef,
            manifest, this.diagnostics.child("axes"));
        this.overlays = new EntityOverlayResolver(session.cache(), subject, this.layerRoster, layerDefinitions,
            this.geometryRef, pipelineTraits, manifest, this.diagnostics.child("overlays"));
        this.blockOverlays = new EntityBlockOverlayResolver(session.cache(), subject, this.layerRoster, blocks,
            this.diagnostics.child("blockOverlays"));
        this.layers = new EntityLayersResolver(session, subject, this.layerRoster, layerDefinitions,
            manifest, this.diagnostics.child("layers"));
    }

    /**
     * Builds the family node - invocation order IS on-disk member order.
     * The variant axis resolves ahead of the adult-texture resolution (variant-axis families
     * carry per-option textures, so no adult texture is resolved for them); the base geometry
     * and adult texture feed the mandatory age axis' {@code options.adult}, and the overlays
     * resolve ahead of the axes (the shape-axis clone) - the put order is unaffected.
     */
    @NotNull JsonNode resolve() {
        // The primary geometry is registered FIRST (manifest order) but is not emitted at
        // top level: it moves the family baseline (base geometry + adult texture) into
        // the mandatory age axis' options.adult (EntityAgeAxisResolver).
        String baseGeometry = this.geometryRef.resolve();                               // -> manifest key
        // armor_type is not a top-level member: EntityLayersResolver emits the humanoid
        // classification as a `layers` row derived off the same addLayer roster.
        JsonNode node = JsonNode.object()
            .put("renderer", this.subject.rendererClass());                             // provenance scalar (resolver-owned)
        String texturePath = this.axes.resolveVariant() == null ? this.texture.resolve() : null;
        JsonNode overlays = this.overlays.resolve();
        return node
            .putIf("render", this.renderTraits.resolve())                               // {scale?, yaw_addend?, tint?}
            .putIf("bones", this.bones.resolve())                                       // {hidden?, toggles?}
            .put("axes", this.axes.resolve(baseGeometry, texturePath, overlays))        // age mandatory -> always present
            .putIf("overlays", overlays)
            .putIf("block_overlays", this.blockOverlays.resolve())
            .putIf("layers", this.layers.resolve());
    }   // family_of appended by the EntityFamilyLinker post-pass

    /**
     * One {@code addLayer(...)} call site in the renderer constructor chain.
     *
     * @param layerClass the added layer class's JVM internal name (the allocated class, or
     *     a factory helper's return type)
     * @param layerIndex the row's position in the roster
     * @param method the constructor holding the site (the arg-region walks re-enter it)
     * @param allocation the {@code NEW} allocating the layer, or the factory {@code INVOKE}
     *     producing it - the arg region is {@code [allocation .. addLayer]}
     * @param addLayer the {@code addLayer} invocation
     */
    record LayerSite(
        @NotNull String layerClass,
        int layerIndex,
        @NotNull MethodNode method,
        @NotNull AbstractInsnNode allocation,
        @NotNull AbstractInsnNode addLayer
    ) {}

    /**
     * Walks the renderer's constructor chain for {@code addLayer(...)} call sites, keeping
     * same-class duplicates. Per-class site lists are collected leaf-to-super and flattened
     * super-first: vanilla runs the super constructor (and its addLayer calls) before the
     * subclass body, so the roster index reflects the runtime addLayer order.
     */
    private @NotNull List<LayerSite> scanLayerRoster(@NotNull ToolingSession session) {
        List<List<LayerSite>> perClass = new ArrayList<>();
        AsmKit.walkSuperChain(session.cache(), this.subject.rendererClass(), cn -> {
            List<LayerSite> level = new ArrayList<>();
            for (MethodNode ctor : cn.methods) {
                if (!AsmKit.INIT.equals(ctor.name)) continue;
                for (AbstractInsnNode in = ctor.instructions.getFirst(); in != null; in = in.getNext()) {
                    // Owner-agnostic addLayer match - the renderer's super may be any of
                    // several LivingEntityRenderer subclasses; gate on the canonical
                    // descriptor shape (single Layer arg, boolean return).
                    if (in.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                    if (!(in instanceof MethodInsnNode mi)) continue;
                    if (!VanillaSourceClasses.Methods.ADD_LAYER.equals(mi.name)) continue;
                    if (!mi.desc.startsWith("(L") || !mi.desc.endsWith(";)Z")) continue;
                    LayerSite site = resolveSite(ctor, in);
                    if (site != null) level.add(site);
                }
            }
            perClass.add(level);
        });
        List<LayerSite> out = new ArrayList<>();
        for (int levelIndex = perClass.size() - 1; levelIndex >= 0; levelIndex--)
            for (LayerSite site : perClass.get(levelIndex))
                out.add(new LayerSite(site.layerClass(), out.size(), site.method(), site.allocation(), site.addLayer()));
        return out;
    }

    /**
     * Resolves one {@code addLayer} call to its site. The argument-producing instruction
     * ends immediately before the call: an {@code INVOKESPECIAL <init>} names the allocated
     * layer directly (its balanced {@code NEW} anchors the arg region); a factory
     * {@code INVOKESTATIC} / {@code INVOKEVIRTUAL} returning the layer (camel's
     * {@code createCamelSaddleLayer}) names it by return type, the invoke itself anchoring
     * the region.
     */
    private static @Nullable LayerSite resolveSite(@NotNull MethodNode ctor, @NotNull AbstractInsnNode addLayer) {
        AbstractInsnNode previous = AsmKit.previousReal(addLayer);
        if (previous instanceof MethodInsnNode ctorCall
            && previous.getOpcode() == Opcodes.INVOKESPECIAL
            && AsmKit.INIT.equals(ctorCall.name)) {
            AbstractInsnNode alloc = findPrecedingLayerNew(addLayer);
            return alloc == null ? null : new LayerSite(ctorCall.owner, 0, ctor, alloc, addLayer);
        }
        if (previous instanceof MethodInsnNode factory
            && (previous.getOpcode() == Opcodes.INVOKESTATIC || previous.getOpcode() == Opcodes.INVOKEVIRTUAL)) {
            Type returned = AsmKit.returnType(factory.desc);
            if (returned.getSort() == Type.OBJECT)
                return new LayerSite(returned.getInternalName(), 0, ctor, previous, addLayer);
        }
        AbstractInsnNode alloc = findPrecedingLayerNew(addLayer);
        return alloc == null ? null : new LayerSite(((TypeInsnNode) alloc).desc, 0, ctor, alloc, addLayer);
    }

    /**
     * Walks backwards from an {@code addLayer} call for the {@code NEW XLayer} allocating
     * the added layer. A {@code pendingInits} balance keeps nested constructions in the
     * layer's own arguments ({@code new XLayer(new Model(...))}) from returning the wrong
     * {@code NEW}: each {@code INVOKESPECIAL <init>} increments, each {@code NEW} decrements,
     * and the {@code NEW} driving the balance to zero is the outermost allocation. Bounded at
     * 64 instructions so a long tangle of nested ctor args can't run into an earlier
     * {@code addLayer} construction.
     */
    private static @Nullable AbstractInsnNode findPrecedingLayerNew(@NotNull AbstractInsnNode addLayerInsn) {
        AbstractInsnNode cursor = addLayerInsn.getPrevious();
        int depth = 0;
        int pendingInits = 0;
        while (cursor != null && depth < 64) {
            depth++;
            if (cursor.getOpcode() == Opcodes.INVOKESPECIAL
                && cursor instanceof MethodInsnNode mi
                && AsmKit.INIT.equals(mi.name))
                pendingInits++;
            if (cursor.getOpcode() == Opcodes.NEW && cursor instanceof TypeInsnNode) {
                pendingInits--;
                if (pendingInits == 0) return cursor;
            }
            cursor = cursor.getPrevious();
        }
        return null;
    }

}
