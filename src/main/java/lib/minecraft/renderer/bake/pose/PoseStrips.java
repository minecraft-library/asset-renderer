package lib.minecraft.renderer.bake.pose;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.engine.frame.StripFit;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The strip each resolved row is drawn over - fitted the first time the row is asked for and held for
 * the process, so every later ask under one key answers the same fit.
 *
 * <p>A row with no source in force is {@link StripFit#STILL} and is never sampled. Every other row is
 * sampled through every channel its posed frames draw and fitted by {@link StripFit#fit}, once per
 * key. The key holds what the sampler reads:
 * <ul>
 *     <li><b>the row by value</b>, so the in-force view, which rebuilds a narrowed row on every resolve,
 *     still meets the fit it made before;</li>
 *     <li><b>the figure window</b> the row frames at - its own where it declares one;</li>
 *     <li><b>every pose the sampler reads, by instance</b> - the body's, each pass's, walked or not, and
 *     each equipment layer's, in order - and each pass's opacity expression where it has one;</li>
 *     <li><b>every mesh the sampler reads, by what it reads off it</b> - the body's, each pass's and
 *     each equipment layer's, in the same order, each as its {@link MeshRest};</li>
 *     <li><b>which passes are walked</b> - every pass but one drawing the body's own mesh under the
 *     body's own pose, which writes the body's channels a second time - since a copy of the body's mesh
 *     reads as the body's and is walked all the same;</li>
 *     <li><b>each equipment layer's slot, by value</b>, which names the channels the layer is sampled
 *     under;</li>
 *     <li><b>what a pass scrolls by, by value</b> - each pass's scroll rate, and the width and height of
 *     the sheet a scrolling pass moves over, whose texels its samples are in.</li>
 * </ul>
 * A pose writes only the bones the mesh it is drawn on declares, and reads that mesh's rest values,
 * so one pose instance over two meshes that read apart samples two sets of tracks. A bone is read
 * whether or not it draws, so a resolve that builds a mesh afresh - a selected bone toggle flipping
 * a bone it reaches - reads as the mesh it flipped and meets the fit taken over it.
 *
 * <p>Safe to ask from any thread. A fit is taken outside any lock and published only where no other
 * thread published one first, so two threads asking for one key together may both fit it, and both
 * answer the fit that was published.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
@Parity(subject = Subject.ENTITY)
public final class PoseStrips {

    /** Every fit taken in the process, by the key it was taken for. */
    private static final @NotNull ConcurrentHashMap<Key, StripFit> FITS = new ConcurrentHashMap<>();

    /**
     * Answers the strip a resolved row is drawn over, fitting it the first time its key is asked for.
     *
     * @param subject the resolved subject the row poses
     * @param style the resolved row
     * @param periodTicks the figure period the row frames at where it declares none of its own
     * @return the row's strip, {@link StripFit#STILL} where no source is in force
     */
    public static @NotNull StripFit of(@NotNull Entity subject, @NotNull PoseStyle style, int periodTicks) {
        if (!style.moves()) return StripFit.STILL;

        Key key = Key.of(subject, style, periodTicks);
        StripFit held = FITS.get(key);
        if (held != null) return held;

        StripFit fitted = StripFit.fit(StripSampler.tracks(subject, style, periodTicks));
        StripFit raced = FITS.putIfAbsent(key, fitted);
        return raced == null ? fitted : raced;
    }

    /**
     * What one fit is held under.
     *
     * @param style the in-force row, compared by value
     * @param window the figure window the row frames at
     * @param poses the body's pose, each pass's pose and each equipment layer's pose, in that order,
     *     compared by instance
     * @param meshes what is read off the body's mesh, each pass's mesh and each equipment layer's mesh,
     *     in that order, compared by value
     * @param walked whether each pass is walked as a mesh of its own, compared by value
     * @param slots each equipment layer's slot, compared by value
     * @param scrolls each pass's scroll rate, empty where it scrolls none, compared by value
     * @param sheets each pass's sheet width and height where it scrolls, zero for both where it does
     *     not, compared by value
     * @param opacities each pass's opacity expression, empty where it draws at a constant one,
     *     compared by instance
     */
    record Key(
        @NotNull PoseStyle style,
        int window,
        @NotNull List<EntityPose> poses,
        @NotNull List<MeshRest> meshes,
        @NotNull List<Boolean> walked,
        @NotNull List<String> slots,
        @NotNull List<Optional<Vector2f>> scrolls,
        @NotNull List<Integer> sheets,
        @NotNull List<Optional<PoseExpr>> opacities
    ) {

        /**
         * Builds the key a resolved row's fit is held under.
         *
         * @param subject the resolved subject the row poses
         * @param style the resolved row
         * @param periodTicks the figure period the row frames at where it declares none of its own
         * @return the key
         */
        static @NotNull Key of(@NotNull Entity subject, @NotNull PoseStyle style, int periodTicks) {
            int sampled = 1 + subject.overlays().size() + subject.layers().equipment().size();
            List<EntityPose> poses = new ArrayList<>(sampled);
            List<MeshRest> meshes = new ArrayList<>(sampled);
            List<Boolean> walked = new ArrayList<>(subject.overlays().size());
            List<String> slots = new ArrayList<>(subject.layers().equipment().size());
            List<Optional<Vector2f>> scrolls = new ArrayList<>(subject.overlays().size());
            List<Integer> sheets = new ArrayList<>(2 * subject.overlays().size());
            List<Optional<PoseExpr>> opacities = new ArrayList<>(subject.overlays().size());
            MeshRest body = MeshRest.of(subject.model());
            poses.add(subject.pose());
            meshes.add(body);
            for (Entity.OverlayLayer pass : subject.overlays()) {
                poses.add(pass.pose());
                // A pass redrawing the body's own mesh shares the body's read rather than taking a second.
                meshes.add(pass.model() == subject.model() ? body : MeshRest.of(pass.model()));
                walked.add(StripSampler.walks(subject, pass));
                scrolls.add(pass.textureScroll());
                sheets.add(pass.textureScroll().isPresent() ? pass.model().getTextureWidth() : 0);
                sheets.add(pass.textureScroll().isPresent() ? pass.model().getTextureHeight() : 0);
                opacities.add(pass.opacity());
            }
            for (Entity.EquipmentOverlay equipment : subject.layers().equipment()) {
                poses.add(equipment.pose());
                meshes.add(MeshRest.of(equipment.model()));
                slots.add(equipment.slot());
            }
            return new Key(style, style.periodTicks().orElse(periodTicks), poses, meshes, walked, slots, scrolls,
                sheets, opacities);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that
                && this.window == that.window
                && this.style.equals(that.style)
                && identical(this.poses, that.poses)
                && this.meshes.equals(that.meshes)
                && this.walked.equals(that.walked)
                && this.slots.equals(that.slots)
                && this.scrolls.equals(that.scrolls)
                && this.sheets.equals(that.sheets)
                && identicalPresent(this.opacities, that.opacities);
        }

        @Override
        public int hashCode() {
            int hash = 31 * this.style.hashCode() + this.window;
            for (EntityPose pose : this.poses)
                hash = 31 * hash + System.identityHashCode(pose);
            hash = 31 * (31 * hash + this.meshes.hashCode()) + this.walked.hashCode();
            hash = 31 * hash + this.slots.hashCode();
            hash = 31 * (31 * hash + this.scrolls.hashCode()) + this.sheets.hashCode();
            for (Optional<PoseExpr> opacity : this.opacities)
                hash = 31 * hash + opacity.map(System::identityHashCode).orElse(0);
            return hash;
        }

        /** Tests whether two lists hold the same instances in the same order. */
        private static boolean identical(@NotNull List<?> first, @NotNull List<?> second) {
            if (first.size() != second.size()) return false;
            for (int index = 0; index < first.size(); index++)
                if (first.get(index) != second.get(index)) return false;

            return true;
        }

        /**
         * Tests whether two lists of optional values are empty at the same places and hold the same
         * instances elsewhere.
         */
        private static boolean identicalPresent(
            @NotNull List<? extends Optional<?>> first, @NotNull List<? extends Optional<?>> second) {

            if (first.size() != second.size()) return false;
            for (int index = 0; index < first.size(); index++)
                if (first.get(index).orElse(null) != second.get(index).orElse(null)) return false;

            return true;
        }

    }

    /**
     * What a sampling reads off one mesh - every bone it declares, in its own order, each with the
     * values a read of the bone answers from.
     *
     * <p>A pose and a clip find a bone by its name, and a channel read answers off the bone's pivot,
     * its rotation, its rest scale and the parent it hangs from, so those are what each bone is held
     * at. They settle the rest of what a sampling asks the mesh as well: the factor the whole mesh was
     * flattened at, which its bones' rest scales give in the bones' order; the scale above each bone,
     * which is its parent's rest scale or that factor; and the container a clip reaches under the
     * parent a bone names and the mesh declares no bone for. A bone's cubes, whether it draws and the
     * toggle that flips that, its rest-pose rotation and whether the mesh culls are read nowhere a
     * sampling goes, and the mesh's sheet only where a pass scrolls over it, which the key holds
     * beside this.
     *
     * <p>Built once per ask, with its hash taken as it is built.
     */
    static final class MeshRest {

        /** Every bone the mesh declares, in the mesh's own order. */
        private final @NotNull List<BoneRest> bones;

        /** The hash of {@link #bones}, taken once. */
        private final int hash;

        private MeshRest(@NotNull List<BoneRest> bones) {
            this.bones = bones;
            this.hash = bones.hashCode();
        }

        /**
         * Reads what a sampling reads off one mesh.
         *
         * @param mesh the mesh
         * @return what is read off it
         */
        static @NotNull MeshRest of(@NotNull EntityMesh mesh) {
            List<BoneRest> bones = new ArrayList<>(mesh.getBones().size());
            for (Map.Entry<String, EntityMesh.Bone> bone : mesh.getBones().entrySet()) {
                bones.add(new BoneRest(bone.getKey(), bone.getValue().getPivot(), bone.getValue().getRotation(),
                    bone.getValue().getScale(), Optional.ofNullable(bone.getValue().getParent())));
            }
            return new MeshRest(bones);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MeshRest that
                && this.hash == that.hash
                && this.bones.equals(that.bones);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public @NotNull String toString() {
            return this.bones.toString();
        }

    }

    /**
     * What a sampling reads off one bone.
     *
     * @param name the name the bone is declared under, which a pose and a clip find it by
     * @param pivot the bone's parent-relative pivot, which a position read answers from
     * @param rotation the bone's rest rotation, which a rotation read answers from
     * @param scale the bone's rest scale, which a scale read answers from, and the scale above every
     *     bone hanging from it
     * @param parent the name of the bone it hangs from, empty for a bone hanging from the root
     */
    record BoneRest(
        @NotNull String name,
        @NotNull Vector3f pivot,
        @NotNull EulerRotation rotation,
        float scale,
        @NotNull Optional<String> parent
    ) {}

}
