package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
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

/**
 * Resolves one entity in one pass - the {@link #resolve()} put-chain IS the on-disk key
 * order, declared once, here. Each per-node resolver owns exactly one JSON node and returns
 * null to omit its key (the empty-vs-absent rule).
 *
 * <p>The overlays node resolves BEFORE the axes node (the shape axis clones the model's
 * pattern overlays onto its large mesh), but the put chain keeps the on-disk order - axes
 * ahead of overlays. {@code group_of} is appended by the post-pass linker.
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

    EntityRendererResolver(@NotNull EntityContext context) {
        this.subject = context.subject();
        this.diagnostics = context.diagnostics();
        // ONE renderer-ctor-chain scan produces the ordered addLayer roster every
        // layer-consuming node reads (overlays, block_overlays, layers - the latter also
        // carries the worn-armor row); a row's layer_index is its position here.
        // Same-class duplicates are kept - deduping by class would force the warden's
        // five-pass re-walk.
        this.layerRoster = scanLayerRoster(context.session());
        this.geometryRef = new EntityGeometryRefResolver(context.scope("geometry"));
        this.texture = new EntityTextureResolver(context.scope("texture"));
        this.renderTraits = new EntityRenderTraitsResolver(context.scope("render"));
        this.bones = new EntityBoneResolver(context.scope("bones"), this.geometryRef);
        this.axes = new EntityAxesResolver(context.scope("axes"), this.geometryRef);
        this.overlays = new EntityOverlayResolver(context.scope("overlays"), this.layerRoster, this.geometryRef);
        this.blockOverlays = new EntityBlockOverlayResolver(context.scope("blockOverlays"), this.layerRoster);
        this.layers = new EntityLayersResolver(context.scope("layers"), this.layerRoster);
    }

    /**
     * Builds the model node - invocation order IS on-disk member order.
     * The variant axis resolves ahead of the adult-texture resolution (variant-axis models
     * carry per-option textures, so no adult texture is resolved for them); the base geometry
     * and adult texture feed the mandatory age axis' {@code options.adult}, and the overlays
     * resolve ahead of the axes (the shape-axis clone) - the put order is unaffected.
     */
    @NotNull JsonTree resolve() {
        // The primary geometry is registered FIRST (manifest order) but is not emitted at
        // top level: it moves the model baseline (base geometry + adult texture) into
        // the mandatory age axis' options.adult (EntityAgeAxisResolver).
        String baseGeometry = this.geometryRef.resolve();                               // -> manifest key
        // Worn armor is not a top-level member: EntityLayersResolver emits it as a `layers`
        // row derived off the same addLayer roster, carrying its own geometry reference.
        JsonTree node = JsonTree.object()
            .put("renderer", this.subject.rendererClass());                             // provenance scalar (resolver-owned)
        String texturePath = this.axes.resolveVariant() == null ? this.texture.resolve() : null;
        JsonTree overlays = this.overlays.resolve();
        return node
            .putIf("render", this.renderTraits.resolve())                               // {scale?, yaw_addend?, tint?}
            .putIf("bones", this.bones.resolve())                                       // {hidden?, toggles?}
            // The setupRotations Y shift is per-age, so it rides the age options rather than `render`.
            .put("axes", this.axes.resolve(baseGeometry, texturePath, overlays,
                this.renderTraits.resolveSetupYShift()))                                // age mandatory -> always present
            .putIf("overlays", overlays)
            .putIf("block_overlays", this.blockOverlays.resolve())
            .putIf("layers", this.layers.resolve());
    }   // group_of appended by the EntityGroupLinker post-pass

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
        ClassKit.walkSuperChain(session.cache(), this.subject.rendererClass(), cn -> {
            List<LayerSite> level = new ArrayList<>();
            for (MethodNode ctor : cn.methods) {
                if (!ClassKit.INIT.equals(ctor.name)) continue;
                // Owner-agnostic addLayer match - the renderer's super may be any of
                // several LivingEntityRenderer subclasses; gate on the canonical
                // descriptor shape (single Layer arg, boolean return).
                level.addAll(AsmWalker.over(ctor)
                    .ofType(MethodInsnNode.class)
                    .where(call -> call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && VanillaSourceClasses.Methods.ADD_LAYER.equals(call.name)
                        && call.desc.startsWith("(L") && call.desc.endsWith(";)Z"))
                    .mapNotNull(call -> resolveSite(ctor, call))
                    .toList());
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
        AbstractInsnNode previous = AsmWalker.previousReal(addLayer);
        if (previous instanceof MethodInsnNode ctorCall
            && previous.getOpcode() == Opcodes.INVOKESPECIAL
            && ClassKit.INIT.equals(ctorCall.name)) {
            AbstractInsnNode alloc = findPrecedingLayerNew(addLayer);
            return alloc == null ? null : new LayerSite(ctorCall.owner, 0, ctor, alloc, addLayer);
        }
        if (previous instanceof MethodInsnNode factory
            && (previous.getOpcode() == Opcodes.INVOKESTATIC || previous.getOpcode() == Opcodes.INVOKEVIRTUAL)) {
            Type returned = ClassKit.returnType(factory.desc);
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
        int[] pendingInits = {0};
        return AsmWalker.before(addLayerInsn).limit(64).firstNotNull(cursor -> {
            if (cursor.getOpcode() == Opcodes.INVOKESPECIAL
                && cursor instanceof MethodInsnNode mi
                && ClassKit.INIT.equals(mi.name))
                pendingInits[0]++;
            if (cursor.getOpcode() == Opcodes.NEW && cursor instanceof TypeInsnNode) {
                pendingInits[0]--;
                if (pendingInits[0] == 0) return cursor;
            }
            return null;
        });
    }

}
