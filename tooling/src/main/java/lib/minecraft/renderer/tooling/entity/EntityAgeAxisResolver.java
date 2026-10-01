package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.animation.PoseFlow;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.asm.Insn;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.index.LayerDefinitionIndex;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.CommitWalk;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * <p>A baby option carries the {@code toggles} its own model class gates, as a size option does,
 * expanded against the baby's own mesh: vanilla gates a baby goat's horns and a baby bee's sting in
 * the same {@code setupAnim} that gates an adult's. The class is the one that bakes the baby mesh,
 * which is also the class the baby's pose is read off. A gate is left off where the renderer stores a
 * literal into its flag on the baby arm of an age test, because no selection can then move the bones
 * it reaches - {@code LlamaRenderer} stores {@code false} into {@code hasChest} for every baby, and
 * that is the one such gate a baby's class declares in 26.1.
 *
 * <p>Baby texture chain: variant families carry per-option {@code baby_texture} instead (node emits
 * geometry only); plain families take the renderer's isBaby-branch texture literal, then the
 * {@code <adult>_baby} sibling existence-probed as a declared fallback.
 */
public final class EntityAgeAxisResolver {

    /**
     * Forward-scan window from an {@code isBaby} read to its consuming boolean-dispatch call.
     */
    private static final int DISPATCH_WINDOW = 8;

    /** The descriptor of {@code LivingEntity.getAgeScale}. */
    private static final @NotNull String AGE_SCALE_DESC = "()F";

    private final @NotNull ClassNodeCache cache;
    private final @NotNull EntitySubject subject;
    private final @NotNull LayerDefinitionIndex layerDefinitions;
    private final @NotNull EntityGeometryRefResolver geometryRef;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull EntityBoneResolver bones;
    private final @NotNull Diagnostics diagnostics;

    EntityAgeAxisResolver(@NotNull EntityContext context, @NotNull EntityGeometryRefResolver geometryRef) {
        this.cache = context.cache();
        this.subject = context.subject();
        this.layerDefinitions = context.indexes().layerDefinitions();
        this.geometryRef = geometryRef;
        this.manifest = context.indexes().manifest();
        this.bones = new EntityBoneResolver(context.scope("bones"));
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
        // No `default` member: an age axis names its adult option first and the reader takes it,
        // where an axis whose default is a choice among several says so.
        JsonTree node = JsonTree.object();
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

        GeometryRequest request = GeometryRequest.body(
            babyEntry.factoryClass(), babyEntry.factoryMethod(), this.subject.entityId(),
            babyEntry.texWidthOverride(), babyEntry.texHeightOverride(),
            babyEntry.floatParam(), babyEntry.appliedMeshTransformerScale());
        String key = this.manifest.register(request);
        // A baby that mints the adult's own key IS the adult mesh, and an option naming it would be a
        // byte-identical copy of the default. Registering first and comparing keys is what asks that
        // exactly: the manifest dedupes by key, so a baby that collides adds no entry to collide with.
        if (key.equals(baseGeometry)) {
            this.diagnostics.info("baby layer ModelLayers.%s mints the adult mesh key - baby option skipped", babyField);
            return null;
        }

        JsonTree baby = JsonTree.object().put("geometry", key);
        if (!variantFamily) baby.putIf("texture", resolveBabyTexture(adultTexture));
        // The toggles alone, as a size option carries them: the pose flow writes what a baby rests
        // without off its own class's pose, and the class is the one the coordinate names, so the node
        // names no poser.
        JsonTree gated = this.bones.resolve(babyEntry.factoryClass(), request, pinnedOnBaby());
        if (gated != null) gated.findObject("toggles").ifPresent(toggles -> baby.put("toggles", toggles));
        // The age the baby's own entity answers, for the pose flow to fold its age-scaled terms at.
        // Generation-only: the pose flow reads it and the rest strip takes it off again.
        Optional<Float> ageScale = ageScaleOnBaby();
        if (ageScale.isPresent()) baby.put(PoseFlow.AGE_SCALE, ageScale.get().floatValue());
        else this.diagnostics.info("age axis: %s answers no readable getAgeScale - baby option carries no %s",
            this.subject.entityClass(), PoseFlow.AGE_SCALE);
        this.diagnostics.info("age axis: baby mesh ModelLayers.%s -> %s", babyField, key);
        return baby;
    }

    // ------------------------------------------------------------------------------------
    // baby age scale
    // ------------------------------------------------------------------------------------

    /**
     * The scale the subject's entity answers from {@code getAgeScale} as a baby - the value vanilla's
     * {@code LivingEntityRenderer.extractRenderState} writes into the render state's {@code ageScale},
     * where the render state's own constructor builds it at one.
     *
     * <p>Read off the nearest {@code getAgeScale()F} up the entity's superclass chain:
     * {@code LivingEntity} answers {@code 0.5} and a goat, a camel or a turtle overrides it with
     * another literal of the same shape.
     *
     * @return the baby's age scale, or empty where the nearest declaration has another shape
     */
    @NotNull Optional<Float> ageScaleOnBaby() {
        ClassNode declaring = ClassKit.walkSuperChainUntil(this.cache, this.subject.entityClass(),
            node -> ClassKit.findMethod(node, SourceClasses.Methods.GET_AGE_SCALE, AGE_SCALE_DESC) != null);
        if (declaring == null) return Optional.empty();
        MethodNode method = ClassKit.findMethod(declaring, SourceClasses.Methods.GET_AGE_SCALE, AGE_SCALE_DESC);
        return method == null ? Optional.empty() : babyAgeScale(method);
    }

    /**
     * The float literal one method returns on the baby arm of an age test.
     *
     * <p>The age test is the one {@link #babyPinnedFlags} reads - an {@code isBaby} read straight into
     * an {@code IFNE} or an {@code IFEQ}, whose baby arm starts at the jump's target and at the
     * fall-through respectively. The arm answers when its first instruction is a float literal and
     * the next - through at most one {@code GOTO}, the shape {@code javac} gives a select - is the
     * {@code FRETURN}. Two arms answering two literals answer nothing, and so does any other shape.
     *
     * @param method the method to read
     * @return the literal the baby arm returns, or empty where no single one does
     */
    static @NotNull Optional<Float> babyAgeScale(@NotNull MethodNode method) {
        Set<Float> answers = new LinkedHashSet<>();
        AsmWalker.over(method)
            .where(EntityAgeAxisResolver::isAgeRead)
            .forEach(read -> {
                if (!(AsmWalker.nextReal(read) instanceof JumpInsnNode test)) return;
                AbstractInsnNode arm = switch (test.getOpcode()) {
                    case Opcodes.IFNE -> AsmWalker.nextReal(test.label);
                    case Opcodes.IFEQ -> AsmWalker.nextReal(test);
                    default -> null;
                };
                Float literal = AsmWalker.floatLiteral(arm);
                if (literal == null) return;
                AbstractInsnNode exit = AsmWalker.nextReal(arm);
                if (exit instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO)
                    exit = AsmWalker.nextReal(jump.label);
                if (exit != null && exit.getOpcode() == Opcodes.FRETURN) answers.add(literal);
            });
        return answers.size() == 1 ? Optional.of(answers.iterator().next()) : Optional.empty();
    }

    // ------------------------------------------------------------------------------------
    // baby pins
    // ------------------------------------------------------------------------------------

    /**
     * The render-state flags the renderer chain's {@code extractRenderState} stores a literal into on
     * the baby arm of an age test, which every baby therefore holds whatever its entity does -
     * {@code LlamaRenderer} writes {@code hasChest} as {@code !isBaby() && hasChest()}.
     *
     * <p>Only a {@code false} pin is an answer. The fold rests a flag at what the render state's
     * constructor builds it at, so a flag every baby holds {@code true} would rest hidden where every
     * baby draws it, and the marking would drop a bone vanilla always draws.
     *
     * @return the pinned flag names, in the order the chain reads them
     * @throws ToolingException if the chain pins a flag {@code true} on a baby
     */
    @NotNull Set<String> pinnedOnBaby() {
        Set<String> pinned = new LinkedHashSet<>();
        ClassKit.walkSuperChain(this.cache, this.subject.rendererClass(), renderer -> {
            for (MethodNode method : renderer.methods) {
                if (!SourceClasses.Methods.EXTRACT_RENDER_STATE.equals(method.name)) continue;
                babyPinnedFlags(method).forEach((flag, value) -> {
                    if (value)
                        throw new ToolingException(
                            "Renderer '%s' pins '%s' true on every baby, which the rest the fold reads off its"
                                + " render state's constructor cannot say",
                            renderer.name, flag
                        );
                    pinned.add(flag);
                });
            }
        });
        return pinned;
    }

    /**
     * The flags one method stores a boolean literal into on the baby arm of an age test, each to that
     * literal.
     *
     * <p>An age test is the entity's {@code isBaby()} or a render state's {@code isBaby} read straight
     * into an {@code IFNE} or an {@code IFEQ}, whose baby arm starts at the jump's target and at the
     * fall-through respectively. The arm pins a flag when its first instruction is {@code ICONST_0} or
     * {@code ICONST_1} and the next - through at most one {@code GOTO}, the shape {@code javac} gives a
     * select - is a {@code PUTFIELD} of a {@code :Z} field. Nothing else is a pin: a float the arm
     * selects ({@code PandaRenderer}'s {@code rollAmount}), a test of something other than the age
     * ({@code TurtleRenderer}'s {@code isOnLand}) and a flag copied without a test
     * ({@code DonkeyRenderer}'s {@code hasChest}).
     *
     * @param method the method to read
     * @return each pinned flag to the literal its baby arm stores, {@code true} where any arm stores it
     */
    static @NotNull Map<String, Boolean> babyPinnedFlags(@NotNull MethodNode method) {
        Map<String, Boolean> pinned = new LinkedHashMap<>();
        AsmWalker.over(method)
            .where(EntityAgeAxisResolver::isAgeRead)
            .forEach(read -> {
                if (!(AsmWalker.nextReal(read) instanceof JumpInsnNode test)) return;
                AbstractInsnNode arm = switch (test.getOpcode()) {
                    case Opcodes.IFNE -> AsmWalker.nextReal(test.label);
                    case Opcodes.IFEQ -> AsmWalker.nextReal(test);
                    default -> null;
                };
                Boolean literal = AsmWalker.booleanLiteral(arm);
                if (literal == null) return;
                AbstractInsnNode store = AsmWalker.nextReal(arm);
                if (store instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO)
                    store = AsmWalker.nextReal(jump.label);
                if (store instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD && "Z".equals(put.desc))
                    pinned.merge(put.name, literal, Boolean::logicalOr);
            });
        return pinned;
    }

    /** Whether an instruction reads the age - the entity's {@code isBaby()} or a render state's {@code isBaby}. */
    private static boolean isAgeRead(@NotNull AbstractInsnNode in) {
        if (in.getOpcode() == Opcodes.INVOKEVIRTUAL && in instanceof MethodInsnNode call)
            return SourceClasses.Methods.IS_BABY.equals(call.name) && "()Z".equals(call.desc);
        return in.getOpcode() == Opcodes.GETFIELD
            && in instanceof FieldInsnNode field
            && SourceClasses.Fields.IS_BABY.equals(field.name)
            && "Z".equals(field.desc);
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
        while (current != null && !ClassKit.OBJECT_INTERNAL.equals(current)) {
            ClassNode cn = this.cache.load(current);
            if (cn == null) break;
            for (MethodNode method : cn.methods)
                if (readsIsBaby(method, null)) return true;
            current = cn.superName;
        }
        current = this.subject.rendererClass();
        while (current != null && !ClassKit.OBJECT_INTERNAL.equals(current)) {
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
                || !SourceClasses.Fields.IS_BABY.equals(fi.name)
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
            return SourceClasses.Paths.MINECRAFT_NAMESPACE + branchLiteral;
        }
        if (adultTexture == null) return null;
        // <adult>_baby sibling, existence-probed as the declared naming fallback.
        String prefixed = adultTexture.substring(SourceClasses.Paths.MINECRAFT_NAMESPACE.length());
        String candidate = prefixed.substring(0, prefixed.length() - ".png".length()) + "_baby.png";
        if (!this.cache.hasEntry(SourceClasses.Paths.ASSETS_ROOT + candidate)) return null;
        this.diagnostics.info("baby texture via _baby sibling probe");
        return SourceClasses.Paths.MINECRAFT_NAMESPACE + candidate;
    }

    /**
     * The texture literal on the {@code isBaby}-true arm of the renderer chain's own
     * {@code getTextureLocation}: {@code GETFIELD isBaby; IFEQ <adult>} falls through into
     * the baby arm - its first {@code GETSTATIC :LIdentifier;} (bounded by the adult
     * label) resolves through the declaring class's {@code <clinit>} literal map.
     */
    private @Nullable String isBabyBranchTexture() {
        String current = this.subject.rendererClass();
        while (current != null && !ClassKit.OBJECT_INTERNAL.equals(current)) {
            ClassNode cn = this.cache.load(current);
            if (cn == null) return null;
            for (MethodNode method : cn.methods) {
                if (!SourceClasses.Methods.GET_TEXTURE_LOCATION.equals(method.name)) continue;
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
        // The probe claims the isBaby gate before the advance hook sees the node, so the advance's
        // flag arm only ever fires on the other flags; a revisited node ends the trace as a miss.
        JumpInsnNode gate = AsmWalker.over(method).traceFirst(
            in -> in.getOpcode() == Opcodes.GETFIELD
                && in instanceof FieldInsnNode fi
                && "Z".equals(fi.desc)
                && SourceClasses.Fields.IS_BABY.equals(fi.name)
                && AsmWalker.nextReal(in) instanceof JumpInsnNode jump
                && jump.getOpcode() == Opcodes.IFEQ ? jump : null,
            in -> {
                if (in.getOpcode() == Opcodes.GETFIELD
                    && in instanceof FieldInsnNode fi
                    && "Z".equals(fi.desc)
                    && AsmWalker.nextReal(in) instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IFEQ) return jump.label; // a non-isBaby flag: take its false arm
                if (in.getOpcode() == Opcodes.GOTO && in instanceof JumpInsnNode goTo) return goTo.label;
                return in.getNext();
            });
        if (gate == null) return null;
        // The true arm, bounded by the gate's own false-arm label; a miss here is the member's
        // answer - the trace does not resume.
        FieldInsnNode texture = AsmWalker.from(gate.getNext())
            .until(gate.label)
            .first(Insn.of(FieldInsnNode.class, fi -> fi.getOpcode() == Opcodes.GETSTATIC
                && SourceClasses.Descs.IDENTIFIER_REF.equals(fi.desc)));
        return texture == null ? null : texture.name;
    }

    /**
     * The {@code textures/entity/} literal bound to a static Identifier field in the class's
     * {@code <clinit>} (the canonical {@code LDC; withDefaultNamespace; PUTSTATIC} triplet).
     */
    private @Nullable String clinitTexturePath(@NotNull ClassNode cn, @NotNull String fieldName) {
        MethodNode clinit = ClassKit.findMethod(cn, ClassKit.CLINIT);
        if (clinit == null) return null;
        CommitWalk.Commit<FieldInsnNode, String> commit = AsmWalker.over(clinit)
            .latch(in -> {
                String literal = AsmWalker.stringLiteral(in);
                return literal != null && literal.startsWith(SourceClasses.Paths.TEXTURES_ENTITY) ? literal : null;
            })
            .commitAt(Insn.putStatic(cn.name, fieldName))
            .first();
        return commit == null ? null : commit.value();
    }

}
