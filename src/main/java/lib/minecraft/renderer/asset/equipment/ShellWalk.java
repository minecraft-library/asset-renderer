package lib.minecraft.renderer.asset.equipment;

import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.option.spec.ArmorSlot;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a walk of one armour shell resolves to, answered once for the shell rather than once per render.
 * <p>
 * Both consumers of a shell - the one that builds its triangles and the one that measures its screen
 * bounds - ask the same two questions of every bone: does this slot's armour draw it, and where does it
 * sit. Neither question depends on the render, so both are resolved when the shell is indexed. The
 * coverage answer costs a parent walk per {@code (slot, bone)} pair and the anchor a walk per bone, and
 * an armoured render used to pay both four times over, once per equipped slot, in each of the two
 * consumers.
 * <p>
 * <b>This shares the walk's inputs and nothing downstream of them.</b> The two consumers assemble a
 * corner from these values by different arithmetic in different frames and they must keep doing so - the
 * triangle builder sums pivots and ends in the upright frame, the bounds walk composes matrices and
 * measures the Y-down mesh. Sharing an assembled box would measure the shell in a frame reflected from
 * the one it is drawn in.
 * <p>
 * Both maps are lookup-only. Nothing iterates them, so neither the per-run salt of an immutable map nor
 * the insertion order of a mutable one can reach a render: the bone order that <em>is</em> load-bearing
 * is the mesh's own, and both consumers still take it from the mesh.
 *
 * @param covered the bone names each slot's armour draws
 * @param anchors each bone's position in mesh space
 */
public record ShellWalk(
    @NotNull Map<ArmorSlot, Set<String>> covered,
    @NotNull Map<String, Vector3f> anchors
) {

    /**
     * Resolves the walk of one shell.
     *
     * @param mesh the shell's ungrown mesh
     * @param form which of the two shells it is, which decides what each slot covers
     * @return the resolved walk
     */
    public static @NotNull ShellWalk of(@NotNull EntityModelData mesh, @NotNull ArmorForm form) {
        Map<ArmorSlot, Set<String>> covered = new EnumMap<>(ArmorSlot.class);

        for (ArmorSlot slot : ArmorSlot.values()) {
            Set<String> bones = new HashSet<>();

            for (String bone : mesh.getBones().keySet())
                if (form.covers(mesh, slot, bone)) bones.add(bone);

            covered.put(slot, Set.copyOf(bones));
        }

        Map<String, Vector3f> anchors = new HashMap<>();

        for (Map.Entry<String, EntityModelData.Bone> entry : mesh.getBones().entrySet())
            anchors.put(entry.getKey(), chainedPivot(mesh, entry.getValue()));

        return new ShellWalk(Map.copyOf(covered), Map.copyOf(anchors));
    }

    /**
     * Whether a slot's armour draws this bone's cubes.
     *
     * @param slot the armour slot
     * @param bone the bone name to test
     * @return {@code true} when that slot's armour draws this bone
     */
    public boolean covers(@NotNull ArmorSlot slot, @NotNull String bone) {
        return this.covered.get(slot).contains(bone);
    }

    /**
     * A bone's anchor in mesh space.
     *
     * @param bone the bone name
     * @return the anchor, or the origin when the shell has no such bone
     */
    public @NotNull Vector3f anchor(@NotNull String bone) {
        return this.anchors.getOrDefault(bone, Vector3f.ZERO);
    }

    /**
     * A bone's anchor in mesh space - its own pivot plus every ancestor's, since a bone pivot is
     * parent-relative.
     * <p>
     * Summed leaf-first, which is the order the operands have to arrive in: float addition does not
     * associate, so re-rooting the sum would move corners by an amount derived from the pivots
     * themselves. The chain is bounded by the set of bones already visited rather than by a depth cap -
     * the real maximum is two hops, so the two agree on everything shipped, and the set is what actually
     * rules out the cycle a cap stands in for.
     */
    private static @NotNull Vector3f chainedPivot(
        @NotNull EntityModelData mesh, @NotNull EntityModelData.Bone bone) {
        Vector3f anchor = bone.getPivot();
        Set<String> visited = new HashSet<>();
        String parent = bone.getParent();

        while (parent != null && visited.add(parent)) {
            EntityModelData.Bone cursor = mesh.getBones().get(parent);
            if (cursor == null) break;
            anchor = anchor.add(cursor.getPivot());
            parent = cursor.getParent();
        }

        return anchor;
    }

}
