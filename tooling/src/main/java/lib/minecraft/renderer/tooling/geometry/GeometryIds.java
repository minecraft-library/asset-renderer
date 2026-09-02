package lib.minecraft.renderer.tooling.geometry;

import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * The factory-coordinate geometry key grammar
 * {@code <SimpleClass>#<method>[@k=v...]} - the dedupe identity IS the key, collision-free
 * by construction, greppable, replacing the legacy insertion-order {@code _<n>} fragility.
 *
 * <p>A key spells two kinds of discriminator, and they are not interchangeable. A <b>request</b>
 * discriminator says what the factory was CALLED with, so it changes what the walk computes and
 * the mesh it yields is readable no other way. A <b>{@link Derivation}</b> says what was done to
 * the mesh the walk already yielded, so it is a transform of a mesh that also ships. Request
 * discriminators come first, in their own canonical order; derivations follow in theirs.
 *
 * <p>Request discriminators appear only when set, in the declared canonical order {@code grow},
 * {@code fparam}, {@code scaled}, {@code baby}, {@code pose}, {@code iparam}, {@code ref}:
 * <ul>
 *   <li><b>{@code @grow=<v>}</b> / <b>{@code @grow=<x,y,z>}</b> - the request's grow
 *       pre-seed (scalar form when uniform), absent at zero. A <em>pre-seed</em> of the
 *       factory's default deformation rather than an addition to what a cube already carries:
 *       a cube the factory deforms inline answers its own way, so this is not
 *       {@link Derivation#INFLATE} under another name and the two never share a spelling.</li>
 *   <li><b>{@code @fparam=<v>}</b> - the slot-0 float-parameter seed (donkey 0.87, mule
 *       0.92), absent at zero / no table.</li>
 *   <li><b>{@code @scaled=<f>}</b> - the external MeshTransformer scale, absent at 1.</li>
 *   <li><b>{@code @baby=<SimpleClass.FIELD>}</b> - the aged-down whole-mesh transformer the
 *       registration applies, named the way {@code @ref} names an enum constant rather than
 *       spelling its seven values; absent for a mesh registered untransformed.</li>
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
@UtilityClass
public final class GeometryIds {

    /**
     * What was done to a mesh the walk already yielded, in the canonical order a key spells the
     * discriminators naming it - which is the order the passes minting them run in, so a mesh
     * reached by several reads its key left to right as the sequence that built it.
     *
     * <p>A derivation is a transform of a mesh that also ships, so a reader holding the bare
     * coordinate and the discriminator can say what the derived mesh is. That is what separates
     * these from the request discriminators, where the mesh is whatever the bytecode computed.
     */
    @Getter(style = NamingStyle.FLUENT)
    @RequiredArgsConstructor
    public enum Derivation {

        /** What a subject rests without, the bones it never draws being gone and the rest marked. */
        REST("rest"),
        /** The vanilla {@code retainExactParts} subset a pass is restricted to. */
        RETAIN("retain"),
        /** The uniform amount added to every cube's grow, on every axis. */
        INFLATE("inflate"),
        /** The bone whose subtree is emptied, vanilla's {@code clearChild().clearRecursively()}. */
        CLEARED("cleared");

        /** The member name this derivation is spelled with, in a key and in the {@code source} twin. */
        private final @NotNull String token;

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
        BabyMeshTransform baby = request.babyTransform();
        if (baby != null) key.append("@baby=").append(baby.discriminator());
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
     * Mints the key a derived mesh is written under.
     *
     * <p>Appended to the key of the mesh it was derived from, whatever that already spells, so a
     * derivation of a request-discriminated mesh reads as both. A caller may hand the derivations
     * in any order - what is spelled is {@link Derivation}'s own, so two passes minting the same
     * pair of derivations can only mint the same key.
     *
     * @param coordinate the key of the mesh derived from
     * @param derivations what was done to it
     * @return the derived key, or {@code coordinate} where nothing was done to it
     */
    public static @NotNull String derived(
        @NotNull String coordinate, @NotNull Map<Derivation, String> derivations) {

        if (derivations.isEmpty()) return coordinate;
        StringBuilder key = new StringBuilder(coordinate);
        new EnumMap<>(derivations).forEach((derivation, value) ->
            key.append('@').append(derivation.token()).append('=').append(value));
        return key.toString();
    }

    /**
     * Stamps onto a derived entry's {@code source} the machine-readable twin of what its key
     * spells, so the entry and the key say one thing rather than two.
     *
     * <p>A mesh derived in place carries no discriminator and is stamped with none: the twin
     * mirrors the key, and a key that distinguishes nothing has nothing to mirror.
     *
     * @param entry the derived geometry entry
     * @param derivations what was done to the mesh it was derived from
     */
    public static void stampSource(
        @NotNull JsonTree entry, @NotNull Map<Derivation, String> derivations) {

        if (derivations.isEmpty()) return;
        entry.find("source").ifPresent(source ->
            new EnumMap<>(derivations).forEach((derivation, value) ->
                source.put(derivation.token(), value)));
    }

    /**
     * The simple class name of a JVM internal name (nested classes keep their {@code $}
     * form: {@code a/b/Outer$Inner} yields {@code Outer$Inner}).
     */
    private static @NotNull String simpleClass(@NotNull String internalName) {
        return ClassKit.simpleName(internalName);
    }

}
