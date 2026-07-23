package lib.minecraft.renderer.tooling.vanilla;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Walks {@code LayerDefinitions.createRoots} once per session for the worn-armor meshes, keying
 * every {@code ModelLayers} armor-set field to the base mesh factory it is built from plus the two
 * deformations that grow it.
 *
 * <p>Vanilla never derives worn armor from the wearer's own model. It builds a handful of
 * {@code ArmorModelSet}s - one shared humanoid set plus a few wearers with their own - and hands
 * each renderer the one its subject wears. Every set is
 * {@code <Model>.createArmorMeshSet(inner, outer)} (or the {@code createArmorLayerSet} spelling),
 * which fans one base mesh factory out over the four slots at two deformations: the outer for
 * helmet / chestplate / boots, the inner for leggings. That base factory rides the set's first
 * {@code INVOKEDYNAMIC} as a method reference, so it is read off the handle rather than guessed.
 *
 * <p>A set built by a delegating factory ({@code AbstractPiglinModel -> PlayerModel ->
 * HumanoidModel}) resolves through to the mesh factory that actually builds boxes; the intervening
 * {@code map} calls only prune child parts this renderer does not build.
 *
 * <p>The two deformations are carried, not baked. A wearer's armor row registers the mesh factory
 * ungrown and writes the pair alongside its geometry reference, so the piglin family - which differs
 * from the generic set in nothing but an outer {@code 1.02} - shares the generic set's geometry entry
 * rather than duplicating it.
 *
 * <p><b>Adult sets only.</b> Vanilla's baby sets take a third {@code PartPose} argument and a
 * different base mesh (an extra {@code waist} part, its own unwrap); they are filtered out by that
 * descriptor, because a baby still wears its own bone boxes here.
 *
 * <p><b>The whole-mesh scale is deliberately not carried.</b> Three sets are registered through
 * {@code ArmorModelSet.map(MeshTransformer.scaling(F))} - giant {@code 6.0}, husk {@code 1.0625},
 * wither skeleton {@code 1.2} - but vanilla applies the very same transformer to those wearers'
 * own body layers, and the renderer already reads that scale off the wearer's torso bone. Carrying
 * it on the mesh as well would apply it twice.
 */
public final class ArmorMeshIndex {

    /** Method descriptor of {@code ArmorModelSet.putFrom(ArmorModelSet, ImmutableMap$Builder)}. */
    private static final @NotNull String PUT_FROM_PREFIX =
        "(" + VanillaSourceClasses.Descs.ARMOR_MODEL_SET_REF;

    /** Guard on the delegate chain, well above vanilla's deepest ({@code piglin -> player -> humanoid}). */
    private static final int MAX_DELEGATE_DEPTH = 4;

    /**
     * One armor set vanilla builds: the mesh every slot is cut from, plus the per-side growth the
     * two armor layers apply to it. The mesh is parsed ungrown and the two growths travel on the
     * wearer's row, so two sets differing only in a deformation share one geometry entry.
     *
     * @param meshClass the base mesh factory's class JVM internal name
     * @param meshMethod the base mesh factory method name
     * @param innerGrow the 3-component growth the leggings layer applies
     * @param outerGrow the 3-component growth the helmet / chestplate / boots layer applies
     */
    public record Set(
        @NotNull String meshClass,
        @NotNull String meshMethod,
        float @NotNull [] innerGrow,
        float @NotNull [] outerGrow
    ) {}

    private final @NotNull Map<String, Set> sets;

    private ArmorMeshIndex(@NotNull Map<String, Set> sets) {
        this.sets = sets;
    }

    /**
     * Walks {@code createRoots} once and builds the index.
     *
     * @param session the live session
     * @return the built index (empty on a missing class / method, ERROR recorded)
     */
    public static @NotNull ArmorMeshIndex build(@NotNull ToolingSession session) {
        ClassNodeCache cache = session.cache();
        Diagnostics diagnostics = session.diagnostics().child("armorMeshes");
        Map<String, Set> out = new LinkedHashMap<>();

        ClassNode cn = cache.load(VanillaSourceClasses.Types.LAYER_DEFINITIONS);
        if (cn == null) {
            diagnostics.error("'%s' class missing - armor-mesh index unresolved",
                VanillaSourceClasses.Types.LAYER_DEFINITIONS);
            return new ArmorMeshIndex(out);
        }
        MethodNode createRoots = AsmKit.findMethod(cn, VanillaSourceClasses.Methods.CREATE_ROOTS);
        if (createRoots == null) {
            diagnostics.error("'%s.%s' missing - armor-mesh index unresolved",
                VanillaSourceClasses.Types.LAYER_DEFINITIONS, VanillaSourceClasses.Methods.CREATE_ROOTS);
            return new ArmorMeshIndex(out);
        }

        // Armor sets reach their registration through a local slot in every adult case, so the walk
        // tracks the ASTORE and re-reads it at the ALOAD that feeds putFrom.
        AsmKit.SlotTracker<Set> slots = new AsmKit.SlotTracker<>();
        // The two most recent CubeDeformations pushed onto the operand stack. A set factory takes
        // (inner, outer) adjacently, so at the call these are exactly its two arguments.
        float[] priorGrow = null;
        float[] latestGrow = null;
        Float pendingFloat = null;
        Set pendingSet = null;
        String pendingSetField = null;

        for (AbstractInsnNode in = createRoots.instructions.getFirst(); in != null; in = in.getNext()) {
            int opcode = in.getOpcode();

            Float asFloat = AsmKit.readFloatLiteral(in);
            if (asFloat != null) {
                pendingFloat = asFloat;
                continue;
            }

            // `new CubeDeformation(F); <init>` - the piglin's inline 1.02 outer.
            if (in instanceof MethodInsnNode mi
                && opcode == Opcodes.INVOKESPECIAL
                && AsmKit.INIT.equals(mi.name)
                && VanillaSourceClasses.Types.CUBE_DEFORMATION.equals(mi.owner)) {
                if (mi.desc.startsWith("(F") && pendingFloat != null) {
                    priorGrow = latestGrow;
                    latestGrow = new float[]{pendingFloat, pendingFloat, pendingFloat};
                }
                pendingFloat = null;
                continue;
            }

            // `GETSTATIC <owner>.<field>: CubeDeformation` - the named INNER / OUTER constants.
            if (in instanceof FieldInsnNode fi && opcode == Opcodes.GETSTATIC
                && VanillaSourceClasses.Descs.CUBE_DEFORMATION_REF.equals(fi.desc)) {
                priorGrow = latestGrow;
                latestGrow = LayerDefinitionIndex.resolveDeformationField(cache, fi.owner, fi.name);
                continue;
            }

            // `GETSTATIC ModelLayers.<X>_ARMOR: ArmorModelSet` - the registration target.
            if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.MODEL_LAYERS)
                && VanillaSourceClasses.Descs.ARMOR_MODEL_SET_REF.equals(((FieldInsnNode) in).desc)) {
                pendingSetField = ((FieldInsnNode) in).name;
                pendingSet = null;
                continue;
            }

            // `INVOKESTATIC <Model>.createArmorMeshSet|createArmorLayerSet(inner, outer)`.
            if (in instanceof MethodInsnNode mi
                && opcode == Opcodes.INVOKESTATIC
                && VanillaSourceClasses.Descs.ARMOR_MESH_SET_DESC.equals(mi.desc)
                && isArmorSetFactory(mi.name)) {
                pendingSet = resolveSet(cache, mi.owner, mi.name, priorGrow, latestGrow, diagnostics);
                priorGrow = null;
                latestGrow = null;
                continue;
            }

            if (in instanceof VarInsnNode vi && opcode == Opcodes.ASTORE && pendingSet != null) {
                slots.store(vi.var, pendingSet);
                pendingSet = null;
                continue;
            }

            if (in instanceof VarInsnNode vi && opcode == Opcodes.ALOAD) {
                Set stored = slots.load(vi.var);
                if (stored != null) pendingSet = stored;
                continue;
            }

            if (in instanceof MethodInsnNode mi
                && opcode == Opcodes.INVOKEVIRTUAL
                && VanillaSourceClasses.Methods.PUT_FROM.equals(mi.name)
                && VanillaSourceClasses.Types.ARMOR_MODEL_SET.equals(mi.owner)
                && mi.desc.startsWith(PUT_FROM_PREFIX)
                && pendingSetField != null
                && pendingSet != null) {
                out.put(pendingSetField.toLowerCase(Locale.ROOT), pendingSet);
                pendingSetField = null;
                pendingSet = null;
            }
        }

        diagnostics.info("indexed %d armor sets over %d distinct meshes", out.size(),
            out.values().stream().map(set -> set.meshClass() + '#' + set.meshMethod()).distinct().count());
        return new ArmorMeshIndex(out);
    }

    /**
     * The set a wearer's named armor mesh resolves to, or {@code null} when vanilla registers none
     * under that name.
     *
     * @param armorMeshName the lowercased {@code ModelLayers} armor-set field name
     * @return the resolved set, or {@code null}
     */
    public @Nullable Set get(@NotNull String armorMeshName) {
        return this.sets.get(armorMeshName);
    }

    /** Whether a method name is one of the two spellings vanilla gives its adult armor-set factory. */
    private static boolean isArmorSetFactory(@NotNull String name) {
        return VanillaSourceClasses.Methods.CREATE_ARMOR_MESH_SET.equals(name)
            || VanillaSourceClasses.Methods.CREATE_ARMOR_LAYER_SET.equals(name);
    }

    /**
     * Resolves one {@code createArmorMeshSet(inner, outer)} call site into a set: the base mesh
     * factory behind the delegate chain, paired with the two call-site deformations. A call site
     * whose deformations could not be read is dropped with a WARN rather than defaulted, since a
     * silently zero-grown armor shell would render inside the body it dresses.
     */
    private static @Nullable Set resolveSet(
        @NotNull ClassNodeCache cache,
        @NotNull String owner,
        @NotNull String method,
        float @Nullable [] innerGrow,
        float @Nullable [] outerGrow,
        @NotNull Diagnostics diagnostics
    ) {
        if (innerGrow == null || outerGrow == null) {
            diagnostics.warn("armor set '%s.%s' has unreadable deformations - set dropped", owner, method);
            return null;
        }
        Handle base = resolveBaseMeshFactory(cache, owner, method, 0);
        if (base == null) {
            diagnostics.warn("armor set '%s.%s' has no resolvable base mesh factory - set dropped", owner, method);
            return null;
        }
        return new Set(base.getOwner(), base.getName(), innerGrow.clone(), outerGrow.clone());
    }

    /**
     * The base mesh factory an armor-set factory fans out over its four slots. A factory that
     * delegates the whole set to another model's ({@code AbstractPiglinModel} to
     * {@code PlayerModel} to {@code HumanoidModel}) is followed through; otherwise the factory's
     * first {@code INVOKEDYNAMIC} carries the mesh method reference, and its handle is the answer.
     */
    private static @Nullable Handle resolveBaseMeshFactory(
        @NotNull ClassNodeCache cache, @NotNull String owner, @NotNull String method, int depth) {
        if (depth > MAX_DELEGATE_DEPTH) return null;
        MethodNode node = AsmKit.findMethodInHierarchy(cache, owner, method,
            VanillaSourceClasses.Descs.ARMOR_MESH_SET_DESC);
        if (node == null) return null;

        for (AbstractInsnNode in = node.instructions.getFirst(); in != null; in = in.getNext())
            if (in.getOpcode() == Opcodes.INVOKESTATIC
                && in instanceof MethodInsnNode mi
                && VanillaSourceClasses.Descs.ARMOR_MESH_SET_DESC.equals(mi.desc)
                && isArmorSetFactory(mi.name)
                && !mi.owner.equals(owner))
                return resolveBaseMeshFactory(cache, mi.owner, mi.name, depth + 1);

        for (AbstractInsnNode in = node.instructions.getFirst(); in != null; in = in.getNext()) {
            if (!(in instanceof InvokeDynamicInsnNode indy)) continue;
            Handle handle = AsmKit.extractLambdaHandle(indy);
            if (handle != null && VanillaSourceClasses.Descs.BASE_ARMOR_MESH_DESC.equals(handle.getDesc()))
                return handle;
        }
        return null;
    }

}
