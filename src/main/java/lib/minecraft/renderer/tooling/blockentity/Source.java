package lib.minecraft.renderer.tooling.blockentity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A single block-entity model source: the class entry path + method name to parse out of the
 * client jar, the entity model id to emit the output under, the source's Y axis convention,
 * the inventory Y rotation baked into the atlas tile, optional texture dimension overrides
 * (used by the skull variants where the {@code LayerDefinition} method returns a
 * {@code MeshDefinition} and the caller supplies 64x32 vs 64x64), and the parameter-substitution
 * hooks that let the parser evaluate a branch-parameterised factory at one concrete variant:
 * int values for boolean flags (e.g. {@code createFlagLayer(boolean isStanding)}), float values
 * for arithmetic on {@code FLOAD} slots, a pre-seeded {@code CubeDeformation} inflate, an
 * external {@code MeshTransformer.scaling} factor, and an enum-constant reference binding.
 *
 * @param classEntry the zip entry of the source class, e.g. {@code "net/minecraft/client/model/X.class"}
 * @param methodName the name of the method to parse
 * @param entityId the output model id (e.g. {@code "minecraft:chest"})
 * @param yAxis the Y axis convention used by the source bytecode
 * @param inventoryYRotation the GUI-facing yaw applied at render time to recover the vanilla inventory pose
 * @param texWidthOverride overrides the texture width when the parsed method does not call {@code LayerDefinition.create} itself
 * @param texHeightOverride overrides the texture height when the parsed method does not call {@code LayerDefinition.create} itself
 * @param paramIntValues int values to substitute for parameter slots when evaluating branches inside the parsed method
 * @param paramFloatValues float values to substitute for {@code FLOAD} parameter slots when
 *     evaluating arithmetic inside the parsed method - {@code null} disables float param
 *     substitution AND arithmetic evaluation entirely (the parser falls back to the legacy
 *     literal-stack-only walk used by all legacy block-entity sources). When non-null,
 *     {@code FLOAD slot} pushes the substituted value when in range, otherwise pushes the
 *     non-literal marker; binary arithmetic ops ({@code FADD}, {@code FSUB}, {@code FMUL},
 *     {@code FDIV}, {@code DADD}, {@code DSUB}, {@code DMUL}, {@code DDIV}) pop two operands
 *     and push the result, treating non-literal markers as the matching parameter's default
 *     value (or zero when the slot is out of range)
 * @param defaultInflate the {@code CubeDeformation} inflate the parser pre-seeds into its
 *     {@code pendingInflate} slot before walking the method, so a factory that receives its
 *     inflate as a {@code CubeDeformation} argument (rather than constructing one inline) still
 *     emits inflated cubes - e.g. the composite-overlay {@code DROWNED_OUTER_LAYER} enters
 *     {@code DrownedModel.createBodyLayer(new CubeDeformation(0.25F))} past the point where the
 *     {@code 0.25} is visible
 * @param appliedMeshTransformerScale the {@code MeshTransformer.scaling} factor the resolver
 *     captured from a {@code LayerDefinitions}-level {@code .apply(MeshTransformer)} chain that
 *     doesn't appear inline in the factory body (cat / horse route the {@code F} through a
 *     class-level static field or a {@code createRoots} local); {@code 1f} means no external
 *     scale, and inline {@code MeshTransformer.scaling} captures fold on top of this during the
 *     walk
 * @param refParam binds an object-reference parameter slot to a concrete enum constant so the
 *     parser can evaluate {@code if (param == Enum.CONST)} ({@code IF_ACMPEQ} / {@code IF_ACMPNE})
 *     branches - used to split {@code HangingSignRenderer.createHangingSignLayer(Attachment)}
 *     into one mesh per attachment; {@code null} disables enum-ref branch evaluation
 */
public record Source(
    @NotNull String classEntry,
    @NotNull String methodName,
    @NotNull String entityId,
    @NotNull YAxis yAxis,
    float inventoryYRotation,
    @Nullable Integer texWidthOverride,
    @Nullable Integer texHeightOverride,
    int @Nullable [] paramIntValues,
    float @Nullable [] paramFloatValues,
    float defaultInflate,
    float appliedMeshTransformerScale,
    @Nullable RefParam refParam
) {

    /**
     * Binds an object-reference parameter slot to a concrete enum constant, letting the parser
     * resolve a method's {@code if (param == Owner.VALUE)} branch dispatch at a chosen variant.
     *
     * @param slot the JVM local-variable slot holding the reference parameter
     * @param ownerInternal the enum class's JVM internal name (e.g. {@code "net/minecraft/world/level/block/HangingSignBlock$Attachment"})
     * @param value the enum constant field name the slot is bound to (e.g. {@code "CEILING"})
     */
    public record RefParam(int slot, @NotNull String ownerInternal, @NotNull String value) {}

    /**
     * Convenience constructor for sources whose parsed method calls {@code LayerDefinition.create}
     * itself (so texture dimensions are extracted from bytecode) and takes no int parameter.
     *
     * @param classEntry the zip entry of the source class
     * @param methodName the name of the method to parse
     * @param entityId the output model id
     * @param yAxis the Y axis convention used by the source bytecode
     * @param inventoryYRotation the GUI-facing yaw applied at render time
     */
    public Source(@NotNull String classEntry, @NotNull String methodName, @NotNull String entityId, @NotNull YAxis yAxis, float inventoryYRotation) {
        this(classEntry, methodName, entityId, yAxis, inventoryYRotation, null, null, null, null, 0f, 1f, null);
    }

    /**
     * Convenience constructor for sign-variant sources: a method-call-time parameter split by
     * either an int (boolean stick flag) or a reference (attachment enum). The standing-sign
     * board / post split passes {@code paramIntValues}; the hanging-sign attachment split passes
     * {@code refParam}. Texture dimensions are read from the parsed method's
     * {@code LayerDefinition.create} call.
     *
     * @param classEntry the zip entry of the source class
     * @param methodName the name of the method to parse
     * @param entityId the output model id
     * @param yAxis the Y axis convention used by the source bytecode
     * @param inventoryYRotation the GUI-facing yaw applied at render time
     * @param paramIntValues int parameter values for boolean branch evaluation, or {@code null}
     * @param refParam reference-parameter enum binding for attachment branch evaluation, or {@code null}
     */
    public Source(@NotNull String classEntry, @NotNull String methodName, @NotNull String entityId, @NotNull YAxis yAxis, float inventoryYRotation, int @Nullable [] paramIntValues, @Nullable RefParam refParam) {
        this(classEntry, methodName, entityId, yAxis, inventoryYRotation, null, null, paramIntValues, null, 0f, 1f, refParam);
    }

    /**
     * Legacy constructor preserving the prior 8-arg shape (no float param substitution). All
     * existing legacy block-entity sources flow through this so adding the
     * {@code paramFloatValues}, {@code defaultInflate}, and {@code appliedMeshTransformerScale}
     * fields (defaulted to {@code null} / {@code 0f} / {@code 1f}) is a non-behavioural change
     * for them.
     *
     * @param classEntry the zip entry of the source class
     * @param methodName the name of the method to parse
     * @param entityId the output model id
     * @param yAxis the Y axis convention used by the source bytecode
     * @param inventoryYRotation the GUI-facing yaw applied at render time
     * @param texWidthOverride overrides the texture width, or {@code null} to read it from bytecode
     * @param texHeightOverride overrides the texture height, or {@code null} to read it from bytecode
     * @param paramIntValues int parameter values for boolean branch evaluation, or {@code null}
     */
    public Source(@NotNull String classEntry, @NotNull String methodName, @NotNull String entityId, @NotNull YAxis yAxis, float inventoryYRotation, @Nullable Integer texWidthOverride, @Nullable Integer texHeightOverride, int @Nullable [] paramIntValues) {
        this(classEntry, methodName, entityId, yAxis, inventoryYRotation, texWidthOverride, texHeightOverride, paramIntValues, null, 0f, 1f, null);
    }

}
