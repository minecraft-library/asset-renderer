package lib.minecraft.renderer.tooling.blockentity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A single block-entity model source: the class entry path + method name to parse out of the
 * client jar, the entity model id to emit the output under, the source's Y axis convention,
 * the inventory Y rotation baked into the atlas tile, optional texture dimension overrides
 * (used by the skull variants where the LayerDefinition method returns a MeshDefinition and
 * the caller supplies 64x32 vs 64x64), and optional parameter-int values for methods
 * parameterised by a boolean flag (e.g. {@code createFlagLayer(boolean isStanding)}).
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
 *     literal-stack-only walk used by all bedrock-side block-entity sources). When non-null,
 *     {@code FLOAD slot} pushes the substituted value when in range, otherwise pushes the
 *     non-literal marker; binary arithmetic ops ({@code FADD}, {@code FSUB}, {@code FMUL},
 *     {@code FDIV}, {@code DADD}, {@code DSUB}, {@code DMUL}, {@code DDIV}) pop two operands
 *     and push the result, treating non-literal markers as the matching parameter's default
 *     value (or zero when the slot is out of range)
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
    float defaultInflate
) {

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
        this(classEntry, methodName, entityId, yAxis, inventoryYRotation, null, null, null, null, 0f);
    }

    /**
     * Convenience constructor for sources that need explicit texture dimensions (typically a
     * {@code MeshDefinition} factory wrapped in {@code LayerDefinition.create(mesh, W, H)} by
     * the caller) but take no int parameter.
     *
     * @param classEntry the zip entry of the source class
     * @param methodName the name of the method to parse
     * @param entityId the output model id
     * @param yAxis the Y axis convention used by the source bytecode
     * @param inventoryYRotation the GUI-facing yaw applied at render time
     * @param texWidthOverride the texture width override
     * @param texHeightOverride the texture height override
     */
    public Source(@NotNull String classEntry, @NotNull String methodName, @NotNull String entityId, @NotNull YAxis yAxis, float inventoryYRotation, @Nullable Integer texWidthOverride, @Nullable Integer texHeightOverride) {
        this(classEntry, methodName, entityId, yAxis, inventoryYRotation, texWidthOverride, texHeightOverride, null, null, 0f);
    }

    /**
     * Legacy constructor preserving the prior 8-arg shape (no float param substitution). All
     * existing bedrock-side block-entity sources flow through this so adding the
     * {@code paramFloatValues} field is a non-behavioural change for them.
     */
    public Source(@NotNull String classEntry, @NotNull String methodName, @NotNull String entityId, @NotNull YAxis yAxis, float inventoryYRotation, @Nullable Integer texWidthOverride, @Nullable Integer texHeightOverride, int @Nullable [] paramIntValues) {
        this(classEntry, methodName, entityId, yAxis, inventoryYRotation, texWidthOverride, texHeightOverride, paramIntValues, null, 0f);
    }

    /**
     * Convenience constructor preserving the prior 9-arg shape (no {@code defaultInflate}). Java
     * pipeline call sites flow through this so adding the {@code defaultInflate} field is a
     * non-behavioural change for them.
     */
    public Source(@NotNull String classEntry, @NotNull String methodName, @NotNull String entityId, @NotNull YAxis yAxis, float inventoryYRotation, @Nullable Integer texWidthOverride, @Nullable Integer texHeightOverride, int @Nullable [] paramIntValues, float @Nullable [] paramFloatValues) {
        this(classEntry, methodName, entityId, yAxis, inventoryYRotation, texWidthOverride, texHeightOverride, paramIntValues, paramFloatValues, 0f);
    }

}
