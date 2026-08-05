package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Node {@code axes.age} - the adult / baby option axis. The baby mesh is picked by dataflow
 * from the renderer's {@code state.isBaby} branch rather than by an {@code endsWith("_BABY")}
 * field-name pick: the geometry-ref walk's multi-model constructor consumptions
 * ({@code AgeableMobRenderer.<init>}'s adult + baby pair, cow's {@code AdultAndBabyModelPair})
 * are verified to select on {@code isBaby} - either the consumer chain reads the flag itself
 * ({@code AgeableMobRenderer.submit}) or the renderer feeds it into a boolean-selecting call
 * on the consumer ({@code pair.getModel(isBaby)}) - and the LAST model argument is the baby
 * (the vanilla adult-first constructor convention, javap-pinned on both consumer shapes). A
 * {@code _BABY} field-suffix fallback runs only when the dataflow pick misses, at INFO.
 *
 * <p>A baby is skipped only when it mints the adult's own geometry key - the one case where the
 * option would be a duplicate of the default under a name claiming otherwise. The factory
 * coordinate grammar is what makes that the right question to ask: two meshes off one model class
 * separate by method or by an {@code @}-discriminator, so the nautilus's
 * {@code #createBabyBodyLayer} and the happy ghast's second {@code @scaled} are distinct keys, and
 * only a genuine re-pick of the adult collides.
 *
 * <p>Baby texture chain: variant families carry per-option {@code baby_texture} instead (node emits
 * geometry only); plain families take the renderer's isBaby-branch texture literal, then the
 * {@code <adult>_baby} sibling existence-probed as a declared fallback.
 */
final class EntityAgeAxisResolver {

    /**
     * Forward-scan window from an {@code isBaby} read to its consuming boolean-dispatch call.
     */
    private static final int DISPATCH_WINDOW = 8;

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull LayerDefinitionIndex layerDefinitions;
    private final @NotNull EntityGeometryRefResolver geometryRef;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull Diagnostics diagnostics;

    EntityAgeAxisResolver(@NotNull EntityContext context, @NotNull EntityGeometryRefResolver geometryRef) {
        this.cache = context.cache();
        this.subject = context.subject();
        this.layerDefinitions = context.indexes().layerDefinitions();
        this.geometryRef = geometryRef;
        this.manifest = context.indexes().manifest();
        this.diagnostics = context.diagnostics();
    }

    /**
     * The mandatory age node: {@code options.adult} carries the family baseline - the base
     * {@code geometry} and, for non-variant families, the adult {@code texture} - and
     * {@code options.baby} is added only when a dedicated baby mesh resolves. Every family
     * emits an age axis; the {@code options} key-order IS the domain (no {@code values} list).
     *
     * @param baseGeometry the family's resolved primary geometry key (the adult mesh), or
     *     {@code null} on an unresolvable family
     * @param adultTexture the family's resolved adult texture (full namespaced path), or
     *     {@code null} on variant-axis / unresolved families
     * @param variantFamily whether the family carries a variant axis (baby textures then
     *     live per-option as {@code baby_texture} - the adult / baby options emit geometry only)
     * @param setupYShift the renderer's {@code setupRotations} Y translation as an
     *     {@code {adult, baby}} pair in blocks, or {@code null} when it applies none. It lives on the
     *     age options rather than on {@code render} because vanilla brackets its rotations with
     *     translates the age selects between - a family-level member could not hold both
     * @return the age node (always non-null)
     */
    @NotNull JsonTree resolve(
        @Nullable String baseGeometry, @Nullable String adultTexture, boolean variantFamily,
        float @Nullable [] setupYShift
    ) {
        JsonTree adult = JsonTree.object().putIf("geometry", baseGeometry);
        if (!variantFamily) adult.putIf("texture", adultTexture);
        if (setupYShift != null && setupYShift[0] != 0f) adult.put("y_shift", setupYShift[0]);
        JsonTree node = JsonTree.object().put("default", "adult");
        JsonTree options = node.child("options");
        options.put("adult", adult);
        JsonTree baby = resolveBaby(baseGeometry, adultTexture, variantFamily);
        if (baby != null) {
            if (setupYShift != null && setupYShift[1] != 0f) baby.put("y_shift", setupYShift[1]);
            options.put("baby", baby);
        }
        return node;
    }

    /**
     * The {@code options.baby} delta body, or {@code null} when no dedicated baby mesh resolves
     * (the baby field is unindexed, or the baby mesh IS the adult's).
     *
     * @param baseGeometry the family's resolved primary geometry key, compared against the key the
     *     baby mints so an option that would duplicate the default is dropped
     * @param adultTexture the family's resolved adult texture, the stem the {@code <adult>_baby}
     *     probe works from
     * @param variantFamily whether baby textures live per-option rather than on this node
     * @return the baby delta, or {@code null} when none resolves
     */
    private @Nullable JsonTree resolveBaby(
        @Nullable String baseGeometry, @Nullable String adultTexture, boolean variantFamily
    ) {
        String babyField = pickBabyLayerField();
        if (babyField == null) return null;
        LayerDefinitionIndex.Entry babyEntry = this.layerDefinitions.get(babyField);
        if (babyEntry == null) {
            this.diagnostics.info("baby layer ModelLayers.%s has no LayerDefinitions.createRoots entry - baby option omitted", babyField);
            return null;
        }

        String key = this.manifest.register(GeometryRequest.body(
            babyEntry.factoryClass(), babyEntry.factoryMethod(), this.subject.entityId(),
            babyEntry.texWidthOverride(), babyEntry.texHeightOverride(),
            babyEntry.floatParam(), babyEntry.appliedMeshTransformerScale()));
        // A baby that mints the adult's own key IS the adult mesh, and an option naming it would be a
        // byte-identical copy of the default. Registering first and comparing keys is what asks that
        // exactly: the manifest dedupes by key, so a baby that collides adds no entry to collide with.
        if (key.equals(baseGeometry)) {
            this.diagnostics.info("baby layer ModelLayers.%s mints the adult mesh key - baby option skipped", babyField);
            return null;
        }

        JsonTree baby = JsonTree.object().put("geometry", key);
        if (!variantFamily) baby.putIf("texture", resolveBabyTexture(adultTexture));
        this.diagnostics.info("age axis: baby mesh ModelLayers.%s -> %s", babyField, key);
        return baby;
    }

    // ------------------------------------------------------------------------------------
    // baby mesh pick
    // ------------------------------------------------------------------------------------

    private @Nullable String pickBabyLayerField() {
        for (EntityGeometryRefResolver.ModelConsumer consumer : this.geometryRef.modelConsumers()) {
            if (!selectsOnIsBaby(consumer.owner())) continue;
            return consumer.tripleFields().getLast();
        }
        // Declared naming fallback: the first _BABY-suffixed triple in the ctor chain.
        for (String field : this.geometryRef.tripleSites())
            if (field.endsWith("_BABY") && this.layerDefinitions.get(field) != null) {
                this.diagnostics.info("baby layer ModelLayers.%s via field-suffix fallback (isBaby dataflow missed)", field);
                return field;
            }
        // Registration-lambda fallback: a renderer handed already-BUILT models bakes nothing in its
        // own constructor, so it has no triple for either arm above to read - EntityRenderers bakes
        // SQUID and SQUID_BABY in the registration lambda and passes SquidRenderer two SquidModels.
        // The adult already resolves from this same pool; the baby just never consulted it. Guarded
        // against the adult's own field so a family naming one layer cannot re-pick it as its baby.
        for (String field : this.subject.lambdaLayerFields())
            if (field.endsWith("_BABY")
                && !field.equals(this.geometryRef.primaryFieldName())
                && this.layerDefinitions.get(field) != null) {
                this.diagnostics.info("baby layer ModelLayers.%s via registration-lambda reference order", field);
                return field;
            }
        return null;
    }

    /**
     * Whether a multi-model consumer selects its model on the render state's
     * {@code isBaby} flag: the consumer chain reads the flag itself
     * ({@code AgeableMobRenderer.submit}; zombie's {@code AbstractZombieRenderer} texture
     * branch), or the renderer chain reads it and dispatches a boolean-selecting call on the
     * consumer within the scan window ({@code pair.getModel(state.isBaby)}).
     */
    private boolean selectsOnIsBaby(@NotNull String consumerOwner) {
        String current = consumerOwner;
        while (current != null && !AsmKit.OBJECT_INTERNAL.equals(current)) {
            ClassNode cn = this.cache.load(current);
            if (cn == null) break;
            for (MethodNode method : cn.methods)
                if (readsIsBaby(method, null)) return true;
            current = cn.superName;
        }
        current = this.subject.rendererClass();
        while (current != null && !AsmKit.OBJECT_INTERNAL.equals(current)) {
            ClassNode cn = this.cache.load(current);
            if (cn == null) break;
            for (MethodNode method : cn.methods)
                if (readsIsBaby(method, consumerOwner)) return true;
            current = cn.superName;
        }
        return false;
    }

    /**
     * Whether the method reads {@code isBaby:Z}; with a non-null {@code dispatchOwner}, the
     * read must additionally feed an {@code INVOKEVIRTUAL <dispatchOwner>.<m>(Z...)} within
     * the scan window.
     */
    private static boolean readsIsBaby(@NotNull MethodNode method, @Nullable String dispatchOwner) {
        return AsmWalker.over(method).any(in -> {
            if (in.getOpcode() != Opcodes.GETFIELD
                || !(in instanceof FieldInsnNode fi)
                || !VanillaSourceClasses.Fields.IS_BABY.equals(fi.name)
                || !"Z".equals(fi.desc)) return false;
            if (dispatchOwner == null) return true;
            return AsmWalker.after(in).real().limit(DISPATCH_WINDOW).any(cursor ->
                cursor instanceof MethodInsnNode mi
                    && cursor.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && dispatchOwner.equals(mi.owner)
                    && mi.desc.startsWith("(Z"));
        });
    }

    // ------------------------------------------------------------------------------------
    // baby texture (plain families)
    // ------------------------------------------------------------------------------------

    private @Nullable String resolveBabyTexture(@Nullable String adultTexture) {
        String branchLiteral = isBabyBranchTexture();
        if (branchLiteral != null) {
            this.diagnostics.info("baby texture via isBaby-branch literal");
            return VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + branchLiteral;
        }
        if (adultTexture == null) return null;
        // <adult>_baby sibling, existence-probed as the declared naming fallback.
        String prefixed = adultTexture.substring(VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE.length());
        String candidate = prefixed.substring(0, prefixed.length() - ".png".length()) + "_baby.png";
        if (!this.cache.hasEntry(VanillaSourceClasses.Paths.ASSETS_ROOT + candidate)) return null;
        this.diagnostics.info("baby texture via _baby sibling probe");
        return VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + candidate;
    }

    /**
     * The texture literal on the {@code isBaby}-true arm of the renderer chain's own
     * {@code getTextureLocation}: {@code GETFIELD isBaby; IFEQ <adult>} falls through into
     * the baby arm - its first {@code GETSTATIC :LIdentifier;} (bounded by the adult
     * label) resolves through the declaring class's {@code <clinit>} literal map.
     */
    private @Nullable String isBabyBranchTexture() {
        String current = this.subject.rendererClass();
        while (current != null && !AsmKit.OBJECT_INTERNAL.equals(current)) {
            ClassNode cn = this.cache.load(current);
            if (cn == null) return null;
            for (MethodNode method : cn.methods) {
                if (!VanillaSourceClasses.Methods.GET_TEXTURE_LOCATION.equals(method.name)) continue;
                String field = isBabyTrueArmIdentifierField(method);
                if (field == null) continue;
                String path = clinitTexturePath(cn, field);
                if (path != null) return path;
            }
            current = cn.superName;
        }
        return null;
    }

    /**
     * The first Identifier {@code GETSTATIC} on the {@code isBaby}-true arm reached along the
     * DEFAULT state path, or {@code null}. Renderers that gate the texture on more than one flag
     * ({@code StriderRenderer}: {@code isSuffocating} outside {@code isBaby}) expose several
     * {@code GETFIELD isBaby; IFEQ} sites; the baby texture must match the same state branch the
     * adult resolves to (all other flags false - {@code EntityTextureResolver.findPrimaryByDefaultPath}),
     * so a plain first-site pick returns the wrong sibling (cold baby vs the warm-default baby).
     * This traces each {@code GETFIELD <flag>; IFEQ} taking the false arm for non-{@code isBaby}
     * flags and falling through into the {@code isBaby}-true arm at the isBaby gate.
     */
    private static @Nullable String isBabyTrueArmIdentifierField(@NotNull MethodNode method) {
        java.util.Set<AbstractInsnNode> visited = new java.util.HashSet<>();
        AbstractInsnNode in = method.instructions.getFirst();
        while (in != null && visited.add(in)) {
            if (in.getOpcode() == Opcodes.GETFIELD
                && in instanceof FieldInsnNode fi
                && "Z".equals(fi.desc)
                && AsmKit.nextReal(in) instanceof JumpInsnNode jump
                && jump.getOpcode() == Opcodes.IFEQ) {
                if (VanillaSourceClasses.Fields.IS_BABY.equals(fi.name)) {
                    for (AbstractInsnNode arm = jump.getNext(); arm != null && arm != jump.label; arm = arm.getNext())
                        if (arm.getOpcode() == Opcodes.GETSTATIC
                            && arm instanceof FieldInsnNode texture
                            && VanillaSourceClasses.Descs.IDENTIFIER_REF.equals(texture.desc))
                            return texture.name;
                    return null;
                }
                in = jump.label;                      // a non-isBaby flag: take its false arm
                continue;
            }
            if (in.getOpcode() == Opcodes.GOTO && in instanceof JumpInsnNode goTo) {
                in = goTo.label;
                continue;
            }
            in = in.getNext();
        }
        return null;
    }

    /**
     * The {@code textures/entity/} literal bound to a static Identifier field in the class's
     * {@code <clinit>} (the canonical {@code LDC; withDefaultNamespace; PUTSTATIC} triplet).
     */
    private @Nullable String clinitTexturePath(@NotNull ClassNode cn, @NotNull String fieldName) {
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return null;
        String pendingPath = null;
        for (AbstractInsnNode in : clinit.instructions) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null && literal.startsWith(VanillaSourceClasses.Paths.TEXTURES_ENTITY)) {
                pendingPath = literal;
                continue;
            }
            if (AsmKit.isPutStatic(in, cn.name, fieldName)) return pendingPath;
        }
        return null;
    }

}
