package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.json.JsonNode;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
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
 * <p>Same-model-class factories are skipped - NautilusModel bakes adult and baby from the
 * same class and the collision suffix would shift the adult's id. Baby texture chain: variant
 * families carry per-option {@code baby_texture} instead (node emits geometry only); plain
 * families take the renderer's isBaby-branch texture literal, then the {@code <adult>_baby}
 * sibling existence-probed as a declared fallback.
 */
final class EntityAgeAxisResolver {

    /** Forward-scan window from an {@code isBaby} read to its consuming boolean-dispatch call. */
    private static final int DISPATCH_WINDOW = 8;

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull LayerDefinitionIndex layerDefinitions;
    private final @NotNull EntityGeometryRefResolver geometryRef;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull Diagnostics diagnostics;

    EntityAgeAxisResolver(
        @NotNull ClassNodeCache cache,
        @NotNull EntitySubject subject,
        @NotNull LayerDefinitionIndex layerDefinitions,
        @NotNull EntityGeometryRefResolver geometryRef,
        @NotNull GeometryManifest manifest,
        @NotNull Diagnostics diagnostics
    ) {
        this.cache = cache;
        this.subject = subject;
        this.layerDefinitions = layerDefinitions;
        this.geometryRef = geometryRef;
        this.manifest = manifest;
        this.diagnostics = diagnostics;
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
     * @return the age node (always non-null)
     */
    @NotNull JsonNode resolve(@Nullable String baseGeometry, @Nullable String adultTexture, boolean variantFamily) {
        JsonNode adult = JsonNode.object().putIf("geometry", baseGeometry);
        if (!variantFamily) adult.putIf("texture", adultTexture);
        JsonNode node = JsonNode.object().put("default", "adult");
        JsonNode options = node.child("options");
        options.put("adult", adult);
        JsonNode baby = resolveBaby(adultTexture, variantFamily);
        if (baby != null) options.put("baby", baby);
        return node;
    }

    /**
     * The {@code options.baby} delta body, or {@code null} when no dedicated baby mesh resolves
     * (the baby field is unindexed, or the baby bakes from the adult model class).
     */
    private @Nullable JsonNode resolveBaby(@Nullable String adultTexture, boolean variantFamily) {
        String babyField = pickBabyLayerField();
        if (babyField == null) return null;
        LayerDefinitionIndex.Entry babyEntry = this.layerDefinitions.get(babyField);
        if (babyEntry == null) {
            this.diagnostics.info("baby layer ModelLayers.%s has no LayerDefinitions.createRoots entry - baby option omitted", babyField);
            return null;
        }
        LayerDefinitionIndex.Entry primary = this.geometryRef.resolvedEntry();
        // Skip babies baked from the SAME model class as the adult (nautilus:
        // NautilusModel#createBabyBodyLayer vs #createBodyLayer) - their geometry ids derive
        // the same class-based stem and the collision suffix would shift the adult's.
        if (primary != null && primary.factoryClass().equals(babyEntry.factoryClass())) {
            this.diagnostics.info("baby layer ModelLayers.%s shares the adult model class - baby option skipped [D10]", babyField);
            return null;
        }

        String key = this.manifest.register(GeometryRequest.body(
            babyEntry.factoryClass(), babyEntry.factoryMethod(), this.subject.entityId(),
            babyEntry.texWidthOverride(), babyEntry.texHeightOverride(),
            babyEntry.floatParam(), babyEntry.appliedMeshTransformerScale()));

        JsonNode baby = JsonNode.object().put("geometry", key);
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
                this.diagnostics.info("baby layer ModelLayers.%s via P10 field-suffix fallback (isBaby dataflow missed)", field);
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
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.GETFIELD
                || !(in instanceof FieldInsnNode fi)
                || !VanillaSourceClasses.Fields.IS_BABY.equals(fi.name)
                || !"Z".equals(fi.desc)) continue;
            if (dispatchOwner == null) return true;
            AbstractInsnNode cursor = in;
            for (int step = 0; step < DISPATCH_WINDOW && cursor != null; step++) {
                cursor = AsmKit.nextReal(cursor);
                if (cursor instanceof MethodInsnNode mi
                    && cursor.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && dispatchOwner.equals(mi.owner)
                    && mi.desc.startsWith("(Z"))
                    return true;
            }
        }
        return false;
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
        this.diagnostics.info("baby texture via _baby sibling probe [D26/P10]");
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
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
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
