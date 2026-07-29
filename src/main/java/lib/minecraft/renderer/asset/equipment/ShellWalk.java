package lib.minecraft.renderer.asset.equipment;

import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.face.Unwrap;
import lib.minecraft.renderer.option.spec.ArmorSlot;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a walk of one armour shell resolves to, answered once for the shell rather than once per render.
 * <p>
 * Both consumers of a shell - the one that builds its triangles and the one that measures its screen
 * bounds - ask the same questions of every bone: does this slot's armour draw it, and where does it
 * sit. Neither question depends on the render, so both are resolved when the shell is indexed. The
 * coverage answer costs a parent walk per {@code (slot, bone)} pair and the anchor a walk per bone, and
 * an armoured render used to pay both four times over, once per equipped slot, in each of the two
 * consumers.
 * <p>
 * The two consumers want the answer in different shapes, which is why both are here. The triangle
 * builder wants a flat row per cube and gets {@link #parts}; the bounds adapter rebuilds a bone tree
 * and wants the coverage keyed the way it iterates, by bone name. They agree by construction - a bone
 * with cubes has a row exactly when it is covered, and a bone without cubes contributes nothing to
 * either.
 * <p>
 * <b>This shares the walk's inputs and nothing downstream of them.</b> The two consumers assemble a
 * corner from these values by different arithmetic in different frames and they must keep doing so - the
 * triangle builder sums pivots and ends in the upright frame, the bounds walk composes matrices and
 * measures the Y-down mesh. Sharing an assembled box would measure the shell in a frame reflected from
 * the one it is drawn in.
 * <p>
 * {@link #parts} is a list because the order in it is load-bearing: it is the mesh's own bone order and
 * then each bone's own cube order, which is what keeps a part and the overlay box parented to it
 * adjacent, and a coplanar tie is decided by emission order. {@link #covered} is lookup-only, so
 * neither the per-run salt of an immutable map nor the insertion order of a mutable one can reach a
 * render.
 *
 * @param parts one row per cube of the shell, in the mesh's own order
 * @param covered the bone names each slot's armour draws
 */
public record ShellWalk(
    @NotNull List<ShellPart> parts,
    @NotNull Map<ArmorSlot, Set<String>> covered
) {

    /**
     * Resolves the walk of one shell.
     *
     * @param mesh the shell's ungrown mesh
     * @param form which of the two shells it is, which decides what each slot covers
     * @param innerGrow the per-side growth the innermost armour layer applies
     * @param outerGrow the per-side growth the outer armour layer applies
     * @return the resolved walk
     */
    public static @NotNull ShellWalk of(@NotNull EntityModelData mesh, @NotNull ArmorForm form,
                                        @NotNull Vector3f innerGrow, @NotNull Vector3f outerGrow) {
        Map<ArmorSlot, Set<String>> covered = new EnumMap<>(ArmorSlot.class);

        for (ArmorSlot slot : ArmorSlot.values()) {
            Set<String> bones = new HashSet<>();

            for (String bone : mesh.getBones().keySet())
                if (form.covers(mesh, slot, bone)) bones.add(bone);

            covered.put(slot, Set.copyOf(bones));
        }

        List<ShellPart> parts = new ArrayList<>();

        for (Map.Entry<String, EntityModelData.Bone> entry : mesh.getBones().entrySet()) {
            String bone = entry.getKey();
            EnumSet<ArmorSlot> slots = EnumSet.noneOf(ArmorSlot.class);

            for (ArmorSlot slot : ArmorSlot.values())
                if (covered.get(slot).contains(bone)) slots.add(slot);

            Set<ArmorSlot> drawnBy = Set.copyOf(slots);
            Vector3f anchor = chainedPivot(mesh, entry.getValue());
            float scale = entry.getValue().getScale();

            for (EntityModelData.Cube cube : entry.getValue().getCubes())
                parts.add(new ShellPart.Mesh(bone,
                    new Unwrap.Atlas(cube.getUv(), cube.getSize(), cube.isMirror()), drawnBy,
                    anchor.add(cube.getOrigin().multiply(scale)), cube.getSize().multiply(scale),
                    innerGrow.add(cube.getGrow()).multiply(scale),
                    outerGrow.add(cube.getGrow()).multiply(scale)));
        }

        return new ShellWalk(List.copyOf(parts), Map.copyOf(covered));
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
