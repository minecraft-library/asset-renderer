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
 */
public record Source(
    @NotNull String classEntry,
    @NotNull String methodName,
    @NotNull String entityId,
    @NotNull YAxis yAxis,
    float inventoryYRotation,
    @Nullable Integer texWidthOverride,
    @Nullable Integer texHeightOverride,
    int @Nullable [] paramIntValues
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
        this(classEntry, methodName, entityId, yAxis, inventoryYRotation, null, null, null);
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
        this(classEntry, methodName, entityId, yAxis, inventoryYRotation, texWidthOverride, texHeightOverride, null);
    }

}
