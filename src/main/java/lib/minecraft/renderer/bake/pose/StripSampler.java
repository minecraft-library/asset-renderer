package lib.minecraft.renderer.bake.pose;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.engine.frame.StripFit;
import lib.minecraft.renderer.engine.frame.StripTrack;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseEvaluator;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.function.ToDoubleFunction;

/**
 * The tracks a resolved row's strip is fitted over - every channel the row's posed frames draw,
 * sampled at each tick of {@link StripFit#WINDOW_TICKS} from tick 0 through the frame the row answers
 * there.
 *
 * <p>Three kinds of mesh are walked, in this order: the body as {@code body}, each overlay pass as
 * {@code pass<i>} by its index in the subject's passes, and each equipment layer as
 * {@code equip:<slot>}. A pass drawing the body's own mesh under the body's own pose writes the body's
 * channels a second time and is not walked, and a mesh whose pose could not be read contributes
 * nothing. What each walked mesh contributes is named {@code <mesh>.<bone>.<member>}, or
 * {@code <mesh>.$container<k>.<member>} for the container's step {@code k}, outermost first:
 * <ul>
 *     <li><b>the pose's expressions</b>, each written member one track;</li>
 *     <li><b>each clip site apart</b>, each member it displaces one track under the same name as the
 *     member's expression - a displacement of the container under its innermost step's - so the fit
 *     sums what the frame sums. A site's period comes off how far the clip clock moves per tick across
 *     the ticks it plays, whichever way it runs: a looping clip repeats on its length at that speed, and
 *     one played once runs from where the clock starts to the end the clock runs toward - the clip's
 *     length where it runs forward, its start where it runs backward. A site whose clock never moves
 *     while it plays holds one displacement and contributes nothing.</li>
 * </ul>
 *
 * <p>Beside the meshes, every pass contributes what it moves without a pose of its own, the pass that
 * is not walked included: each axis its sheet scrolls along, as {@code pass<i>.scroll.u} or
 * {@code .v} in the texels of the pass's sheet, coming round at the sheet's extent and repeating on
 * the inverse of its rate whichever way it scrolls, and its opacity where it follows the clock, as
 * {@code pass<i>.opacity} at the value the pass draws at.
 *
 * <p>A rotation is sampled in degrees and a position in the model's own units, as the evaluator and
 * the clip player answer it, with no scale a mesh was flattened at; a bone is sampled whether or not
 * it draws. A contribution that never changes over the window is left out, since nothing the fit reads
 * moves with it. Every map walked is insertion-ordered, so the tracks come out in one order on every
 * run.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
@Parity(subject = Subject.ENTITY)
final class StripSampler {

    /** The span a rotation comes round at, in degrees. */
    private static final double FULL_TURN = 360d;

    /** The name a container step is spelled under in a channel, before its index. */
    private static final @NotNull String CONTAINER = "$container";

    /** Milliseconds a whole second holds, which is the unit a clip clock counts in. */
    private static final double MILLIS_PER_SECOND = 1000d;

    /**
     * Samples every channel a resolved row's posed frames draw.
     *
     * @param subject the resolved subject the row poses
     * @param style the resolved row
     * @param periodTicks the figure period the row frames at where it declares none of its own
     * @return the row's tracks, in the order the meshes and passes are walked
     */
    static @NotNull List<StripTrack> tracks(@NotNull Entity subject, @NotNull PoseStyle style, int periodTicks) {
        List<ToDoubleFunction<String>> frames = new ArrayList<>(StripFit.WINDOW_TICKS);
        for (int tick = 0; tick < StripFit.WINDOW_TICKS; tick++)
            frames.add(style.frameAt(tick, periodTicks));

        List<StripTrack> tracks = new ArrayList<>();
        sample("body", subject.pose(), subject.model(), frames, tracks);
        for (int index = 0; index < subject.overlays().size(); index++) {
            Entity.OverlayLayer pass = subject.overlays().get(index);
            if (!walks(subject, pass)) continue;
            sample("pass" + index, pass.pose(), pass.model(), frames, tracks);
        }
        for (Entity.EquipmentOverlay equipment : subject.layers().equipment())
            sample("equip:" + equipment.slot(), equipment.pose(), equipment.model(), frames, tracks);

        for (int index = 0; index < subject.overlays().size(); index++)
            scrolls("pass" + index, subject.overlays().get(index), tracks);
        for (int index = 0; index < subject.overlays().size(); index++) {
            Entity.OverlayLayer pass = subject.overlays().get(index);
            if (pass.opacity().isPresent())
                opacity("pass" + index, pass.opacity().get(), pass.model(), frames, tracks);
        }
        return tracks;
    }

    /**
     * Tests whether one pass's mesh is walked under the pass's own name - every pass's but one drawing
     * the body's own mesh under the body's own pose, which writes the body's channels a second time.
     *
     * @param subject the resolved subject the pass belongs to
     * @param pass the pass
     * @return whether the pass's mesh is walked
     */
    static boolean walks(@NotNull Entity subject, @NotNull Entity.OverlayLayer pass) {
        return pass.pose() != subject.pose() || pass.model() != subject.model();
    }

    /**
     * Samples what one mesh's pose writes and what each of its clip sites displaces.
     *
     * @param prefix the mesh's name in the channels it contributes
     * @param pose the pose belonging to the mesh
     * @param mesh the mesh
     * @param frames the row's frame at each tick of the window
     * @param tracks the tracks sampled so far, added to
     */
    private static void sample(
        @NotNull String prefix, @NotNull EntityPose pose, @NotNull EntityMesh mesh,
        @NotNull List<ToDoubleFunction<String>> frames, @NotNull List<StripTrack> tracks) {

        if (!pose.isReadable()) return;
        Map<String, Series> written = new LinkedHashMap<>();
        List<Site> sites = new ArrayList<>(pose.clips().size());
        for (EntityPose.Clip clip : pose.clips())
            sites.add(new Site(clip));

        for (int tick = 0; tick < StripFit.WINDOW_TICKS; tick++) {
            ToDoubleFunction<String> frame = frames.get(tick);
            PoseEvaluator.ChannelWrites writes = PosePlayer.evaluate(pose, mesh, frame);
            List<Map<PoseChannel, Float>> steps = writes.container();
            for (int step = 0; step < steps.size(); step++)
                for (Map.Entry<PoseChannel, Float> member : steps.get(step).entrySet())
                    series(written, container(prefix, step, member.getKey()), member.getKey()).put(tick, member.getValue());
            for (Map.Entry<String, Map<PoseChannel, Float>> bone : writes.bones().entrySet())
                for (Map.Entry<PoseChannel, Float> member : bone.getValue().entrySet())
                    series(written, bone(prefix, bone.getKey(), member.getKey()), member.getKey()).put(tick, member.getValue());

            // A displacement of the container folds onto the innermost step the pose writes, the
            // only step there is where it writes none.
            String innermost = prefix + "." + CONTAINER + Math.max(0, steps.size() - 1) + ".";
            List<ClipPlayer.SitePlay> plays = ClipPlayer.siteDeltas(pose, mesh, frame);
            for (int index = 0; index < plays.size(); index++)
                sites.get(index).put(tick, plays.get(index), prefix, innermost);
        }

        for (Map.Entry<String, Series> series : written.entrySet())
            series.getValue().track(series.getKey(), new StripTrack.Role.Expression(), tracks);
        for (Site site : sites)
            site.tracks(tracks);
    }

    /**
     * Samples each axis one pass scrolls its sheet along.
     *
     * @param prefix the pass's name in the channels it contributes
     * @param pass the pass
     * @param tracks the tracks sampled so far, added to
     */
    private static void scrolls(@NotNull String prefix, @NotNull Entity.OverlayLayer pass, @NotNull List<StripTrack> tracks) {
        if (pass.textureScroll().isEmpty()) return;
        Vector2f rate = pass.textureScroll().get();
        int width = pass.model().getTextureWidth();
        int height = pass.model().getTextureHeight();
        double[] across = new double[StripFit.WINDOW_TICKS];
        double[] down = new double[StripFit.WINDOW_TICKS];
        for (int tick = 0; tick < StripFit.WINDOW_TICKS; tick++) {
            Vector2f offset = pass.textureOffsetAt(tick).orElseThrow();
            across[tick] = offset.x() * width;
            down[tick] = offset.y() * height;
        }
        if (rate.x() != 0f) {
            moving(new StripTrack(prefix + ".scroll.u", StripTrack.Kind.TEXEL,
                new StripTrack.Role.Looping(1d / Math.abs(rate.x())), across, OptionalDouble.of(width)), tracks);
        }
        if (rate.y() != 0f) {
            moving(new StripTrack(prefix + ".scroll.v", StripTrack.Kind.TEXEL,
                new StripTrack.Role.Looping(1d / Math.abs(rate.y())), down, OptionalDouble.of(height)), tracks);
        }
    }

    /**
     * Samples the opacity one pass draws at on each frame.
     *
     * @param prefix the pass's name in the channel it contributes
     * @param opacity the pass's opacity expression
     * @param mesh the pass's mesh, which a channel read in the expression is answered from
     * @param frames the row's frame at each tick of the window
     * @param tracks the tracks sampled so far, added to
     */
    private static void opacity(
        @NotNull String prefix, @NotNull PoseExpr opacity, @NotNull EntityMesh mesh,
        @NotNull List<ToDoubleFunction<String>> frames, @NotNull List<StripTrack> tracks) {

        double[] values = new double[StripFit.WINDOW_TICKS];
        for (int tick = 0; tick < StripFit.WINDOW_TICKS; tick++)
            values[tick] = PosePlayer.opacityAt(opacity, mesh, frames.get(tick));
        moving(new StripTrack(prefix + ".opacity", StripTrack.Kind.OPACITY, new StripTrack.Role.Expression(),
            values, OptionalDouble.empty()), tracks);
    }

    /** Spells one bone member's channel name. */
    private static @NotNull String bone(@NotNull String prefix, @NotNull String bone, @NotNull PoseChannel member) {
        return prefix + "." + bone + "." + member.token();
    }

    /** Spells one container step member's channel name. */
    private static @NotNull String container(@NotNull String prefix, int step, @NotNull PoseChannel member) {
        return prefix + "." + CONTAINER + step + "." + member.token();
    }

    /** Finds the series one channel samples into, opening it on its first sample. */
    private static @NotNull Series series(
        @NotNull Map<String, Series> sampled, @NotNull String channel, @NotNull PoseChannel member) {

        return sampled.computeIfAbsent(channel, key -> new Series(member));
    }

    /** Adds a track where its samples change over the window. */
    private static void moving(@NotNull StripTrack track, @NotNull List<StripTrack> tracks) {
        double[] values = track.values();
        for (int tick = 1; tick < values.length; tick++) {
            if (values[tick] != values[0]) {
                tracks.add(track);
                return;
            }
        }
    }

    /**
     * One member's samples across the window, in the unit its kind is fitted in.
     */
    private static final class Series {

        /** The member sampled. */
        private final @NotNull PoseChannel member;

        /** The member's value at each tick. */
        private final double @NotNull [] values = new double[StripFit.WINDOW_TICKS];

        /**
         * Opens the series of one member.
         *
         * @param member the member sampled
         */
        Series(@NotNull PoseChannel member) {
            this.member = member;
        }

        /**
         * Records the member's value at one tick, a rotation turned from radians to degrees.
         *
         * @param tick the tick
         * @param value the value the pose or clip answers, in the table's units
         */
        void put(int tick, float value) {
            this.values[tick] = this.member.kind() == PoseChannel.Kind.ROTATION ? Math.toDegrees(value) : value;
        }

        /**
         * Adds the series as one track where it moves.
         *
         * @param channel the channel the track contributes to
         * @param role where the contribution's period comes from
         * @param tracks the tracks sampled so far, added to
         */
        void track(@NotNull String channel, @NotNull StripTrack.Role role, @NotNull List<StripTrack> tracks) {
            moving(new StripTrack(channel, kindOf(this.member), role, this.values, wrapOf(this.member)), tracks);
        }

        /** Reads the fit's kind for a pose member. */
        private static @NotNull StripTrack.Kind kindOf(@NotNull PoseChannel member) {
            return switch (member.kind()) {
                case POSITION -> StripTrack.Kind.POSITION;
                case ROTATION -> StripTrack.Kind.ROTATION;
                case SCALE -> StripTrack.Kind.SCALE;
            };
        }

        /** Reads the span a pose member comes round at - a full turn for a rotation, nowhere otherwise. */
        private static @NotNull OptionalDouble wrapOf(@NotNull PoseChannel member) {
            return member.kind() == PoseChannel.Kind.ROTATION ? OptionalDouble.of(FULL_TURN) : OptionalDouble.empty();
        }

    }

    /**
     * One clip site's displacements across the window, and the clip clock it read at the ticks it
     * played.
     */
    private static final class Site {

        /** The play site. */
        private final @NotNull EntityPose.Clip clip;

        /** What the site displaces, by the channel each member contributes to. */
        private final @NotNull Map<String, Series> displaced = new LinkedHashMap<>();

        /** The first tick the site played at, or minus one before it has. */
        private int firstTick = -1;

        /** The clip clock at the first tick the site played at. */
        private long firstMillis;

        /** The last tick the site played at. */
        private int lastTick;

        /** The clip clock at the last tick the site played at. */
        private long lastMillis;

        /**
         * Opens the samples of one play site.
         *
         * @param clip the play site
         */
        Site(@NotNull EntityPose.Clip clip) {
            this.clip = clip;
        }

        /**
         * Records what the site answers at one tick.
         *
         * @param tick the tick
         * @param play the site's answer there
         * @param prefix the mesh's name in the channels it contributes
         * @param innermost the channel prefix of the step a container displacement folds onto
         */
        void put(int tick, @NotNull ClipPlayer.SitePlay play, @NotNull String prefix, @NotNull String innermost) {
            OptionalLong millis = play.millis();
            if (millis.isEmpty()) return;
            if (this.firstTick < 0) {
                this.firstTick = tick;
                this.firstMillis = millis.getAsLong();
            }
            this.lastTick = tick;
            this.lastMillis = millis.getAsLong();

            for (Map.Entry<String, Map<PoseChannel, Float>> bone : play.displacement().bones().entrySet())
                for (Map.Entry<PoseChannel, Float> member : bone.getValue().entrySet()) {
                    String channel = bone(prefix, bone.getKey(), member.getKey());
                    series(this.displaced, channel, member.getKey()).put(tick, member.getValue());
                }
            for (Map.Entry<PoseChannel, Float> member : play.displacement().container().entrySet())
                series(this.displaced, innermost + member.getKey().token(), member.getKey()).put(tick, member.getValue());
        }

        /**
         * Adds what the site displaces, one track per member, where its clock moves while it plays.
         *
         * @param tracks the tracks sampled so far, added to
         */
        void tracks(@NotNull List<StripTrack> tracks) {
            if (this.firstTick < 0 || this.lastTick == this.firstTick) return;
            double slope = (double) (this.lastMillis - this.firstMillis) / (this.lastTick - this.firstTick);
            if (slope == 0d) return;

            double lengthMillis = this.clip.clip().lengthSeconds() * MILLIS_PER_SECOND;
            double endMillis = slope > 0d ? lengthMillis : 0d;
            StripTrack.Role role = this.clip.clip().looping()
                ? new StripTrack.Role.Looping(lengthMillis / Math.abs(slope))
                : new StripTrack.Role.OneShot((endMillis - this.firstMillis) / slope + this.firstTick);
            for (Map.Entry<String, Series> series : this.displaced.entrySet())
                series.getValue().track(series.getKey(), role, tracks);
        }

    }

}
