package lib.minecraft.renderer.tooling2.entity;

import lib.minecraft.renderer.tooling2.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling2.kernel.AsmKit;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import lib.minecraft.renderer.tooling2.kernel.ToolingSession;
import lib.minecraft.renderer.tooling2.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling2.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ONE entity, ONE pass (SPINE 3.1 stage 3) - the {@link #resolve()} put-chain IS the
 * on-disk key order, declared once, here. Each per-node resolver owns exactly one JSON node
 * and returns null to omit its key (the empty-vs-absent rule).
 *
 * <p>The axis / overlay / block-overlay / layer nodes (roster rows 7-16) append to this
 * chain in their own sessions; {@code family_of} is the post-pass linker's.
 */
final class EntityRendererResolver {

    private final @NotNull EntitySubject subject;
    private final @NotNull Diagnostics diagnostics;
    private final @NotNull EntityGeometryRefResolver geometryRef;
    private final @NotNull EntityTextureResolver texture;
    private final @NotNull EntityRenderTraitsResolver renderTraits;
    private final @NotNull EntityBoneResolver bones;
    private final @NotNull List<LayerSite> layerRoster;

    EntityRendererResolver(
        @NotNull ToolingSession session,
        @NotNull EntitySubject subject,
        @NotNull LayerDefinitionIndex layerDefinitions,
        @NotNull VariantIndex variants,
        @NotNull Set<String> nonBaseSuffixes,
        @NotNull GeometryManifest manifest
    ) {
        this.subject = subject;
        this.diagnostics = session.diagnostics().child(subject.entityId());
        // ONE renderer-ctor scan produces the ordered addLayer roster every layer-consuming
        // node reads (armor_type now; overlays / block_overlays / layers in their sessions);
        // a row's layer_index is its position here. No same-class dedupe - the legacy dedupe
        // forced the warden five-pass re-walk.
        this.layerRoster = scanLayerRoster(session);
        this.geometryRef = new EntityGeometryRefResolver(session.cache(), subject, layerDefinitions, manifest,
            this.diagnostics.child("geometry"));
        this.texture = new EntityTextureResolver(session.cache(), subject, variants, nonBaseSuffixes,
            this.diagnostics.child("texture"));
        this.renderTraits = new EntityRenderTraitsResolver(session.cache(), subject, this.diagnostics.child("render"));
        this.bones = new EntityBoneResolver(session.cache(), subject, this.geometryRef, this.diagnostics.child("bones"));
    }

    /**
     * The family node - invocation order IS on-disk member order (SPINE 3.1, normative).
     */
    @NotNull JsonNode resolve() {
        return JsonNode.object()
            .put("renderer", this.subject.rendererClass())                              // provenance scalar (resolver-owned)
            .putIf("geometry", this.geometryRef.resolve())                              // -> manifest key
            .put("armor_type", new EntityArmorTypeResolver(this.layerRoster).resolve())
            .putIf("texture", this.texture.resolve())
            .putIf("render", this.renderTraits.resolve())                               // {scale?, yaw_addend?, tint?}
            .putIf("bones", this.bones.resolve());                                      // {hidden?, toggles?}
    }   // rows 7-16 (axes / overlays / block_overlays / layers; family_of post-pass) land per plan sessions

    /**
     * One {@code addLayer(new XLayer(...))} call site in the renderer constructor chain.
     * Private-helper shape (marked as such; not a SPINE 2 name).
     *
     * @param layerClass the constructed layer class's JVM internal name
     * @param layerIndex the row's position in the roster (d18)
     */
    record LayerSite(@NotNull String layerClass, int layerIndex) {}

    /**
     * Walks the renderer's constructor chain for {@code addLayer(new XLayer(...))} call
     * sites, in walk order, keeping same-class duplicates.
     */
    private @NotNull List<LayerSite> scanLayerRoster(@NotNull ToolingSession session) {
        List<String> classes = new ArrayList<>();
        AsmKit.walkConstructorChain(session.cache(), this.subject.rendererClass(), ctor -> {
            for (AbstractInsnNode in = ctor.instructions.getFirst(); in != null; in = in.getNext()) {
                // Owner-agnostic addLayer match - the renderer's super may be any of several
                // LivingEntityRenderer subclasses; gate on the canonical descriptor shape
                // (single Layer arg, boolean return).
                if (in.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                if (!(in instanceof MethodInsnNode mi)) continue;
                if (!VanillaSourceClasses.Methods.ADD_LAYER.equals(mi.name)) continue;
                if (!mi.desc.startsWith("(L") || !mi.desc.endsWith(";)Z")) continue;
                String layerClass = findPrecedingLayerNew(in);
                if (layerClass != null) classes.add(layerClass);
            }
        });
        List<LayerSite> out = new ArrayList<>(classes.size());
        for (int index = 0; index < classes.size(); index++) out.add(new LayerSite(classes.get(index), index));
        return out;
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
    private static @Nullable String findPrecedingLayerNew(@NotNull AbstractInsnNode addLayerInsn) {
        AbstractInsnNode cursor = addLayerInsn.getPrevious();
        int depth = 0;
        int pendingInits = 0;
        while (cursor != null && depth < 64) {
            depth++;
            if (cursor.getOpcode() == Opcodes.INVOKESPECIAL
                && cursor instanceof MethodInsnNode mi
                && AsmKit.INIT.equals(mi.name))
                pendingInits++;
            if (cursor.getOpcode() == Opcodes.NEW && cursor instanceof TypeInsnNode alloc) {
                pendingInits--;
                if (pendingInits == 0) return alloc.desc;
            }
            cursor = cursor.getPrevious();
        }
        return null;
    }

}
