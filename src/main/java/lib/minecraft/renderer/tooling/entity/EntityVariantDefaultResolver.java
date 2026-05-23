package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.ClassNodeCache;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lib.minecraft.renderer.tooling.util.VanillaSourceClasses;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Locale;

/**
 * Walks a variant-driven renderer's {@code getTextureLocation} body for a
 * {@code GETFIELD state.variant : L<EntityVariant>;} read, then walks the variant enum
 * class's {@code <clinit>} for the canonical {@code public static final DEFAULT}
 * initializer ({@code GETSTATIC <enum_value>; PUTSTATIC DEFAULT}) to recover the zero-state
 * default. Combined with the per-entity texture-naming convention
 * ({@code <entity>/<entity>_<default_lowercase>}), this lets the tooling pick the canonical
 * default texture stem without a hardcoded entry.
 *
 * <p>Two vanilla 26.1 entities use this exact pattern:
 * <ul>
 *   <li>{@code AxolotlRenderer.getTextureLocation} reads
 *       {@code state.variant : LAxolotl$Variant;}; {@code Axolotl$Variant.DEFAULT = LUCY} -&gt;
 *       {@code axolotl/axolotl_lucy}.</li>
 *   <li>{@code RabbitRenderer.getTextureLocation} reads
 *       {@code state.variant : LRabbit$Variant;}; {@code Rabbit$Variant.DEFAULT = BROWN} -&gt;
 *       {@code rabbit/rabbit_brown}.</li>
 * </ul>
 *
 * <p>Data-driven variant entities (cat, frog, wolf, cow, pig, chicken) DON'T match this
 * shape - their state field is a {@code Holder<XVariant>} that resolves through the
 * {@code data/minecraft/X_variant/} registry. Those keep the existing
 * {@link EntityVariantResolver} path.
 */
@UtilityClass
public final class EntityVariantDefaultResolver {

    private static final @NotNull String GET_TEXTURE_LOCATION = "getTextureLocation";
    private static final @NotNull String VARIANT_FIELD = "variant";

    /**
     * Outcome of the resolver: the variant enum's internal name plus the lowercase name of
     * the {@code DEFAULT} enum value. The texture stem is computed by the caller from the
     * entity id (e.g. {@code minecraft:axolotl} + {@code lucy} -&gt; {@code axolotl/axolotl_lucy}).
     */
    public record Result(@NotNull String variantClass, @NotNull String defaultName) {}

    /**
     * Returns the default-variant binding for the renderer's {@code getTextureLocation} when
     * the method reads {@code state.variant} from an enum-typed field AND the variant enum
     * has a {@code public static final DEFAULT} initializer in its {@code <clinit>}. Returns
     * {@code null} for any other shape - data-driven Holder variants, no-DEFAULT enums,
     * non-overridden getTextureLocation, etc.
     */
    public static @Nullable Result resolve(
        @NotNull ClassNodeCache classNodes,
        @NotNull String rendererInternalName,
        @NotNull Diagnostics diag
    ) {
        ClassNode rendererCn = classNodes.load(rendererInternalName);
        if (rendererCn == null) return null;
        MethodNode getTex = findOwnGetTextureLocation(rendererCn);
        if (getTex == null) return null;

        String variantClass = findVariantFieldClass(getTex);
        if (variantClass == null) return null;

        String defaultName = AsmKit.findEnumDefaultName(classNodes, variantClass);
        if (defaultName == null) return null;

        diag.info("variant-default: '%s' -> %s.%s", rendererInternalName, variantClass, defaultName);
        return new Result(variantClass, defaultName.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the renderer's own (non-bridge) {@code getTextureLocation} method. The bridge
     * variant that takes {@code LivingEntityRenderState} delegates back to the typed version
     * via checkcast + invokevirtual - we skip it because the body has no
     * {@code GETFIELD variant} instruction.
     */
    private static @Nullable MethodNode findOwnGetTextureLocation(@NotNull ClassNode cn) {
        String livingState = "(L" + VanillaSourceClasses.LIVING_ENTITY_RENDER_STATE + ";)L" + VanillaSourceClasses.IDENTIFIER + ";";
        String identifierReturn = ")L" + VanillaSourceClasses.IDENTIFIER + ";";
        MethodNode bridge = null;
        for (MethodNode m : cn.methods) {
            if (!GET_TEXTURE_LOCATION.equals(m.name)) continue;
            if (m.desc == null) continue;
            if (livingState.equals(m.desc)) {
                bridge = m;
                continue;
            }
            if (m.desc.endsWith(identifierReturn)) return m;
        }
        return bridge;
    }

    /**
     * Scans the method body for a {@code GETFIELD <state>.variant : L<EnumClass>;}
     * instruction and returns the enum class's JVM internal name. The variant field is
     * named exactly {@code "variant"} in vanilla 26.1 RenderState classes
     * (AxolotlRenderState.variant, RabbitRenderState.variant). Returns {@code null} when
     * no such GETFIELD appears.
     */
    private static @Nullable String findVariantFieldClass(@NotNull MethodNode method) {
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.GETFIELD) continue;
            if (!(in instanceof FieldInsnNode fi)) continue;
            if (!VARIANT_FIELD.equals(fi.name)) continue;
            if (fi.desc == null || !fi.desc.startsWith("L") || !fi.desc.endsWith(";")) continue;
            return fi.desc.substring(1, fi.desc.length() - 1);
        }
        return null;
    }

}
