package lib.minecraft.renderer.tooling.vanilla;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.AllArgsConstructor;
import lib.minecraft.renderer.tooling.geometry.BabyMeshTransform;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Cells;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
 * <p><b>Baby sets are indexed too, and they are a different mesh rather than a smaller one.</b>
 * Vanilla's {@code createBabyArmorMeshSet(inner, outer, PartPose)} builds
 * {@code createBabyArmorMesh}, whose boxes, part list and unwrap are its own: a {@code waist} part
 * the adult mesh has no counterpart for, a pair of feet parented under the legs, and a 64x64 sheet
 * against the adult's 64x32. The trailing pose is a mesh argument rather than a layer one - it seats
 * the shell's two arms - so it rides the geometry request and distinguishes the piglin family's baby
 * mesh from the generic one. Its base factory sits behind a <em>capturing</em> lambda, so the
 * {@code INVOKEDYNAMIC} chase resolves the handle and then descends one hop into it.
 *
 * <p><b>The texture size is read, not assumed.</b> Every set reaches its registration through an
 * {@code ArmorModelSet.map} whose lambda wraps each slot's mesh in
 * {@code LayerDefinition.create(mesh, w, h)}, and that call site is the only statement of the
 * atlas the unwrap is resolved against. A later {@code map} that wraps rather than creates - the
 * whole-mesh scale below - leaves the size it was already carrying alone.
 *
 * <p><b>The whole-mesh scale is carried, and the mesh stays unscaled.</b> Three sets are registered
 * through {@code ArmorModelSet.map(MeshTransformer.scaling(F))} - giant {@code 6.0}, husk
 * {@code 1.0625}, wither skeleton {@code 1.2} - and the factor rides the wearer's armor row rather
 * than the geometry, so the four sets that share the generic mesh still share one geometry entry.
 * The renderer applies it to the shell alone; the wearer's own body carries the same transformer
 * baked into its geometry, so nothing is applied twice.
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class ArmorMeshIndex {

    /**
     * Method descriptor of {@code ArmorModelSet.putFrom(ArmorModelSet, ImmutableMap$Builder)}.
     */
    private static final @NotNull String PUT_FROM_PREFIX =
        "(" + VanillaSourceClasses.Descs.ARMOR_MODEL_SET_REF;

    /**
     * Guard on the delegate chain, well above vanilla's deepest ({@code piglin -> player -> humanoid}).
     */
    private static final int MAX_DELEGATE_DEPTH = 4;

    /**
     * One armor set vanilla builds: the mesh every slot is cut from, the atlas it is unwrapped
     * against, and the per-side growth the two armor layers apply to it. The mesh is parsed ungrown
     * and the two growths travel on the wearer's row, so two sets differing only in a deformation
     * share one geometry entry.
     *
     * @param meshClass the base mesh factory's class JVM internal name
     * @param meshMethod the base mesh factory method name
     * @param meshDesc the base mesh factory's descriptor - what says whether it takes a pose, and in
     *     which parameter slot
     * @param innerGrow the 3-component growth the leggings layer applies
     * @param outerGrow the 3-component growth the helmet / chestplate / boots layer applies
     * @param meshScale the whole-mesh uniform scale the set is registered through, {@code 1} for the
     *     sets registered unscaled
     * @param textureWidth the atlas width the set's slots are wrapped at
     * @param textureHeight the atlas height the set's slots are wrapped at
     * @param armOffset the 3-component offset the mesh factory seats the shell's arms through,
     *     {@code {0, 0, 0}} for the adult sets and for the baby set vanilla passes an unset pose to
     * @param babyParts whether the set's factory hands its fan-out the aged-down per-slot part
     *     table - read off the {@code *_ARMOR_PARTS_PER_SLOT} constant the factory passes, because
     *     that is where vanilla decides it and it does not follow from the set's name
     * @param babyTransform the aged-down whole-mesh transformer the registration re-applies the set
     *     through, or {@code null} for a set registered untransformed. It is what makes the small
     *     armor stand's shell a different shell rather than the adult one under another name
     */
    public record Set(
        @NotNull String meshClass,
        @NotNull String meshMethod,
        @NotNull String meshDesc,
        float @NotNull [] innerGrow,
        float @NotNull [] outerGrow,
        float meshScale,
        int textureWidth,
        int textureHeight,
        float @NotNull [] armOffset,
        boolean babyParts,
        @Nullable BabyMeshTransform babyTransform
    ) {

        /**
         * The unset arm offset - what an adult set, and a baby set at {@code PartPose.ZERO}, carries.
         */
        public static final float @NotNull [] NO_ARM_OFFSET = {0f, 0f, 0f};

        /**
         * Returns a copy carrying {@code scale} as its whole-mesh scale.
         *
         * <p>A copy because the transformer is applied to a copy in vanilla too - the operand the
         * scaled wearers rewrite is the shared generic set every unscaled wearer also registers, so
         * stamping it in place would scale all of them.
         *
         * @param scale the whole-mesh uniform scale
         * @return an otherwise-identical set registered through that scale
         */
        @NotNull Set scaled(float scale) {
            return new Set(this.meshClass, this.meshMethod, this.meshDesc, this.innerGrow, this.outerGrow,
                scale, this.textureWidth, this.textureHeight, this.armOffset, this.babyParts, this.babyTransform);
        }

        /**
         * Returns a copy registered through an aged-down whole-mesh transformer.
         *
         * <p>A copy for the same reason {@link #scaled} makes one - the operand the small armor
         * stand re-registers is the very set the full-size one is registered from, so stamping it
         * in place would shrink both.
         *
         * @param transform the transformer the registration applies
         * @return an otherwise-identical set registered through it
         */
        @NotNull Set transformed(@NotNull BabyMeshTransform transform) {
            return new Set(this.meshClass, this.meshMethod, this.meshDesc, this.innerGrow, this.outerGrow,
                this.meshScale, this.textureWidth, this.textureHeight, this.armOffset, this.babyParts, transform);
        }

        /**
         * Returns a copy unwrapped against the given atlas.
         *
         * @param width the atlas width
         * @param height the atlas height
         * @return an otherwise-identical set carrying that atlas
         */
        @NotNull Set wrappedAt(int width, int height) {
            return new Set(this.meshClass, this.meshMethod, this.meshDesc, this.innerGrow, this.outerGrow,
                this.meshScale, width, height, this.armOffset, this.babyParts, this.babyTransform);
        }

        /**
         * Whether another set dresses its wearer in the very same shell - same mesh, same two
         * deformations, same scale, same atlas, same pose.
         *
         * <p>Vanilla's way of saying a wearer has no distinct baby form is to hand its armor layer
         * one set twice, and the piglin brute - which passes literally the same field twice - is
         * caught here rather than by name. The small armor stand is not: its second set is the
         * adult one re-registered through an aged-down transformer, which builds genuinely
         * different boxes, so it compares as the distinct shell it is.
         *
         * @param other the set to compare against
         * @return whether the two sets are the same shell
         */
        public boolean sameShellAs(@NotNull Set other) {
            return this.meshClass.equals(other.meshClass)
                && this.meshMethod.equals(other.meshMethod)
                && Arrays.equals(this.innerGrow, other.innerGrow)
                && Arrays.equals(this.outerGrow, other.outerGrow)
                && this.meshScale == other.meshScale
                && this.textureWidth == other.textureWidth
                && this.textureHeight == other.textureHeight
                && Arrays.equals(this.armOffset, other.armOffset)
                && Objects.equals(this.babyTransform, other.babyTransform);
        }

        /**
         * The parameter slot the mesh factory takes its pose in, or {@code -1} when it takes none.
         *
         * <p>Read off the descriptor rather than assumed, because it is the descriptor that says
         * whether this set's mesh is seated through a pose at all - the adult factories take only a
         * deformation. Every argument is a reference, so the argument index is the slot index and the
         * factory is static, so there is no receiver ahead of them.
         *
         * @return the pose parameter's local-variable slot, or {@code -1}
         */
        public int poseSlot() {
            Type[] arguments = Type.getArgumentTypes(this.meshDesc);
            for (int index = 0; index < arguments.length; index++)
                if (arguments[index].getSort() == Type.OBJECT
                    && VanillaSourceClasses.Types.PART_POSE.equals(arguments[index].getInternalName())) return index;
            return -1;
        }
    }

    private final @NotNull Map<String, Set> sets;

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
        MethodNode createRoots = ClassKit.findMethod(cn, VanillaSourceClasses.Methods.CREATE_ROOTS);
        if (createRoots == null) {
            diagnostics.error("'%s.%s' missing - armor-mesh index unresolved",
                VanillaSourceClasses.Types.LAYER_DEFINITIONS, VanillaSourceClasses.Methods.CREATE_ROOTS);
            return new ArmorMeshIndex(out);
        }

        // Armor sets reach their registration through a local slot in every adult case, so the walk
        // tracks the ASTORE and re-reads it at the ALOAD that feeds putFrom.
        Cells.Slots<Set> slots = Cells.slots();
        // A MeshTransformer.scaling(F) reaches its registration through a slot of its own, so the
        // factor is tracked the same way the set is and re-read at the ALOAD that feeds map().
        Cells.Slots<Float> scaleSlots = Cells.slots();
        // The two most recent CubeDeformations pushed onto the operand stack. A set factory takes
        // (inner, outer) adjacently, so at the call these are exactly its two arguments. A shift
        // survives an unreadable deformation as a cleared latch, which reads as the null the
        // unreadable-deformations drop arm expects.
        Cells.Latch<float[]> priorGrow = Cells.latch();
        Cells.Latch<float[]> latestGrow = Cells.latch();
        // The pose most recently pushed - the third argument of a baby set factory, and nothing
        // else in createRoots reaches one.
        Cells.Latch<float[]> latestPose = Cells.latch();
        Cells.Window<Float> pendingFloat = Cells.window(AsmWalker::floatLiteral, 1);
        Cells.Latch<Float> pendingScale = Cells.latch();
        Cells.Latch<Handle> pendingLambda = Cells.latch();
        Cells.Latch<Set> pendingSet = Cells.latch();
        Cells.Latch<String> pendingSetField = Cells.latch();

        AsmWalker.over(createRoots)
            .feed(pendingFloat)
            .feed(slots)
            .feed(scaleSlots)
            .feed(priorGrow)
            .feed(latestGrow)
            .feed(latestPose)
            .feed(pendingScale)
            .feed(pendingLambda)
            .feed(pendingSet)
            .feed(pendingSetField)
            // `new CubeDeformation(F); <init>` - the piglin's inline 1.02 outer.
            .on(Insn.invokeSpecial(VanillaSourceClasses.Types.CUBE_DEFORMATION, ClassKit.INIT), mi -> {
                if (mi.desc.startsWith("(F") && pendingFloat.size() == 1) {
                    float grow = pendingFloat.values().getFirst();
                    float[] shifted = latestGrow.get();
                    if (shifted != null) priorGrow.set(shifted); else priorGrow.clear();
                    latestGrow.set(new float[]{grow, grow, grow});
                }
                pendingFloat.clear();
            })
            // `GETSTATIC <owner>.<field>: CubeDeformation` - the named INNER / OUTER constants.
            .on(Insn.of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.GETSTATIC
                && VanillaSourceClasses.Descs.CUBE_DEFORMATION_REF.equals(fi.desc)), fi -> {
                float[] shifted = latestGrow.get();
                if (shifted != null) priorGrow.set(shifted); else priorGrow.clear();
                float[] resolved = LayerDefinitionIndex.resolveDeformationField(cache, fi.owner, fi.name);
                if (resolved != null) latestGrow.set(resolved); else latestGrow.clear();
            })
            // `GETSTATIC <owner>.<field>: PartPose` - the baby set factory's arm offset.
            .on(Insn.of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.GETSTATIC
                && VanillaSourceClasses.Descs.PART_POSE_REF.equals(fi.desc)), fi -> {
                float[] resolved = LayerDefinitionIndex.resolvePartPoseField(cache, fi.owner, fi.name);
                if (resolved != null) latestPose.set(resolved); else latestPose.clear();
            })
            // `GETSTATIC ModelLayers.<X>_ARMOR: ArmorModelSet` - the registration target.
            .on(Insn.getStatic(VanillaSourceClasses.Types.MODEL_LAYERS)
                .and(fi -> VanillaSourceClasses.Descs.ARMOR_MODEL_SET_REF.equals(fi.desc)), fi -> {
                pendingSetField.set(fi.name);
                pendingSet.clear();
            })
            // `INVOKESTATIC <Model>.createArmorMeshSet|createArmorLayerSet(inner, outer)`.
            .on(Insn.of(MethodInsnNode.class, mi -> mi.getOpcode() == Opcodes.INVOKESTATIC
                && VanillaSourceClasses.Descs.ARMOR_MESH_SET_DESC.equals(mi.desc)
                && isArmorSetFactory(mi.name)), mi -> {
                Set resolved = resolveSet(cache, mi.owner, mi.name, mi.desc, priorGrow.get(),
                    latestGrow.get(), Set.NO_ARM_OFFSET, diagnostics);
                if (resolved != null) pendingSet.set(resolved); else pendingSet.clear();
                priorGrow.clear();
                latestGrow.clear();
            })
            // `INVOKESTATIC <Model>.createBabyArmorMeshSet(inner, outer, armOffset)` - the same
            // shape one argument wider, and the argument is what makes the piglin family's baby
            // shell a distinct mesh rather than a distinct deformation.
            .on(Insn.of(MethodInsnNode.class, mi -> mi.getOpcode() == Opcodes.INVOKESTATIC
                && VanillaSourceClasses.Descs.BABY_ARMOR_MESH_SET_DESC.equals(mi.desc)
                && VanillaSourceClasses.Methods.CREATE_BABY_ARMOR_MESH_SET.equals(mi.name)), mi -> {
                Set resolved = resolveSet(cache, mi.owner, mi.name, mi.desc, priorGrow.get(),
                    latestGrow.get(), latestPose.get(), diagnostics);
                if (resolved != null) pendingSet.set(resolved); else pendingSet.clear();
                priorGrow.clear();
                latestGrow.clear();
                latestPose.clear();
            })
            // `INVOKESTATIC MeshTransformer.scaling(F)` - the whole-mesh scale the giant, the husk
            // and the wither skeleton register the generic set through.
            .on(Insn.invokeStatic(VanillaSourceClasses.Types.MESH_TRANSFORMER,
                VanillaSourceClasses.Methods.SCALING), mi -> {
                if (pendingFloat.size() == 1) pendingScale.set(pendingFloat.values().getFirst());
                else pendingScale.clear();
                pendingFloat.clear();
            })
            // The lambda a following `map` rewrites each slot through - the wrap that states the
            // atlas, or the transformer wrap that only re-applies a scale.
            .on(Insn.ofType(InvokeDynamicInsnNode.class), indy -> {
                Handle handle = AsmWalker.extractLambdaHandle(indy);
                if (handle != null) pendingLambda.set(handle); else pendingLambda.clear();
            })
            .on(Insn.of(VarInsnNode.class, vi -> vi.getOpcode() == Opcodes.ASTORE), vi -> {
                Set parked = pendingSet.get();
                Float factor = pendingScale.get();
                if (parked != null) slots.store(vi.var, parked);
                else if (factor != null) scaleSlots.store(vi.var, factor);
                pendingSet.clear();
                pendingScale.clear();
            })
            .on(Insn.of(VarInsnNode.class, vi -> vi.getOpcode() == Opcodes.ALOAD), vi -> {
                Set stored = slots.load(vi.var);
                if (stored != null) pendingSet.set(stored);
                Float storedScale = scaleSlots.load(vi.var);
                if (storedScale != null) pendingScale.set(storedScale);
            })
            // `INVOKEVIRTUAL ArmorModelSet.map(Function)` - the transformer stamps onto a COPY, so
            // the shared generic set the scaled wearers load is left unscaled for everyone else.
            // Only a map that follows a scaling() carries one: createRoots makes ten map calls and
            // three of them capture a transformer.
            .on(Insn.invokeVirtual(VanillaSourceClasses.Types.ARMOR_MODEL_SET,
                VanillaSourceClasses.Methods.MAP), mi -> {
                Set current = pendingSet.get();
                Float factor = pendingScale.get();
                if (current != null && factor != null) {
                    current = current.scaled(factor);
                    pendingSet.set(current);
                }
                Handle lambda = pendingLambda.get();
                if (current != null && lambda != null) {
                    int[] atlas = resolveWrapAtlas(cache, lambda);
                    if (atlas != null) {
                        current = current.wrappedAt(atlas[0], atlas[1]);
                        pendingSet.set(current);
                    }
                    // A wrap that applies a transformer rather than creating a LayerDefinition -
                    // the small armor stand's, and the only one in createRoots.
                    BabyMeshTransform baby = resolveWrapBabyTransform(cache, lambda);
                    if (baby != null) pendingSet.set(current.transformed(baby));
                }
                pendingScale.clear();
                pendingLambda.clear();
            })
            // The registration: an unarmed putFrom leaves every cell live, so the armed guards
            // ride the recognizer and the reset touches nothing beyond the commit's own pair.
            .commitAt(Insn.invokeVirtual(VanillaSourceClasses.Types.ARMOR_MODEL_SET,
                    VanillaSourceClasses.Methods.PUT_FROM)
                .and(mi -> mi.desc.startsWith(PUT_FROM_PREFIX)
                    && pendingSetField.get() != null
                    && pendingSet.get() != null), mi -> {
                Set set = pendingSet.get();
                String field = pendingSetField.get();
                if (set.textureWidth() > 0 && set.textureHeight() > 0)
                    out.put(field.toLowerCase(Locale.ROOT), set);
                else
                    diagnostics.warn("armor set '%s' names no atlas through its slot wrap - set dropped",
                        field);
            })
            .clearing(pendingSetField, pendingSet)
            .run();

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

    /**
     * Whether a method name is one of the three spellings vanilla gives an armor-set factory.
     */
    private static boolean isArmorSetFactory(@NotNull String name) {
        return VanillaSourceClasses.Methods.CREATE_ARMOR_MESH_SET.equals(name)
            || VanillaSourceClasses.Methods.CREATE_ARMOR_LAYER_SET.equals(name)
            || VanillaSourceClasses.Methods.CREATE_BABY_ARMOR_MESH_SET.equals(name);
    }

    /**
     * Resolves one armor-set factory call site into a set: the base mesh factory behind the delegate
     * chain, paired with the two call-site deformations and the pose the baby factory seats its arms
     * through. A call site whose deformations or pose could not be read is dropped with a WARN rather
     * than defaulted, since a silently zero-grown armor shell would render inside the body it
     * dresses and a silently unset pose would put the piglin family's baby arms where the generic
     * baby's are.
     */
    private static @Nullable Set resolveSet(
        @NotNull ClassNodeCache cache,
        @NotNull String owner,
        @NotNull String method,
        @NotNull String desc,
        float @Nullable [] innerGrow,
        float @Nullable [] outerGrow,
        float @Nullable [] armOffset,
        @NotNull Diagnostics diagnostics
    ) {
        if (innerGrow == null || outerGrow == null) {
            diagnostics.warn("armor set '%s.%s' has unreadable deformations - set dropped", owner, method);
            return null;
        }
        if (armOffset == null) {
            diagnostics.warn("armor set '%s.%s' has an unreadable arm offset - set dropped", owner, method);
            return null;
        }
        Handle base = resolveBaseMeshFactory(cache, owner, method, desc, 0);
        if (base == null) {
            diagnostics.warn("armor set '%s.%s' has no resolvable base mesh factory - set dropped", owner, method);
            return null;
        }
        int[] atlas = resolveFactoryAtlas(cache, owner, method, desc, 0);
        return new Set(base.getOwner(), base.getName(), base.getDesc(), innerGrow.clone(), outerGrow.clone(),
            1f, atlas == null ? 0 : atlas[0], atlas == null ? 0 : atlas[1], armOffset.clone(),
            resolveBabyParts(cache, owner, method, desc, 0), null);
    }

    /**
     * Whether a set factory hands its four-slot fan-out the aged-down per-slot part table.
     *
     * <p>Read rather than taken from the factory's name, because the two do not have to agree: the
     * armor stand's small set is built by an <em>adult</em> factory and re-registered through an
     * aged-down transformer, so it keeps the adult table - which is what puts its shell on the
     * full-size sheets and lets it draw a trim, exactly as {@code HumanoidArmorLayer} carves it out
     * of the baby layer type. The walk follows the same delegate chain the atlas read does.
     */
    private static boolean resolveBabyParts(
        @NotNull ClassNodeCache cache,
        @NotNull String owner,
        @NotNull String method,
        @NotNull String desc,
        int depth
    ) {
        if (depth > MAX_DELEGATE_DEPTH) return false;
        MethodNode node = ClassKit.findMethodInHierarchy(cache, owner, method, desc);
        if (node == null) return false;

        FieldInsnNode parts = AsmWalker.over(node)
            .opcode(Opcodes.GETSTATIC)
            .ofType(FieldInsnNode.class)
            .where(fi -> fi.name.endsWith(VanillaSourceClasses.Fields.ARMOR_PARTS_PER_SLOT_SUFFIX))
            .first();
        if (parts != null) return parts.name.startsWith(VanillaSourceClasses.Fields.BABY_ARMOR_PARTS_PREFIX);

        MethodInsnNode delegate = AsmWalker.over(node)
            .opcode(Opcodes.INVOKESTATIC)
            .ofType(MethodInsnNode.class)
            .where(mi -> desc.equals(mi.desc)
                && isArmorSetFactory(mi.name)
                && !mi.owner.equals(owner))
            .first();
        if (delegate != null) return resolveBabyParts(cache, delegate.owner, delegate.name, desc, depth + 1);
        return false;
    }

    /**
     * The aged-down transformer a registration's slot-wrap lambda applies, or {@code null} when the
     * lambda applies none - which is every wrap but one.
     */
    private static @Nullable BabyMeshTransform resolveWrapBabyTransform(
        @NotNull ClassNodeCache cache, @NotNull Handle lambda) {
        MethodNode node = ClassKit.findMethodInHierarchy(cache, lambda.getOwner(), lambda.getName(), lambda.getDesc());
        if (node == null) return null;
        return AsmWalker.over(node)
            .ofType(FieldInsnNode.class)
            .where(fi -> fi.getOpcode() == Opcodes.GETSTATIC
                && VanillaSourceClasses.Descs.MESH_TRANSFORMER_REF.equals(fi.desc))
            .firstNotNull(fi -> BabyMeshTransform.resolve(cache, fi.owner, fi.name));
    }

    /**
     * The atlas a set factory wraps its own four slots at, or {@code null} when it hands back bare
     * meshes for the registration to wrap.
     *
     * <p>Vanilla spells the wrap in whichever of the two places suits the factory's return type: a
     * factory yielding {@code ArmorModelSet<MeshDefinition>} leaves it to the {@code map} at the
     * registration, while one yielding {@code ArmorModelSet<LayerDefinition>} - the armor stand's and
     * the zombie villager's - has already applied it inside itself. Looking in both is what keeps the
     * atlas derived for every set rather than for most of them.
     */
    private static int @Nullable [] resolveFactoryAtlas(
        @NotNull ClassNodeCache cache,
        @NotNull String owner,
        @NotNull String method,
        @NotNull String desc,
        int depth
    ) {
        if (depth > MAX_DELEGATE_DEPTH) return null;
        MethodNode node = ClassKit.findMethodInHierarchy(cache, owner, method, desc);
        if (node == null) return null;

        int[] direct = atlasIn(node);
        if (direct != null) return direct;

        int[] wrapped = AsmWalker.over(node)
            .ofType(InvokeDynamicInsnNode.class)
            .mapNotNull(AsmWalker::extractLambdaHandle)
            .firstNotNull(handle -> resolveWrapAtlas(cache, handle));
        if (wrapped != null) return wrapped;

        MethodInsnNode delegate = AsmWalker.over(node)
            .opcode(Opcodes.INVOKESTATIC)
            .ofType(MethodInsnNode.class)
            .where(mi -> desc.equals(mi.desc)
                && isArmorSetFactory(mi.name)
                && !mi.owner.equals(owner))
            .first();
        if (delegate != null) return resolveFactoryAtlas(cache, delegate.owner, delegate.name, desc, depth + 1);
        return null;
    }

    /**
     * The atlas a lambda body wraps a mesh at, or {@code null} when it wraps something already
     * wrapped ({@code LayerDefinition.apply}) and so states no atlas of its own.
     */
    private static int @Nullable [] resolveWrapAtlas(@NotNull ClassNodeCache cache, @NotNull Handle lambda) {
        MethodNode node = ClassKit.findMethodInHierarchy(cache, lambda.getOwner(), lambda.getName(), lambda.getDesc());
        return node == null ? null : atlasIn(node);
    }

    /**
     * The {@code LayerDefinition.create(mesh, w, h)} dimensions in a method body, or {@code null}.
     */
    private static int @Nullable [] atlasIn(@NotNull MethodNode node) {
        // A create reached before two literals states no atlas and must not clear the pair it has,
        // so the window is retained across commits and read only when full.
        return AsmWalker.over(node)
            .gather(AsmWalker::intLiteral)
            .keep(2)
            .retain()
            .commitAt(Insn.invokeStatic(VanillaSourceClasses.Types.LAYER_DEFINITION,
                VanillaSourceClasses.Methods.CREATE))
            .firstNotNull(commit -> commit.values().size() == 2
                ? new int[]{commit.values().getFirst(), commit.values().getLast()}
                : null);
    }

    /**
     * The base mesh factory an armor-set factory fans out over its four slots. A factory that
     * delegates the whole set to another model's ({@code AbstractPiglinModel} to
     * {@code PlayerModel} to {@code HumanoidModel}) is followed through; otherwise the factory's
     * first mesh-producing {@code INVOKEDYNAMIC} carries it.
     *
     * <p>The adult factory passes its base mesh as a plain method reference, so the handle IS the
     * answer. The baby factory has to close over the pose it seats the arms through, so its handle
     * names a synthesised body that carries the capture ahead of the deformation; the walk descends
     * one hop into that body and takes the call it forwards to. A lambda is recognised by what it
     * produces - a {@code MeshDefinition} - rather than by its javac name.
     */
    private static @Nullable Handle resolveBaseMeshFactory(
        @NotNull ClassNodeCache cache,
        @NotNull String owner,
        @NotNull String method,
        @NotNull String desc,
        int depth
    ) {
        if (depth > MAX_DELEGATE_DEPTH) return null;
        MethodNode node = ClassKit.findMethodInHierarchy(cache, owner, method, desc);
        if (node == null) return null;

        MethodInsnNode delegate = AsmWalker.over(node)
            .opcode(Opcodes.INVOKESTATIC)
            .ofType(MethodInsnNode.class)
            .where(mi -> desc.equals(mi.desc)
                && isArmorSetFactory(mi.name)
                && !mi.owner.equals(owner))
            .first();
        if (delegate != null) return resolveBaseMeshFactory(cache, delegate.owner, delegate.name, desc, depth + 1);

        return AsmWalker.over(node)
            .ofType(InvokeDynamicInsnNode.class)
            .firstNotNull(indy -> {
                Handle handle = AsmWalker.extractLambdaHandle(indy);
                if (handle == null
                    || !ClassKit.descriptorReturns(handle.getDesc(), VanillaSourceClasses.Types.MESH_DEFINITION))
                    return null;
                if (VanillaSourceClasses.Descs.BASE_ARMOR_MESH_DESC.equals(handle.getDesc())) return handle;
                return resolveForwardedMeshFactory(cache, handle);
            });
    }

    /**
     * The mesh factory a capturing lambda body forwards to - the single {@code INVOKESTATIC}
     * producing a {@code MeshDefinition} inside it - or {@code null} when the body reaches none.
     */
    private static @Nullable Handle resolveForwardedMeshFactory(
        @NotNull ClassNodeCache cache, @NotNull Handle lambda) {
        MethodNode node = ClassKit.findMethodInHierarchy(cache, lambda.getOwner(), lambda.getName(), lambda.getDesc());
        if (node == null) return null;
        MethodInsnNode forward = AsmWalker.over(node)
            .opcode(Opcodes.INVOKESTATIC)
            .ofType(MethodInsnNode.class)
            .where(mi -> ClassKit.descriptorReturns(mi.desc, VanillaSourceClasses.Types.MESH_DEFINITION))
            .first();
        return forward == null ? null
            : new Handle(Opcodes.H_INVOKESTATIC, forward.owner, forward.name, forward.desc, false);
    }

}
