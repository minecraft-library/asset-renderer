package lib.minecraft.renderer.author.pose;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.engine.kit.BoneKit;
import lib.minecraft.renderer.engine.kit.PoseKit;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validates a built style against a target row's known-good motion - the shipped bind, idle and
 * stride styles define a clearance envelope for every pair of bones that sit adjacent at bind,
 * and a custom pose that tears a pair outside that envelope has usually forgotten a coupling
 * vanilla carries outside the pose table: a tail seat that follows a pitched body, a snout that
 * rides its head's neck assembly.
 *
 * <p>The audit compiles the style against the row, evaluates the woven pose across its period
 * through the same kit the renderer uses, measures each adjacent pair's world-space clearance,
 * and reports every pair whose excursion leaves the shipped envelope by more than a margin
 * scaled to how much that pair already moves under shipped motion - a tight pair (a socket) is
 * held tight, a free pair (a striding arm) keeps its swing room.
 */
public final class PoseValidator {

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

    private PoseValidator() {}

    /**
     * Audits a built style against one target row.
     *
     * @param style the built style to audit
     * @param row the shipped row the style would install on
     * @return the audit
     * @throws IllegalArgumentException if the style refuses to compile against the row
     */
    public static @NotNull PoseAudit audit(@NotNull BuiltStyle style, @NotNull Entity row) {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(style, row);
        EntityModelData mesh = row.model();
        int catalogPeriod = row.styles().periodTicks();

        // A container step moves the whole figure rigidly, which cannot break a bone coupling
        // but does shift every pair's axis-aligned clearance - so the audit samples the woven
        // bones over the SHIPPED container list, keeping the measure coupling-only.
        EntityPose sampled = new EntityPose(row.pose().container(), compiled.pose().bones(),
            compiled.pose().clips(), compiled.pose().refusal());

        Map<String, List<OrientedBox>> bind = worldBoxes(mesh);
        List<String> boneOrder = List.copyOf(bind.keySet());
        List<String[]> pairs = adjacentPairs(bind, boneOrder);

        Map<String, float[]> known = new LinkedHashMap<>();
        for (String[] pair : pairs) {
            float at = clearance(bind.get(pair[0]), bind.get(pair[1]));
            known.put(key(pair), new float[]{at, at});
        }
        for (PoseStyle shipped : knownStyles(row.styles()))
            for (int tick : ticks(shipped, catalogPeriod))
                widen(known, pairs, worldBoxes(PoseKit.posed(row.pose(), mesh, shipped, catalogPeriod, tick)));

        Set<String> stanced = carried(mesh, stancedBones(row.pose(), compiled, style.styleId()));

        Map<String, float[]> posed = new LinkedHashMap<>();
        Map<String, int[]> worst = new LinkedHashMap<>();
        for (int tick : ticks(compiled.style(), catalogPeriod)) {
            Map<String, List<OrientedBox>> boxes = worldBoxes(PoseKit.posed(sampled, mesh, compiled.style(), catalogPeriod, tick));
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
                    aStanced, bStanced, parent));
            if (range[0] < envelope[0] - margin)
                findings.add(new PoseAudit.Finding(PoseAudit.Kind.OVERLAP, pair[0], pair[1], bindAt,
                    envelope[0], envelope[1], range[0], range[1], worst.get(key(pair))[0],
                    aStanced, bStanced, parent));
        }

        return new PoseAudit(style.styleId(), row.id().toString(), pairs.size(),
            compiled.droppedBones(), Concurrent.newUnmodifiableList(findings));
    }

    /**
     * The shipped styles whose motion defines the known-good envelope - bind always, idle and
     * stride where the catalog carries them.
     */
    private static @NotNull List<PoseStyle> knownStyles(@NotNull StyleCatalog catalog) {
        List<PoseStyle> known = new ArrayList<>();
        known.add(catalog.bind());
        catalog.byId(PoseStyle.IDLE).ifPresent(known::add);
        catalog.byId(PoseStyle.STRIDE).ifPresent(known::add);
        return known;
    }

    /**
     * The ticks a style is sampled at - the strip's schedule across the style's own period for
     * a mover, tick zero alone for a statue.
     */
    private static int @NotNull [] ticks(@NotNull PoseStyle style, int catalogPeriod) {
        if (!style.moves()) return new int[]{0};
        int period = style.periodTicks().orElse(catalogPeriod);
        int[] ticks = new int[StyleCatalog.STRIP_FRAMES];
        for (int frame = 0; frame < ticks.length; frame++)
            ticks[frame] = frame * period / StyleCatalog.STRIP_FRAMES;
        return ticks;
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
    private static @NotNull Set<String> carried(@NotNull EntityModelData mesh, @NotNull Set<String> direct) {
        Set<String> moved = new LinkedHashSet<>(direct);
        mesh.getBones().forEach((name, bone) -> {
            String ancestor = bone.getParent();
            while (ancestor != null && !moved.contains(name)) {
                if (direct.contains(ancestor)) moved.add(name);
                EntityModelData.Bone up = mesh.getBones().get(ancestor);
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
    private static @NotNull Map<String, List<OrientedBox>> worldBoxes(@NotNull EntityModelData model) {
        Map<String, Matrix4f> chains = BoneKit.buildChainTransforms(model.getBones());
        Map<String, List<OrientedBox>> out = new LinkedHashMap<>();

        model.getBones().forEach((name, bone) -> {
            if (bone.getCubes().isEmpty()) return;
            Matrix4f chain = chains.get(name);
            List<OrientedBox> cubes = new ArrayList<>(bone.getCubes().size());
            for (EntityModelData.Cube cube : bone.getCubes())
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
        @NotNull EntityModelData mesh, @NotNull String a, @NotNull String b) {

        EntityModelData.Bone boneA = mesh.getBones().get(a);
        EntityModelData.Bone boneB = mesh.getBones().get(b);
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
