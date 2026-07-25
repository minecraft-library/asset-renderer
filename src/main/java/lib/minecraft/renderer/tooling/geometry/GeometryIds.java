package lib.minecraft.renderer.tooling.geometry;

import org.jetbrains.annotations.NotNull;

/**
 * The factory-coordinate geometry key grammar
 * {@code <SimpleClass>#<method>[@k=v...]} - the dedupe identity IS the key, collision-free
 * by construction, greppable, replacing the legacy insertion-order {@code _<n>} fragility.
 *
 * <p>Discriminators appear only when set, in the declared canonical order {@code grow},
 * {@code fparam}, {@code scaled}, {@code pose}, {@code iparam}, {@code ref}:
 * <ul>
 *   <li><b>{@code @grow=<v>}</b> / <b>{@code @grow=<x,y,z>}</b> - the request's grow
 *       pre-seed (scalar form when uniform), absent at zero.</li>
 *   <li><b>{@code @fparam=<v>}</b> - the slot-0 float-parameter seed (donkey 0.87, mule
 *       0.92), absent at zero / no table.</li>
 *   <li><b>{@code @scaled=<f>}</b> - the external MeshTransformer scale, absent at 1.</li>
 *   <li><b>{@code @pose=<x,y,z>}</b> - the bound {@code PartPose} offset (the baby piglin's
 *       armor arm offset), absent at the zero pose the generic baby shell is built at.</li>
 *   <li><b>{@code @iparam=<slot>:<v>[,...]}</b> - the bound int-parameter slots
 *       (banner standing / wall). Non-zero slots are encoded; a bound-but-all-zero table
 *       encodes {@code 0:0} - the substitution semantics, not the table length, are the
 *       identity (an 8-slot zeroed table and a 1-slot zero table parse identically).</li>
 *   <li><b>{@code @ref=<ConstName>}</b> - the bound enum constant (hanging-sign
 *       CEILING / WALL).</li>
 * </ul>
 */
final class GeometryIds {

    private GeometryIds() {
    }

    /**
     * Mints the key for a request.
     *
     * @param request the deduped request
     * @return the factory-coordinate key
     */
    static @NotNull String of(@NotNull GeometryRequest request) {
        StringBuilder key = new StringBuilder(simpleClass(request.factoryClass()))
            .append('#').append(request.factoryMethod());
        float[] grow = request.grow();
        if (grow[0] != 0f || grow[1] != 0f || grow[2] != 0f) {
            key.append("@grow=");
            if (grow[0] == grow[1] && grow[1] == grow[2]) key.append(grow[0]);
            else key.append(grow[0]).append(',').append(grow[1]).append(',').append(grow[2]);
        }
        float[] floatParams = request.paramFloatValues();
        if (floatParams != null && floatParams.length > 0 && floatParams[0] != 0f)
            key.append("@fparam=").append(floatParams[0]);
        if (request.appliedMeshTransformerScale() != 1f)
            key.append("@scaled=").append(request.appliedMeshTransformerScale());
        GeometryRequest.PoseParam pose = request.poseParam();
        if (pose != null && (pose.offset()[0] != 0f || pose.offset()[1] != 0f || pose.offset()[2] != 0f))
            key.append("@pose=").append(pose.offset()[0])
                .append(',').append(pose.offset()[1])
                .append(',').append(pose.offset()[2]);
        int[] intParams = request.paramIntValues();
        if (intParams != null) {
            StringBuilder pairs = new StringBuilder();
            for (int slot = 0; slot < intParams.length; slot++) {
                if (intParams[slot] == 0) continue;
                if (!pairs.isEmpty()) pairs.append(',');
                pairs.append(slot).append(':').append(intParams[slot]);
            }
            key.append("@iparam=").append(pairs.isEmpty() ? "0:0" : pairs);
        }
        if (request.refParam() != null)
            key.append("@ref=").append(request.refParam().value());
        return key.toString();
    }

    /**
     * The simple class name of a JVM internal name (nested classes keep their {@code $}
     * form: {@code a/b/Outer$Inner} yields {@code Outer$Inner}).
     */
    private static @NotNull String simpleClass(@NotNull String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

}
