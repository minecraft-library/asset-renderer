package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A split is tint-bearing when its renderer's hierarchy calls a dye/banner tint accessor AND the
 * split's mesh factory is the dye-taking mesh - the flag sub-model takes the dye, the wood-brown
 * pole / bar does not. That mesh is recovered once per session at the coordinate
 * {@link BlockFamilyPolicies#BANNER_DYE_TARGET} declares; the per-renderer tint-bearing verdict is
 * memoised.
 *
 * <p>No allow-list - the only semantic judgment is "renderer calls a DyeColor / BannerPattern
 * API"; everything downstream is structural.
 */
final class BlockTintFlagResolver {

    /** The caller label a stale dye-target coordinate is reported under. */
    private static final @NotNull String DYE_TARGET = "the banner dye-target mesh";

    private final @NotNull ClassNodeCache cache;

    /** The mesh class the dye-routed submit receives. */
    private final @NotNull String dyeTargetModel;

    /**
     * renderer internal name -> whether its hierarchy calls a tint accessor.
     */
    private final @NotNull Map<String, Boolean> tintBearing = new HashMap<>();

    BlockTintFlagResolver(@NotNull ToolingSession session) {
        this.cache = session.cache();
        this.dyeTargetModel = resolveDyeTargetModel(session);
    }

    /**
     * Whether a split carries the {@code tinted} flag: a tint-bearing renderer plus the dye-taking
     * mesh factory.
     *
     * @param rendererClass the renderer internal name
     * @param factoryClass the split's mesh factory class internal name
     * @return {@code true} when the split renders tinted
     */
    boolean isTinted(@NotNull String rendererClass, @NotNull String factoryClass) {
        return isFlagModel(factoryClass) && isRendererTintBearing(rendererClass);
    }

    /**
     * The dye-taking sub-model (banner / wall-banner flag).
     */
    private boolean isFlagModel(@NotNull String factoryClass) {
        return factoryClass.equals(this.dyeTargetModel);
    }

    private boolean isRendererTintBearing(@NotNull String rendererClass) {
        return this.tintBearing.computeIfAbsent(rendererClass,
            renderer -> ClassKit.walkSuperChainUntil(this.cache, renderer, BlockTintFlagResolver::classCallsTintAccessor) != null);
    }

    /**
     * Whether any method on a class invokes {@code DyeColor.getTextureDiffuseColor(s)},
     * {@code BannerPattern.getColor}, or returns {@code BannerPatternLayers}.
     */
    private static boolean classCallsTintAccessor(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods)
            if (AsmWalker.over(method).any(in ->
                AsmWalker.isInvokeVirtual(in, VanillaSourceClasses.Types.DYE_COLOR, VanillaSourceClasses.Methods.GET_TEXTURE_DIFFUSE_COLOR)
                    || AsmWalker.isInvokeStatic(in, VanillaSourceClasses.Types.DYE_COLOR, VanillaSourceClasses.Methods.GET_TEXTURE_DIFFUSE_COLORS)
                    || AsmWalker.isInvokeVirtual(in, VanillaSourceClasses.Types.BANNER_PATTERN, VanillaSourceClasses.Methods.GET_COLOR)
                    || (in instanceof MethodInsnNode mi && ClassKit.descriptorReturns(mi.desc, VanillaSourceClasses.Types.BANNER_PATTERN_LAYERS))))
                return true;
        return false;
    }

    /**
     * Resolves the dye-taking mesh at the coordinate the family policy declares. The declared
     * method hands a model to several submits; the fold gathers each submit's model-typed argument
     * loads and commits at the submit itself, and the first commit whose callee reaches
     * {@code DyeColor.getTextureDiffuseColor} names the mesh.
     *
     * <p>Consulted on a KEYLESS frame: the mesh is resolved once at construction, before the walk
     * reaches a subject, so there is no roster id to key on and none is invented.
     *
     * @param session the live session
     * @return the dye-taking mesh class's JVM internal name
     * @throws ToolingException if the coordinate routes the dye through no submitted model
     */
    private static @NotNull String resolveDyeTargetModel(@NotNull ToolingSession session) {
        ClassNodeCache cache = session.cache();
        Navigation.At coordinate = BlockFamilyPolicies.BANNER_DYE_TARGET.requireAt(
            AsmContext.keyless(session, session.diagnostics()));
        ClassNode owner = ClassKit.requireClass(cache, coordinate.owner(), DYE_TARGET);
        MethodNode submit = ClassKit.requireMethod(owner, coordinate.member(), DYE_TARGET);
        String model = AsmWalker.over(submit)
            .gather(node -> modelArgumentOf(cache, submit, node))
            .commitAt(Insn.of(MethodInsnNode.class, call -> takesModel(cache, call.desc)))
            .firstNotNull(commit -> routesDye(owner, commit.node(), new HashSet<>()) ? commit.value() : null);
        if (model == null)
            throw new ToolingException(
                "Method '%s.%s' routes the dye through no submitted model for %s - the jar is either obfuscated or from an unsupported version",
                owner.name, submit.name, DYE_TARGET
            );
        return model;
    }

    /**
     * The declared type of the model-typed parameter an {@code ALOAD} names, or {@code null} for
     * any other instruction.
     */
    private static @Nullable String modelArgumentOf(
        @NotNull ClassNodeCache cache,
        @NotNull MethodNode method,
        @NotNull AbstractInsnNode node
    ) {
        if (node.getOpcode() != Opcodes.ALOAD || !(node instanceof VarInsnNode load)) return null;
        String declared = parameterType(method, load.var);
        return declared != null && isModel(cache, declared) ? declared : null;
    }

    /**
     * The JVM internal name a method's parameter at the given local slot declares, or {@code null}
     * when the slot names no reference parameter.
     */
    private static @Nullable String parameterType(@NotNull MethodNode method, int slot) {
        int current = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : ClassKit.argTypes(method.desc)) {
            if (current == slot) return argument.getSort() == Type.OBJECT ? argument.getInternalName() : null;
            current += argument.getSize();
        }
        return null;
    }

    /** Whether a descriptor declares a model parameter - the submit shape the fold commits at. */
    private static boolean takesModel(@NotNull ClassNodeCache cache, @NotNull String descriptor) {
        for (Type argument : ClassKit.argTypes(descriptor))
            if (argument.getSort() == Type.OBJECT && isModel(cache, argument.getInternalName())) return true;
        return false;
    }

    /** Whether a class is the vanilla model base or one of its subclasses. */
    private static boolean isModel(@NotNull ClassNodeCache cache, @NotNull String internalName) {
        return ClassKit.extendsClass(cache, internalName, VanillaSourceClasses.Types.MODEL);
    }

    /**
     * Whether a call reaches the dye read - the callee, or anything the callee calls on the same
     * class, invokes {@code DyeColor.getTextureDiffuseColor}. A call off the class is not followed,
     * and the visiting set bounds the reach.
     */
    private static boolean routesDye(@NotNull ClassNode owner, @NotNull MethodInsnNode call, @NotNull Set<String> visiting) {
        if (!owner.name.equals(call.owner) || !visiting.add(call.name + call.desc)) return false;
        MethodNode callee = ClassKit.findMethod(owner, call.name, call.desc);
        if (callee == null) return false;
        return AsmWalker.over(callee).any(in -> AsmWalker.isInvokeVirtual(
                in, VanillaSourceClasses.Types.DYE_COLOR, VanillaSourceClasses.Methods.GET_TEXTURE_DIFFUSE_COLOR)
            || in instanceof MethodInsnNode inner && routesDye(owner, inner, visiting));
    }

}
