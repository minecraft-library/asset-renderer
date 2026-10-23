package lib.minecraft.renderer.author.audit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PaceCeiling;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.compile.FormWalker;
import lib.minecraft.renderer.author.compile.GraphInterner;
import lib.minecraft.renderer.author.compile.PoseCompiler;
import lib.minecraft.renderer.author.mesh.Seats;
import lib.minecraft.renderer.bake.mesh.BoneKit;
import lib.minecraft.renderer.bake.pose.PosePlayer;
import lib.minecraft.renderer.bake.pose.PoseStrips;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.frame.StripFit;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.exception.StyleException;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * Validates a built style against a target row's known-good motion - the shipped bind, idle and
 * stride styles define a clearance envelope for every pair of bones that sit adjacent at bind,
 * and a custom pose that tears a pair outside that envelope has usually forgotten a coupling
 * vanilla carries outside the pose table: a tail seat that follows a pitched body, a snout that
 * rides its head's neck assembly.
 *
 * <p>The audit compiles the style at every site a strict install of it on the row compiles,
 * evaluates the row's woven pose across its strip through the same kit the renderer uses,
 * measures each adjacent pair's world-space clearance, and reports every pair whose excursion
 * leaves the shipped envelope by more than a margin scaled to how much that pair already moves
 * under shipped motion - a tight pair (a socket) is held tight, a free pair (a striding arm) keeps
 * its swing room.
 *
 * <p><b>The envelope is what the shipped table draws of the subject and what its seats
 * reproduce</b> - the bind and idle styles across their strips, the stride across its strip at
 * the fastest pace vanilla walks the subject at the audited age, and each state silhouette that
 * witnesses a seat the row derives, which is a placement vanilla makes by hand of parts that move
 * together. A sitting wolf's body and mane interpenetrate by the amount vanilla sat them at, and a
 * pose landing inside that is a contact vanilla draws, not a coupling the author forgot. A
 * silhouette that witnesses no seat places its parts by amounts no frame explains and widens
 * nothing: a humanoid's crouch, its attack swing and its arm poses each bury a limb in the torso by
 * their own measure, and an arm an author drives into the torso is read against the stride rather
 * than against them.
 *
 * <p>The audited age is the one the style is compiled at - the adult where the style spans both -
 * and the idle and stride are the shipped rows applying to it. A stride recording no fastest pace
 * enters the envelope at the walk it drives. A style an install appends as an adult row and a baby
 * row is audited once per row, each walking at its own age's stride against the envelope at that
 * age, and each of its findings names the age it was measured for.
 */
@UtilityClass
@Parity(subject = Subject.ENTITY)
public final class PoseAuditor {

    /**
     * The bind clearance under which two bones count as adjacent, in model pixels.
     */
    private static final float ADJACENT_PX = 2.0f;

    /**
     * The floor of the allowed excursion beyond the shipped envelope, in model pixels.
     */
    private static final float MARGIN_FLOOR_PX = 1.5f;

    /**
     * The share of a pair's shipped clearance range added to the margin - a pair that already
     * travels keeps proportional room.
     */
    private static final float MARGIN_SHARE = 0.35f;

    /**
     * A style row driving nothing, under which a pose evaluates with every figure resting.
     */
    private static final @NotNull PoseStyle STILL = new PoseStyle("silhouette",
        Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableMap(Map.of()),
        Concurrent.newUnmodifiableList(), Optional.empty(), Optional.empty());

    /**
     * Validates a built style against one target row - predicts what a strict install of it on the
     * row would refuse over, evaluates the woven pose across its strip, and reports every
     * bind-adjacent bone pair whose clearance leaves the envelope the shipped styles define.
     *
     * <p>The audit PREDICTS an install on the row rather than measuring a fit against the body. It
     * walks every site a strict install compiles or guards - the row's body and passes, the baby
     * wherever the style's age admits one, each coat and each coat's baby, the passes of each shape
     * form, and each size form - and compiles each against the evidence the install reads, so what
     * it reports as unreached is exactly what a strict install's weave refuses over. A pass sharing
     * its body's pose follows the body uncompiled, a pass no written bone lands on is left
     * untouched, and a size form lending a mesh resting as its row's does to its row's pose filters
     * a write to a bone it lacks at render, so none of the three reports anything. The clearance is
     * measured on the row's own body - twice where the install appends an adult row and a baby row,
     * the adult's first, each posed by its own row's drivers.
     *
     * <p>What it predicts is the weave. An install refuses ahead of any weave where the row already
     * carries the style's id at its age, and that guard is left to the caller, so a style spelled
     * like a shipped one audits against the row it is being compared with.
     *
     * @param style the built style to audit
     * @param row the shipped row the style would install on
     * @return the audit
     * @throws StyleException if a tolerant install's weave of the style onto the row would
     *     refuse - a lowering rule refusing at any site the install compiles, a scale a shipped clip
     *     already writes, a raw read of a bone a mesh evaluating the woven row does not declare, or a
     *     malformed selection site
     */
    public static @NotNull PoseAudit validate(@NotNull BuiltStyle style, @NotNull Entity row) {
        Prediction prediction = predict(style, row);
        PoseCompiler.Compiled compiled = prediction.body();
        EntityMesh mesh = row.model();

        // A container step moves the whole figure rigidly, which cannot break a bone coupling
        // but does shift every pair's axis-aligned clearance - so the audit samples the woven
        // bones over the SHIPPED container list, keeping the measure coupling-only.
        EntityPose sampled = new EntityPose(row.pose().container(), compiled.pose().bones(),
            compiled.pose().clips(), compiled.pose().refusal(), compiled.pose().states());

        Map<String, List<OrientedBox>> bind = worldBoxes(mesh);
        List<String> boneOrder = List.copyOf(bind.keySet());
        List<String[]> pairs = adjacentPairs(bind, boneOrder);
        Set<String> stanced = carried(mesh, stancedBones(row.pose(), compiled, style.styleId()));

        List<PoseAudit.Finding> findings = new ArrayList<>();
        if (PoseCompiler.installsAsPair(style, row)) {
            // The pair's two rows pose the one woven body, each walking at its own age's stride.
            String entityId = row.id().toString();
            PoseStyle adult = compiled.style();
            PoseStyle baby = new PoseStyle(adult.id(), adult.sources(),
                PoseCompiler.restrided(adult.drivers(), PoseCompiler.strideOf(row.styles(), Age.ADULT, entityId),
                    PoseCompiler.strideOf(row.styles(), Age.BABY, entityId)),
                adult.toggles(), Optional.of(Age.BABY), adult.periodTicks());
            findings.addAll(measured(row, sampled, bind, pairs, stanced, adult, Age.ADULT, Optional.of(Age.ADULT)));
            findings.addAll(measured(row, sampled, bind, pairs, stanced, baby, Age.BABY, Optional.of(Age.BABY)));
        } else {
            findings.addAll(measured(row, sampled, bind, pairs, stanced, compiled.style(), auditedAge(style),
                Optional.empty()));
        }

        return new PoseAudit(style.styleId(), row.id().toString(), pairs.size(),
            prediction.drops(), Concurrent.newUnmodifiableList(findings));
    }

    /**
     * The findings of one installed row - the woven body posed by the row's drivers across the
     * style's strip, measured against the envelope the shipped styles define at one age.
     *
     * @param row the shipped row the style would install on
     * @param sampled the woven bones over the shipped container list
     * @param bind each cube-bearing bone's oriented cubes at bind
     * @param pairs the bone pairs adjacent at bind
     * @param stanced the bones the style moves, and every bone they carry
     * @param installed the installed row, whose drivers pose the woven body
     * @param age the age whose shipped styles define the envelope
     * @param recorded the age each finding records - empty where the install appends one row
     * @return the row's findings, in mesh order
     */
    private static @NotNull List<PoseAudit.Finding> measured(
        @NotNull Entity row, @NotNull EntityPose sampled, @NotNull Map<String, List<OrientedBox>> bind,
        @NotNull List<String[]> pairs, @NotNull Set<String> stanced, @NotNull PoseStyle installed,
        @NotNull Age age, @NotNull Optional<Age> recorded) {

        EntityMesh mesh = row.model();
        int catalogPeriod = row.styles().periodTicks();

        Map<String, float[]> known = new LinkedHashMap<>();
        for (String[] pair : pairs) {
            float at = clearance(bind.get(pair[0]), bind.get(pair[1]));
            known.put(key(pair), new float[]{at, at});
        }
        for (PoseStyle shipped : knownStyles(row.styles(), age))
            for (int tick : ticks(row, shipped, catalogPeriod))
                widen(known, pairs, worldBoxes(PosePlayer.posed(row.pose(), mesh, shipped, catalogPeriod, tick)));
        for (String state : Seats.derive(row.pose(), mesh).witnesses()) {
            EntityPose.Silhouette silhouette = row.pose().states().get(state);
            widen(known, pairs, worldBoxes(PosePlayer.posed(placedBy(row.pose(), silhouette), mesh, STILL, catalogPeriod, 0)));
        }

        Map<String, float[]> posed = new LinkedHashMap<>();
        Map<String, int[]> worst = new LinkedHashMap<>();
        for (int tick : ticks(row.mutate().pose(sampled).build(), installed, catalogPeriod)) {
            Map<String, List<OrientedBox>> boxes = worldBoxes(PosePlayer.posed(sampled, mesh, installed, catalogPeriod, tick));
            for (String[] pair : pairs) {
                List<OrientedBox> a = boxes.get(pair[0]);
                List<OrientedBox> b = boxes.get(pair[1]);
                if (a == null || b == null) continue;
                float at = clearance(a, b);
                float[] range = posed.computeIfAbsent(key(pair), unused -> new float[]{at, at});
                int[] extremes = worst.computeIfAbsent(key(pair), unused -> new int[]{tick, tick});
                if (at < range[0]) { range[0] = at; extremes[0] = tick; }
                if (at > range[1]) { range[1] = at; extremes[1] = tick; }
            }
        }

        List<PoseAudit.Finding> findings = new ArrayList<>();
        for (String[] pair : pairs) {
            float[] envelope = known.get(key(pair));
            float[] range = posed.get(key(pair));
            if (range == null) continue;
            float margin = Math.max(MARGIN_FLOOR_PX, MARGIN_SHARE * (envelope[1] - envelope[0]));
            float bindAt = clearance(bind.get(pair[0]), bind.get(pair[1]));
            Optional<String> parent = sharedParent(mesh, pair[0], pair[1]);
            boolean aStanced = stanced.contains(pair[0]);
            boolean bStanced = stanced.contains(pair[1]);

            if (range[1] > envelope[1] + margin)
                findings.add(new PoseAudit.Finding(PoseAudit.Kind.SPLIT, pair[0], pair[1], bindAt,
                    envelope[0], envelope[1], range[0], range[1], worst.get(key(pair))[1],
                    aStanced, bStanced, parent, recorded));
            if (range[0] < envelope[0] - margin)
                findings.add(new PoseAudit.Finding(PoseAudit.Kind.OVERLAP, pair[0], pair[1], bindAt,
                    envelope[0], envelope[1], range[0], range[1], worst.get(key(pair))[0],
                    aStanced, bStanced, parent, recorded));
        }
        return findings;
    }

    /**
     * Walks every site a strict install of the style on the row compiles or guards, compiling each as
     * the install does over one pool of its own, and answers the row's body compile with every address
     * a strict install would refuse over.
     *
     * <p>An address counts where the install compiles and a strict one refuses: the row's body, a baby,
     * a coat, a size form weaving a pose or rests of its own, and every pass carrying a distinct pose
     * row that a written bone lands on. A pass sharing its form's pose is re-pointed rather than
     * compiled, a distinct pass nothing lands on is skipped, and a size form lending a mesh resting
     * as its row's does to its row's pose is guarded rather than compiled - none of them refuses
     * over an address, so none reports one. The guard's own refusals throw here as they throw from
     * the install.
     *
     * @param style the built style being audited
     * @param row the shipped row the style would install on
     * @return the row's body compile and the unreached addresses, in first-written order and each
     *     recorded once
     * @throws StyleException if a tolerant install's weave of the style onto the row would
     *     refuse
     */
    private static @NotNull Prediction predict(@NotNull BuiltStyle style, @NotNull Entity row) {
        Prediction prediction = new Prediction(style, row.styles().periodTicks(), PoseCompiler.strideOf(style, row));
        FormWalker.walk(row.id().toString(), style, row, row,
            Diagnostics.root("audit", Diagnostics.Output.NONE, null)
                .child(row.id().toString()).child(style.styleId()),
            prediction);
        return prediction;
    }

    /**
     * What an audit does at each site the walk compiles - compiles it with the install's own
     * arguments over one pool of its own, keeps the row's body compile for the clearance measure,
     * and keeps every address a compile reached nothing with rather than refusing over it.
     */
    private static final class Prediction implements FormWalker.Visitor {

        /**
         * The built style being audited.
         */
        private final @NotNull BuiltStyle style;

        /**
         * The row's catalog period, the figure period every compile frames at.
         */
        private final int periodTicks;

        /**
         * The stride the install keeps, at the age the style is compiled at - the one the row's
         * compile reads, handed to every layer and form compile beside it.
         */
        private final @NotNull ConcurrentMap<String, StyleDriver> stride;

        /**
         * The interner pool every compile of the audit shares, as an install's share the entity's.
         */
        private final @NotNull GraphInterner pool = new GraphInterner();

        /**
         * Every address a compile reached nothing with, in first-written order and each once.
         */
        private final @NotNull Set<PoseCompiler.Unreached> drops = new LinkedHashSet<>();

        /**
         * The row's own body compile, empty until the walk's first compile answers.
         */
        private @NotNull Optional<PoseCompiler.Compiled> body = Optional.empty();

        private Prediction(@NotNull BuiltStyle style, int periodTicks,
                           @NotNull ConcurrentMap<String, StyleDriver> stride) {
            this.style = style;
            this.periodTicks = periodTicks;
            this.stride = stride;
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull PoseCompiler.Compiled compile(@NotNull FormWalker.Site site,
                                                      @NotNull Optional<EntityPose.Clip> playSite) {
            PoseCompiler.Compiled compiled = site.coordinate().isEmpty()
                ? PoseCompiler.compile(this.style, site.form(), site.evidence(), site.scope(), this.pool)
                : PoseCompiler.compileLayer(this.style, site.pose(), site.evidence(), site.mesh(),
                    site.coordinate(), site.scope(), this.pool, playSite, this.periodTicks, this.stride);
            // The row's body is the one site spelled under no coordinate, and the walk's first.
            if (site.coordinate().isEmpty())
                this.body = Optional.of(compiled);
            return compiled;
        }

        /** {@inheritDoc} */
        @Override
        public void unreached(@NotNull FormWalker.Site site, @NotNull ConcurrentList<PoseCompiler.Unreached> drops) {
            this.drops.addAll(drops);
        }

        /**
         * Answers the row's own body compile, the walk's first.
         *
         * @return the body compile
         */
        @NotNull PoseCompiler.Compiled body() {
            return this.body.orElseThrow();
        }

        /**
         * Answers every address a strict install would refuse over, in first-written order and each
         * recorded once.
         *
         * @return the unreached addresses
         */
        @NotNull ConcurrentList<PoseCompiler.Unreached> drops() {
            return Concurrent.newUnmodifiableList(this.drops);
        }

    }

    /**
     * The shipped pose standing in one state - its bones with the silhouette's channels written
     * over them, no clip playing, so what the state places evaluates with every figure resting.
     */
    private static @NotNull EntityPose placedBy(@NotNull EntityPose shipped, @NotNull EntityPose.Silhouette silhouette) {
        Map<String, Map<PoseChannel, PoseExpr>> bones = new LinkedHashMap<>(shipped.bones());
        silhouette.bones().forEach((bone, channels) -> {
            Map<PoseChannel, PoseExpr> placed = new EnumMap<>(PoseChannel.class);
            placed.putAll(bones.getOrDefault(bone, Map.of()));
            placed.putAll(channels);
            bones.put(bone, placed);
        });
        return new EntityPose(shipped.container(), Concurrent.newUnmodifiableMap(bones),
            Concurrent.newUnmodifiableList(), shipped.refusal());
    }

    /**
     * The age a one-row install is audited at, reading its known-good envelope there - the age the
     * style is compiled at, and the adult where the style spans both ages. A pair install never
     * reads it: each of its rows is audited at its own age, the baby row at the baby's.
     *
     * @param style the style being audited
     * @return the audited age
     */
    static @NotNull Age auditedAge(@NotNull BuiltStyle style) {
        return style.age().orElse(Age.ADULT);
    }

    /**
     * The shipped styles whose motion defines the known-good envelope at one age - bind always, then
     * the idle and the stride where the catalog carries them, each the row a render at that age
     * draws, the stride at its {@link #atCeiling ceiling}.
     *
     * @param catalog the shipped catalog of the row being audited
     * @param age the age the audited row applies to
     * @return the known styles, bind first
     */
    static @NotNull List<PoseStyle> knownStyles(@NotNull StyleCatalog catalog, @NotNull Age age) {
        Predicate<PoseStyle> applies = row -> row.age().map(age::equals).orElse(true);
        List<PoseStyle> known = new ArrayList<>();
        known.add(StyleCatalog.bind());
        drawnAt(catalog, PoseStyle.IDLE, applies).ifPresent(known::add);
        drawnAt(catalog, PoseStyle.STRIDE, applies).map(PoseAuditor::atCeiling).ifPresent(known::add);
        return known;
    }

    /**
     * The row of one universal id a render at the audited age draws, where the catalog carries that
     * id at all - the row applying to the age, or the universal row where every row of the id is
     * scoped to the other age.
     */
    private static @NotNull Optional<PoseStyle> drawnAt(
        @NotNull StyleCatalog catalog, @NotNull String id, @NotNull Predicate<PoseStyle> applies) {

        if (catalog.byId(id).isEmpty()) return Optional.empty();
        return Optional.of(catalog.resolve(id, applies, id));
    }

    /**
     * Answers a stride row walking at its subject's fastest vanilla pace - its swing and walk clock
     * raised to its ceiling, every other driver, and its sources, toggles, age, period and ceiling,
     * kept; the row itself where it carries no ceiling.
     *
     * @param stride a shipped stride row
     * @return the row at its ceiling
     */
    static @NotNull PoseStyle atCeiling(@NotNull PoseStyle stride) {
        if (stride.ceiling().isEmpty()) return stride;
        PaceCeiling ceiling = stride.ceiling().get();
        LinkedHashMap<String, StyleDriver> drivers = new LinkedHashMap<>(stride.drivers());
        drivers.computeIfPresent("walkAnimationSpeed", (field, held) -> at(held, ceiling.speed()));
        drivers.computeIfPresent("walkAnimationPos", (field, held) -> at(held, ceiling.rate()));
        return new PoseStyle(stride.id(), stride.sources(), Concurrent.adoptLinkedMap(drivers).toUnmodifiable(),
            stride.toggles(), stride.age(), stride.periodTicks(), stride.ceiling());
    }

    /**
     * One walk driver at another extent - its wave, rest, group and timing kept.
     */
    private static @NotNull StyleDriver at(@NotNull StyleDriver held, float extent) {
        return new StyleDriver(held.field(), held.wave(), held.rest(), extent, held.group(), held.timing());
    }

    /**
     * The ticks a style is sampled at - the strip a render of it draws, tick zero alone for a
     * statue.
     */
    private static int @NotNull [] ticks(@NotNull Entity subject, @NotNull PoseStyle style, int catalogPeriod) {
        StripFit strip = PoseStrips.of(subject, style, catalogPeriod);
        return IntStream.range(0, strip.frames()).map(frame -> frame * strip.ticksPerFrame()).toArray();
    }

    /**
     * The bones the compiled style moves - every bone whose woven channel is not the shipped
     * instance, plus every bone the style's own clip tracks.
     */
    private static @NotNull Set<String> stancedBones(
        @NotNull EntityPose shipped,
        @NotNull PoseCompiler.Compiled compiled,
        @NotNull String styleId
    ) {
        Set<String> stanced = new LinkedHashSet<>();
        String gate = "style$" + styleId;

        compiled.pose().bones().forEach((bone, channels) -> {
            Map<PoseChannel, PoseExpr> before = shipped.bones().get(bone);
            channels.forEach((channel, expr) -> {
                if (before == null || before.get(channel) != expr)
                    stanced.add(bone);
            });
        });
        for (EntityPose.Clip site : compiled.pose().clips())
            if (site.field().filter(gate::equals).isPresent())
                for (PoseClip.Channel channel : site.clip().channels())
                    stanced.add(channel.bone());

        return stanced;
    }

    /**
     * Grows a directly-stanced set to every descendant a stanced bone carries - a child rides
     * its ancestor's chain, so it moved too.
     */
    private static @NotNull Set<String> carried(@NotNull EntityMesh mesh, @NotNull Set<String> direct) {
        Set<String> moved = new LinkedHashSet<>(direct);
        mesh.getBones().forEach((name, bone) -> {
            String ancestor = bone.getParent();
            while (ancestor != null && !moved.contains(name)) {
                if (direct.contains(ancestor)) moved.add(name);
                EntityMesh.Bone up = mesh.getBones().get(ancestor);
                ancestor = up == null || ancestor.equals(up.getParent()) ? null : up.getParent();
            }
        });
        return moved;
    }

    /**
     * One cube as an oriented box in the working frame - the transformed corner anchoring it,
     * its three edge vectors, and the derived unit axes with half-extents. Oriented rather
     * than axis-aligned on purpose: two boxes rotated together keep their true clearance,
     * where an axis-aligned wrap inflates under rotation and reads as fictitious overlap.
     *
     * @param center the box centre in the working frame
     * @param axes the three unit edge directions
     * @param half the three half-extents along the axes
     */
    private record OrientedBox(float @NotNull [] center, float @NotNull [][] axes, float @NotNull [] half) {

        /**
         * Builds the oriented box from a cube's transformed corners.
         */
        static @NotNull OrientedBox of(@NotNull Box local, @NotNull Matrix4f transform) {
            float[][] corner = new float[8][];
            for (int index = 0; index < 8; index++) {
                Vector3f point = new Vector3f(
                    (index & 1) == 0 ? local.minX() : local.maxX(),
                    (index & 2) == 0 ? local.minY() : local.maxY(),
                    (index & 4) == 0 ? local.minZ() : local.maxZ()
                ).transform(transform);
                corner[index] = new float[]{point.x(), point.y(), point.z()};
            }

            float[][] axes = new float[3][3];
            float[] half = new float[3];
            int[] edgeEnds = {1, 2, 4};
            for (int axis = 0; axis < 3; axis++) {
                float[] edge = subtract(corner[edgeEnds[axis]], corner[0]);
                float length = (float) Math.sqrt(dot(edge, edge));
                half[axis] = length / 2;
                axes[axis] = length == 0 ? new float[]{0, 0, 0}
                    : new float[]{edge[0] / length, edge[1] / length, edge[2] / length};
            }
            float[] center = {
                (corner[0][0] + corner[7][0]) / 2,
                (corner[0][1] + corner[7][1]) / 2,
                (corner[0][2] + corner[7][2]) / 2
            };
            return new OrientedBox(center, axes, half);
        }

    }

    /**
     * Each cube-bearing bone's oriented cubes under the given mesh state, keyed in mesh order.
     */
    private static @NotNull Map<String, List<OrientedBox>> worldBoxes(@NotNull EntityMesh model) {
        Map<String, Matrix4f> chains = BoneKit.buildChainTransforms(model.getBones());
        Map<String, List<OrientedBox>> out = new LinkedHashMap<>();

        model.getBones().forEach((name, bone) -> {
            if (bone.getCubes().isEmpty()) return;
            Matrix4f chain = chains.get(name);
            List<OrientedBox> cubes = new ArrayList<>(bone.getCubes().size());
            for (EntityMesh.Cube cube : bone.getCubes())
                cubes.add(OrientedBox.of(
                    BoneKit.scaledCubeBounds(bone.getScale(), cube),
                    BoneKit.composeCubeTransform(cube, bone, chain)));
            out.put(name, cubes);
        });

        return out;
    }

    /**
     * The bone pairs adjacent at bind, in mesh order.
     */
    private static @NotNull List<String[]> adjacentPairs(
        @NotNull Map<String, List<OrientedBox>> bind, @NotNull List<String> order) {

        List<String[]> pairs = new ArrayList<>();
        for (int i = 0; i < order.size(); i++)
            for (int j = i + 1; j < order.size(); j++)
                if (clearance(bind.get(order.get(i)), bind.get(order.get(j))) <= ADJACENT_PX)
                    pairs.add(new String[]{order.get(i), order.get(j)});
        return pairs;
    }

    /**
     * The signed clearance between two bones' cube sets - the closest cube pair decides.
     */
    private static float clearance(@NotNull List<OrientedBox> a, @NotNull List<OrientedBox> b) {
        float closest = Float.POSITIVE_INFINITY;
        for (OrientedBox cubeA : a)
            for (OrientedBox cubeB : b)
                closest = Math.min(closest, clearance(cubeA, cubeB));
        return closest;
    }

    /**
     * The signed clearance between two oriented boxes by separating axes - the widest
     * separation any face or edge-cross axis measures when apart, the negated least
     * penetration when every axis overlaps.
     */
    private static float clearance(@NotNull OrientedBox a, @NotNull OrientedBox b) {
        float[] between = subtract(b.center(), a.center());
        float widest = Float.NEGATIVE_INFINITY;

        for (int index = 0; index < 15; index++) {
            float[] axis;
            if (index < 3) axis = a.axes()[index];
            else if (index < 6) axis = b.axes()[index - 3];
            else axis = cross(a.axes()[(index - 6) / 3], b.axes()[(index - 6) % 3]);

            float length = (float) Math.sqrt(dot(axis, axis));
            if (length < 1e-6f) continue;
            float[] unit = {axis[0] / length, axis[1] / length, axis[2] / length};

            float reachA = 0, reachB = 0;
            for (int edge = 0; edge < 3; edge++) {
                reachA += a.half()[edge] * Math.abs(dot(a.axes()[edge], unit));
                reachB += b.half()[edge] * Math.abs(dot(b.axes()[edge], unit));
            }
            widest = Math.max(widest, Math.abs(dot(between, unit)) - reachA - reachB);
        }
        return widest;
    }

    /**
     * The component difference of two points.
     */
    private static float @NotNull [] subtract(float @NotNull [] a, float @NotNull [] b) {
        return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    /**
     * The dot product of two vectors.
     */
    private static float dot(float @NotNull [] a, float @NotNull [] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /**
     * The cross product of two vectors.
     */
    private static float @NotNull [] cross(float @NotNull [] a, float @NotNull [] b) {
        return new float[]{
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        };
    }

    /**
     * Widens each pair's envelope with the clearances one posed mesh state measures.
     */
    private static void widen(
        @NotNull Map<String, float[]> envelopes,
        @NotNull List<String[]> pairs,
        @NotNull Map<String, List<OrientedBox>> boxes
    ) {
        for (String[] pair : pairs) {
            List<OrientedBox> a = boxes.get(pair[0]);
            List<OrientedBox> b = boxes.get(pair[1]);
            if (a == null || b == null) continue;
            float at = clearance(a, b);
            float[] range = envelopes.get(key(pair));
            range[0] = Math.min(range[0], at);
            range[1] = Math.max(range[1], at);
        }
    }

    /**
     * The parent both bones hang under, when they hang under one.
     */
    private static @NotNull Optional<String> sharedParent(
        @NotNull EntityMesh mesh, @NotNull String a, @NotNull String b) {

        EntityMesh.Bone boneA = mesh.getBones().get(a);
        EntityMesh.Bone boneB = mesh.getBones().get(b);
        if (boneA == null || boneB == null) return Optional.empty();
        String parentA = boneA.getParent();

        return parentA != null && parentA.equals(boneB.getParent()) ? Optional.of(parentA) : Optional.empty();
    }

    /**
     * One pair's envelope key.
     */
    private static @NotNull String key(String @NotNull [] pair) {
        return pair[0] + "|" + pair[1];
    }

}
