package lib.minecraft.renderer.tooling.blockentity;

import dev.simplified.util.StringUtil;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.asm.Insn;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.tooling.geometry.FixedRootPose;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reads the root pose a block entity's model writes on every draw, where its {@code setupAnim} takes no
 * input.
 * <p>
 * A model's {@code setupAnim} is an animation where it reads a render state, and the static render is
 * drawn without it. One declared over {@code net.minecraft.util.Unit} reads nothing, so every draw runs
 * the same body: what it writes is a fixed pose, part of the mesh rather than an animation of it. In the
 * 26.1 client the copper golem statue's model is the one whose body writes anything - its root's
 * {@code y} and {@code zRot} - and {@code Model$Simple}'s is empty. Nothing here names either: the rule
 * is the input type, read off the model the renderer builds over the baked layer.
 * <p>
 * The body is read as vanilla's statue writes it - {@code this.root}, a float literal, a write to one of
 * the root's offset or rotation members, repeated, then {@code return}. Anything else in an input-free
 * body stops the flow rather than being guessed at, since a pose the walk cannot read would ship the
 * mesh in a pose vanilla never draws.
 */
final class NoInputSetupAnim {

    private NoInputSetupAnim() {
    }

    /**
     * Reads the fixed root pose of the model a renderer builds over one of its baked layers.
     *
     * @param cache the session's jar cache
     * @param rendererClass the renderer's internal name, whose super chain is searched too
     * @param layerField the {@code ModelLayers} field naming the baked layer
     * @return the pose, or {@code null} where the renderer builds no model over the layer, the model's
     *     {@code setupAnim} takes an input, or its input-free body writes nothing
     * @throws ToolingException when the renderer builds more than one model over the layer, or an
     *     input-free body does anything but write constants to its root
     */
    static @Nullable FixedRootPose read(@NotNull ClassNodeCache cache, @NotNull String rendererClass, @NotNull String layerField) {
        Set<String> models = new LinkedHashSet<>();
        ClassKit.walkSuperChain(cache, rendererClass, renderer -> {
            for (MethodNode method : renderer.methods)
                models.addAll(AsmWalker.over(method)
                    .getStatic(SourceClasses.Types.MODEL_LAYERS, layerField)
                    .mapNotNull(NoInputSetupAnim::modelBuiltOver)
                    .toSet());
        });
        if (models.isEmpty()) return null;
        if (models.size() > 1)
            throw new ToolingException("'%s' builds %d models over '%s' - %s - so no one setupAnim poses its mesh",
                ClassKit.simpleName(rendererClass), models.size(), layerField, models);

        String model = models.iterator().next();
        MethodNode setupAnim = ClassKit.findMethodInHierarchy(cache, model, SourceClasses.Methods.SETUP_ANIM,
            "(L" + SourceClasses.Types.UNIT + ";)V");
        if (setupAnim == null) return null;

        RootWrites fold = new RootWrites(cache, model);
        AsmWalker.over(setupAnim)
            .real()
            .on(Insn.of(AbstractInsnNode.class, insn -> true), fold::step)
            .run();
        if (!fold.returned)
            throw new ToolingException("'%s.setupAnim' takes no input and does not end on a return", ClassKit.simpleName(model));
        return fold.writes.isEmpty() ? null : new FixedRootPose(model, fold.writes);
    }

    /**
     * Answers the model a baked layer is handed to: the {@code ModelLayers} read followed by the
     * {@code bakeLayer} call baking it and the constructor taking the baked part first.
     *
     * @param layer the {@code ModelLayers} read
     * @return the model's internal name, or {@code null} where the layer is not baked into a model there
     */
    private static @Nullable String modelBuiltOver(@NotNull AbstractInsnNode layer) {
        if (!(AsmWalker.nextReal(layer) instanceof MethodInsnNode bake) || !bake.name.equals(SourceClasses.Methods.BAKE_LAYER))
            return null;
        if (!(AsmWalker.nextReal(bake) instanceof MethodInsnNode init)
            || init.getOpcode() != Opcodes.INVOKESPECIAL
            || !init.name.equals("<init>")
            || !init.desc.startsWith("(L" + SourceClasses.Types.MODEL_PART + ";"))
            return null;
        return init.owner;
    }

    /**
     * The fold {@link #read} runs over an input-free {@code setupAnim} body: each
     * {@code this.root; <float>; putfield ModelPart.<member>} triple taken as a write, and the
     * {@code return} closing it.
     */
    private static final class RootWrites {

        /** The session's jar cache, through which the root field's declaring class is resolved. */
        private final @NotNull ClassNodeCache cache;

        /** The model whose body is read. */
        private final @NotNull String model;

        /** The constant each written channel is set to, so far. */
        private final @NotNull Map<PoseChannel, Float> writes = new EnumMap<>(PoseChannel.class);

        /** How far into the current write the body is: 0 before {@code this}, 1 holding it, 2 holding the root, 3 holding the value. */
        private int stage;

        /** The value the current write sets. */
        private float value;

        /** Whether the body has returned. */
        private boolean returned;

        private RootWrites(@NotNull ClassNodeCache cache, @NotNull String model) {
            this.cache = cache;
            this.model = model;
        }

        /**
         * Folds one instruction of the body.
         *
         * @param insn the instruction
         * @throws ToolingException when the instruction is not the next step of a constant write to the root
         */
        private void step(@NotNull AbstractInsnNode insn) {
            if (this.returned) throw refused(insn);

            switch (this.stage) {
                case 0 -> {
                    if (insn.getOpcode() == Opcodes.RETURN) this.returned = true;
                    else if (insn.getOpcode() == Opcodes.ALOAD && insn instanceof VarInsnNode load && load.var == 0) this.stage = 1;
                    else throw refused(insn);
                }
                case 1 -> {
                    if (!(insn instanceof FieldInsnNode get) || insn.getOpcode() != Opcodes.GETFIELD || !isModelRoot(get)) throw refused(insn);
                    this.stage = 2;
                }
                case 2 -> {
                    Float literal = AsmWalker.floatLiteral(insn);
                    if (literal == null) throw refused(insn);
                    this.value = literal;
                    this.stage = 3;
                }
                default -> {
                    if (!(insn instanceof FieldInsnNode put) || insn.getOpcode() != Opcodes.PUTFIELD
                        || !put.owner.equals(SourceClasses.Types.MODEL_PART))
                        throw refused(insn);
                    PoseChannel channel = PoseChannel.ofToken(StringUtil.toSnakeCase(put.name));
                    if (channel == null)
                        throw new ToolingException("'%s.setupAnim' writes the root's '%s', which is no pose channel",
                            ClassKit.simpleName(this.model), put.name);
                    this.writes.put(channel, this.value);
                    this.stage = 0;
                }
            }
        }

        /**
         * Answers whether a field read is {@code Model.root}, the mesh root the model was built over,
         * whichever class in the model's chain the read names.
         *
         * @param get the field read
         * @return whether the field resolves to {@code Model}'s own root
         */
        private boolean isModelRoot(@NotNull FieldInsnNode get) {
            if (!get.name.equals(SourceClasses.Fields.MODEL_ROOT) || !get.desc.equals("L" + SourceClasses.Types.MODEL_PART + ";"))
                return false;
            String current = get.owner;
            while (current != null) {
                ClassNode owner = this.cache.load(current);
                if (owner == null) return false;
                if (ClassKit.findField(owner, get.name) != null) return current.equals(SourceClasses.Types.MODEL);
                current = owner.superName;
            }
            return false;
        }

        /**
         * Builds the refusal for an instruction outside the shape.
         *
         * @param insn the instruction
         * @return the refusal
         */
        private @NotNull ToolingException refused(@NotNull AbstractInsnNode insn) {
            return new ToolingException("'%s.setupAnim' takes no input but does more than write constants to its root: opcode %d",
                ClassKit.simpleName(this.model), insn.getOpcode());
        }

    }

}
