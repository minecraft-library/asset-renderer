package lib.minecraft.renderer.pose.compile;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.engine.kit.BoneKit;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.pose.author.LimbSelector;
import lib.minecraft.renderer.pose.author.Rank;
import lib.minecraft.renderer.pose.author.Side;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The legs a mesh declares, grouped into transverse rows front to back.
 *
 * <p>A leg is a bone the mesh NAMES as one - the vocabulary is a naming convention rather than an
 * anatomy. A turtle's and an axolotl's flippers are legs because their meshes call them legs, and a
 * dolphin's identically shaped {@code left_fin} is not; no geometric predicate separates the two,
 * and none is attempted. What geometry decides is where a named leg sits, never whether it is one.
 *
 * <p>Rows are ordinals rather than names. The spellings meshes use for them - {@code front},
 * {@code mid}, {@code middle_hind}, {@code back} - are read as a cross-check and never as the key,
 * because a vocabulary keyed on the token needs a table per family where one keyed on front-to-back
 * position needs none. No entity id appears anywhere in the resolution.
 *
 * @param rows the legs the mesh declares, grouped front to back
 * @param ambiguities what the resolution could not settle cleanly, each as a kind and the words a
 *     reader needs
 * @param crossed the legs whose name says one side and whose own geometry says the other, in
 *     roster order - a fact about the mesh that a caller keying on which side a leg is on has to
 *     be told, because the side a member carries is the name's and the pixels are elsewhere
 */
@Parity(subject = Subject.ENTITY)
public record LimbRoster(@NotNull ConcurrentList<Row> rows,
                         @NotNull ConcurrentList<Note> ambiguities,
                         @NotNull ConcurrentList<String> crossed) {

    /**
     * One thing the resolution could not settle cleanly.
     *
     * <p>The kind is what a reader matches on and the reading is what a reader reads. They are two
     * components rather than one because a note's sentence is written for a person and changes when
     * a better sentence is found, where what it is about does not - so a test keying on the kind
     * keeps biting across a reword, and a renamed kind is a compile error rather than silence.
     *
     * @param kind what the resolution could not settle
     * @param reading the note in the words a reader needs
     */
    public record Note(@NotNull Kind kind, @NotNull String reading) {

        /**
         * What a note is about.
         */
        public enum Kind {

            /** A leg root carrying neither a side token nor a side its own geometry resolves. */
            UNSIDED_ROOT,

            /** A leg named for one side and sitting on the other. */
            CROSSED,

            /** A row grouper holding fewer legs than a row takes. */
            THIN_GROUPER
        }
    }

    /** The side tokens a bone name is read for. */
    private static final @NotNull Set<String> SIDE_TOKENS = Set.of("left", "right");

    /** The share of a row's front-to-back spread two roots may differ by and still share a row. */
    private static final double ROW_TOLERANCE_SHARE = 0.10d;

    /** The smallest row tolerance, for a mesh whose roots carry no front-to-back spread at all. */
    private static final double ROW_TOLERANCE_FLOOR = 0.15d;

    /**
     * How far off the midline a fused row's own cube span may sit, as a share of its half-width.
     *
     * <p>Strictly inside the separating interval rather than on its edge: the worst candidate that
     * is not a fused row sits at exactly one third, so a threshold spelled as a third decides that
     * bone by rounding.
     */
    private static final double FUSED_CENTRE_SHARE = 0.25d;

    /**
     * What a leg-named bone is to the roster.
     */
    public enum Kind {

        /**
         * A leg's own bone, carrying the row and the side a caller addresses.
         */
        ROOT(true, 1),

        /**
         * One bone painting a whole row of legs, straddling the midline and carrying no side.
         */
        FUSED(true, 2),

        /**
         * A bone below a root, reached only where a stance asks for the chain.
         */
        SEGMENT(false, 0),

        /**
         * A cubeless bone holding a row's legs together, which is no leg itself.
         */
        GROUPER(false, 0);

        /** Whether a bone of this kind seats a row of its own rather than hanging below one. */
        private final boolean seat;

        /** How many legs a bone of this kind paints. */
        private final int legs;

        Kind(boolean seat, int legs) {
            this.seat = seat;
            this.legs = legs;
        }

        public boolean seat() {
            return this.seat;
        }

        public int legs() {
            return this.legs;
        }

    }

    /**
     * One transverse row of legs.
     *
     * @param ordinal where the row sits front to back, the frontmost at zero
     * @param members the row's legs, right before left
     */
    public record Row(int ordinal, @NotNull ConcurrentList<Member> members) {}

    /**
     * One leg the mesh declares, carrying where it sits among the legs.
     *
     * <p>The row is the ordinal of the {@link Row} holding this member, taken from the same loop
     * that builds it - so a member and the row it is listed under cannot disagree.
     *
     * @param bone the bone name, as the mesh names it
     * @param row where its row sits front to back, the frontmost at zero
     * @param side which side of its row, or empty where the row is one fused bone
     * @param depth how far below its row's root the bone sits, the root itself at zero
     * @param kind what the bone is to the roster
     */
    public record Member(@NotNull String bone, int row, @NotNull Optional<Side> side, int depth,
                         @NotNull Kind kind) {}

    /**
     * Resolves the legs a mesh declares.
     *
     * @param mesh the body mesh to read
     * @return the roster, holding no row where the mesh names no leg
     */
    public static @NotNull LimbRoster of(@NotNull EntityModelData mesh) {
        Map<String, EntityModelData.Bone> bones = mesh.getBones();
        List<String> legish = bones.keySet().stream().filter(LimbRoster::legish).toList();
        List<Note> notes = new ArrayList<>();
        List<String> crossed = new ArrayList<>();
        if (legish.isEmpty())
            return new LimbRoster(Concurrent.newUnmodifiableList(),
                Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList());

        Map<String, Matrix4f> chains = new LinkedHashMap<>(BoneKit.buildChainTransforms(bones));
        Set<String> legNames = new LinkedHashSet<>(legish);

        Map<String, Kind> kinds = new LinkedHashMap<>();
        for (String bone : legish)
            kinds.put(bone, classify(bone, bones, legNames, chains));

        List<String> seats = legish.stream()
            .filter(bone -> kinds.get(bone).seat())
            .sorted(Comparator.comparingDouble(bone -> accumulated(bone, chains).z()))
            .toList();

        List<List<String>> clustered = cluster(seats, chains);
        List<Row> rows = new ArrayList<>();
        for (int ordinal = 0; ordinal < clustered.size(); ordinal++) {
            List<Member> members = new ArrayList<>();
            for (String seat : clustered.get(ordinal)) {
                Optional<Side> side = kinds.get(seat) == Kind.FUSED
                    ? Optional.empty()
                    : sideOf(seat);
                if (kinds.get(seat) == Kind.ROOT && side.isEmpty())
                    notes.add(new Note(Note.Kind.UNSIDED_ROOT, "leg root '" + seat
                        + "' carries neither a side token nor a resolvable side"));
                Optional<Side> sits = geometric(seat, bones, legNames, chains);
                if (side.isPresent() && sits.isPresent() && side.get() != sits.get()) {
                    notes.add(new Note(Note.Kind.CROSSED, "leg '" + seat + "' is named "
                        + side.get() + " and sits " + sits.get()));
                    crossed.add(seat);
                }
                members.add(new Member(seat, ordinal, side, 0, kinds.get(seat)));
                for (String below : segmentsUnder(seat, bones, legNames, kinds))
                    members.add(new Member(below, ordinal, side,
                        depth(below, seat, bones, legNames), Kind.SEGMENT));
            }
            members.sort(Comparator
                .comparingInt((Member member) -> member.side().map(Side::ordinal).orElse(-1))
                .reversed()
                .thenComparingInt(Member::depth)
                .thenComparing(Member::bone));
            rows.add(new Row(ordinal, Concurrent.newUnmodifiableList(members)));
        }

        for (String bone : legish)
            if (kinds.get(bone) == Kind.GROUPER && childLegs(bone, bones, legNames).size() < 2)
                notes.add(new Note(Note.Kind.THIN_GROUPER, "row grouper '" + bone
                    + "' holds fewer than two legs"));

        return new LimbRoster(Concurrent.newUnmodifiableList(rows),
            Concurrent.newUnmodifiableList(notes), Concurrent.newUnmodifiableList(crossed));
    }

    /**
     * The bone one rank and side address, or empty where the mesh carries no such leg.
     *
     * @param rank which row front to back
     * @param side which side of that row
     * @return the addressed bone name
     */
    public @NotNull Optional<String> resolve(@NotNull Rank rank, @NotNull Side side) {
        return this.row(rank).stream()
            .flatMap(row -> row.members().stream())
            .filter(member -> member.depth() == 0)
            .filter(member -> member.side().filter(side::equals).isPresent())
            .map(Member::bone)
            .findFirst();
    }

    /**
     * The bones one address reaches on a mesh, whichever kind of address it is.
     *
     * <p>The roster is supplied rather than passed, and that is the point of the parameter: only a
     * leg address needs one. A family resolves through {@link LimbFamily}, which walks the mesh
     * down from its stem and never asks which bones are legs, so a caller holding no roster does
     * not pay to build one it will not read - and building one is a full chain-transform walk over
     * the mesh.
     *
     * @param selector the address to resolve
     * @param mesh the mesh being addressed
     * @param roster the roster a leg address resolves against, read on that arm alone
     * @return the bones addressed, empty where the mesh answers none
     */
    public static @NotNull ConcurrentList<String> members(@NotNull LimbSelector selector,
                                                          @NotNull EntityModelData mesh,
                                                          @NotNull Supplier<@NotNull LimbRoster> roster) {
        return switch (selector) {
            case LimbSelector.Legs legs -> roster.get().members(legs);
            case LimbSelector.Family family -> LimbFamily.members(mesh, family.stem());
        };
    }

    /**
     * The bones one leg address reaches on this roster.
     *
     * <p>A row a sided address reaches answers the legs on that side. A row whose legs carry no
     * side of their own is a whole row painted by one bone, so the only address that
     * reaches it is an address speaking for the whole row: one naming no side, or the near side of
     * a pair, whose far side then answers nothing and the row takes one stance rather than two
     * cancelling on yaw and doubling on pitch. An address written for one leg of the row reaches
     * nothing, because the leg it names is not a bone this mesh has.
     *
     * @param legs which legs the mesh is asked for
     * @return the bones addressed, empty where the mesh answers none
     */
    public @NotNull ConcurrentList<String> members(@NotNull LimbSelector.Legs legs) {
        List<Row> addressed = legs.rank()
            .map(rank -> this.row(rank).stream().toList())
            .orElseGet(() -> List.copyOf(this.rows));

        List<String> bones = new ArrayList<>();
        for (Row row : addressed)
            for (Member member : row.members()) {
                if (!reaches(legs, member)) continue;
                boolean root = member.depth() == 0;
                boolean reached = switch (legs.reach()) {
                    case ROOT -> root;
                    case CHAIN -> true;
                    case SEGMENTS -> !root;
                };
                if (reached) bones.add(member.bone());
            }
        return Concurrent.newUnmodifiableList(bones);
    }

    /**
     * Whether one address reaches one leg, on the side it names.
     *
     * <p>A leg carrying a side answers the address that names that side and no other. A leg
     * carrying none is a whole row painted by one bone, so it answers an address speaking for the
     * row - one naming no side, or the near side of a pair, never the far side and never an
     * address written for a single leg.
     */
    private static boolean reaches(@NotNull LimbSelector.Legs legs, @NotNull Member member) {
        if (member.side().isPresent())
            return legs.side().isEmpty() || legs.side().equals(member.side());
        return legs.side().isEmpty() || legs.stamp() == LimbSelector.Stamp.NEAR;
    }

    /**
     * Where one bone sits among the legs, or empty where no row holds it.
     *
     * <p>This is the one reading of a leg that is the mesh's rather than the address's. A side read
     * here is the side of the leg the bone hangs off and never the side its own name claims - two
     * boots in the corpus are cross-parented by vanilla - and a depth read here is the chain the
     * mesh declares rather than the reach the author asked for.
     *
     * @param bone the bone name, as the mesh names it
     * @return where it sits
     */
    public @NotNull Optional<Member> placementOf(@NotNull String bone) {
        for (Row row : this.rows)
            for (Member member : row.members())
                if (member.bone().equals(bone))
                    return Optional.of(member);
        return Optional.empty();
    }

    /**
     * The row one rank addresses, or empty where the mesh carries no such row.
     *
     * <p>The two ends answer on any mesh carrying a leg at all. A rank naming a row between them
     * answers only where the mesh has one to name, so a chain written for a middle row addresses
     * nothing on a mesh without one rather than landing on the row behind it.
     *
     * @param rank which row front to back
     * @return the addressed row
     */
    public @NotNull Optional<Row> row(@NotNull Rank rank) {
        if (this.rows.isEmpty()) return Optional.empty();
        return switch (rank) {
            case FRONT -> Optional.of(this.rows.getFirst());
            case SECOND -> this.interior(1);
            case THIRD -> this.interior(2);
            case HIND -> Optional.of(this.rows.getLast());
        };
    }

    /**
     * The row at one ordinal, where that ordinal falls between the frontmost and the rearmost.
     */
    private @NotNull Optional<Row> interior(int ordinal) {
        return ordinal > 0 && ordinal < this.rows.size() - 1
            ? Optional.of(this.rows.get(ordinal))
            : Optional.empty();
    }

    /**
     * How many legs the mesh paints - one per root, two per fused row.
     *
     * @return the leg count
     */
    public int legCount() {
        return this.members().stream().mapToInt(member -> member.kind().legs()).sum();
    }

    /**
     * Every leg the mesh declares, row by row and front to back.
     *
     * @return every row's members in roster order
     */
    public @NotNull ConcurrentList<Member> members() {
        return Concurrent.newUnmodifiableList(this.rows.stream()
            .flatMap(row -> row.members().stream()).toList());
    }

    /**
     * Whether a bone name says it is a leg.
     *
     * <p>Read as tokens rather than as a substring, so a bone whose name merely contains the
     * letters is not swept in.
     *
     * @param name the bone name, as the mesh names it
     * @return whether the name says leg
     */
    static boolean legish(@NotNull String name) {
        for (String token : name.split("_")) {
            if (token.equals("haunch") || token.equals("foot") || token.equals("feet")) return true;
            if (token.endsWith("legs") || token.endsWith("leg")) return true;
        }
        return false;
    }

    /**
     * What one leg-named bone is.
     *
     * <p>What separates a row grouper from a fused row is the PREDICATE and not the order they are
     * tested in: a grouper carries no cubes of its own and does carry leg children, a fused row
     * carries cubes of its own and carries no leg children. The two conditions are inverted against
     * each other, so no bone satisfies both arms and neither can shadow the other whichever runs
     * first.
     *
     * <p>What would turn a grouper into a fused row - and its legs into segments, leaving the leg
     * count identical and every leg addressed on the wrong bone - is reading its geometry off its
     * SUBTREE rather than off its own cubes. That is the fact this order was standing in for.
     */
    private static @NotNull Kind classify(@NotNull String bone,
                                          @NotNull Map<String, EntityModelData.Bone> bones,
                                          @NotNull Set<String> legNames,
                                          @NotNull Map<String, Matrix4f> chains) {
        if (grouper(bone, bones, legNames)) return Kind.GROUPER;
        if (sideOf(bone).isEmpty() && !bones.get(bone).getCubes().isEmpty()
            && childLegs(bone, bones, legNames).isEmpty() && straddles(bone, bones, chains))
            return Kind.FUSED;

        String above = nearestLeg(bone, bones, legNames);
        if (above == null || grouper(above, bones, legNames)) return Kind.ROOT;
        return Kind.SEGMENT;
    }

    /**
     * Whether a bone holds a row's legs together rather than being one - unsided, carrying no cubes
     * of its OWN, and parenting at least one leg.
     *
     * <p>The cube count is the bone's own and never its subtree's, which is the whole separation: a
     * grouper's legs carry the pixels, so a subtree reading finds geometry on every grouper and
     * reads each as a fused row.
     */
    private static boolean grouper(@NotNull String bone,
                                   @NotNull Map<String, EntityModelData.Bone> bones,
                                   @NotNull Set<String> legNames) {
        return sideOf(bone).isEmpty()
            && bones.get(bone).getCubes().isEmpty()
            && !childLegs(bone, bones, legNames).isEmpty();
    }

    /**
     * Whether a bone's own cubes straddle the midline and sit centred on it.
     */
    private static boolean straddles(@NotNull String bone,
                                     @NotNull Map<String, EntityModelData.Bone> bones,
                                     @NotNull Map<String, Matrix4f> chains) {
        double[] span = spanX(bone, bones, chains, new double[]{Double.MAX_VALUE, -Double.MAX_VALUE});
        double min = span[0];
        double max = span[1];
        if (min > max || min >= 0d || max <= 0d) return false;
        double half = (max - min) / 2d;
        return half > 0d && Math.abs((min + max) / 2d) / half < FUSED_CENTRE_SHARE;
    }

    /**
     * One bone's cubes as a sideways span, placed the way the renderer places them.
     *
     * <p>Every corner is carried through the cube's own composed transform rather than added to the
     * bone's anchor as a raw offset. A bone under a bind rotation carries its cubes around with it -
     * a half turn puts what the model authored on one side out the other - so an offset added to a
     * turned anchor reports a side the renderer never draws.
     *
     * @param span the span so far, widened in place and returned
     */
    private static double @NotNull [] spanX(@NotNull String bone,
                                            @NotNull Map<String, EntityModelData.Bone> bones,
                                            @NotNull Map<String, Matrix4f> chains,
                                            double @NotNull [] span) {
        EntityModelData.Bone held = bones.get(bone);
        Matrix4f chain = chains.getOrDefault(bone, Matrix4f.IDENTITY);
        for (EntityModelData.Cube cube : held.getCubes()) {
            Matrix4f placed = BoneKit.composeCubeTransform(cube, held, chain);
            Vector3f origin = cube.getOrigin();
            Vector3f size = cube.getSize();
            for (int corner = 0; corner < 8; corner++) {
                Vector3f point = new Vector3f(
                    origin.x() + ((corner & 1) == 0 ? 0f : size.x()),
                    origin.y() + ((corner & 2) == 0 ? 0f : size.y()),
                    origin.z() + ((corner & 4) == 0 ? 0f : size.z())
                ).transform(placed);
                span[0] = Math.min(span[0], point.x());
                span[1] = Math.max(span[1], point.x());
            }
        }
        return span;
    }

    /**
     * Which side a bone's accumulated subtree geometry sits on, read only as a cross-check.
     *
     * <p>The subtree rather than the bone's own cubes, because a leg root may carry no geometry at
     * all and hold its pixels on a segment below it, and the pivot rather than the span only where
     * the whole subtree is cubeless - a pivot is a leg's inner edge on some meshes and its midline
     * on others, so it decides a side wrongly and quietly.
     */
    private static @NotNull Optional<Side> geometric(@NotNull String bone,
                                                     @NotNull Map<String, EntityModelData.Bone> bones,
                                                     @NotNull Set<String> legNames,
                                                     @NotNull Map<String, Matrix4f> chains) {
        double[] span = {Double.MAX_VALUE, -Double.MAX_VALUE};
        for (String below : subtree(bone, bones))
            spanX(below, bones, chains, span);
        double centre = span[0] > span[1]
            ? accumulated(bone, chains).x()
            : (span[0] + span[1]) / 2d;
        if (Math.abs(centre) <= 1.0e-4d) return Optional.empty();
        return Optional.of(centre < 0d ? Side.RIGHT : Side.LEFT);
    }

    /**
     * Groups the seats into rows, opening a row whenever a seat sits further from the open row's
     * first member than the tolerance the mesh's own front-to-back spread sets.
     */
    private static @NotNull List<List<String>> cluster(@NotNull List<String> seats,
                                                       @NotNull Map<String, Matrix4f> chains) {
        List<List<String>> rows = new ArrayList<>();
        if (seats.isEmpty()) return rows;
        double extent = accumulated(seats.getLast(), chains).z()
            - accumulated(seats.getFirst(), chains).z();
        double tolerance = Math.max(ROW_TOLERANCE_SHARE * extent, ROW_TOLERANCE_FLOOR);
        for (String seat : seats) {
            double depth = accumulated(seat, chains).z();
            if (rows.isEmpty()
                || Math.abs(depth - accumulated(rows.getLast().getFirst(), chains).z()) > tolerance)
                rows.add(new ArrayList<>(List.of(seat)));
            else
                rows.getLast().add(seat);
        }
        return rows;
    }

    /**
     * Which side a bone's name says it is on.
     */
    private static @NotNull Optional<Side> sideOf(@NotNull String name) {
        for (String token : name.split("_"))
            if (SIDE_TOKENS.contains(token))
                return Optional.of(token.equals("left") ? Side.LEFT : Side.RIGHT);
        return Optional.empty();
    }

    /**
     * The nearest leg-named ancestor, or {@code null} where the chain holds none.
     */
    private static String nearestLeg(@NotNull String bone,
                                     @NotNull Map<String, EntityModelData.Bone> bones,
                                     @NotNull Set<String> legNames) {
        Set<String> seen = new LinkedHashSet<>(List.of(bone));
        String above = bones.get(bone).getParent();
        while (above != null && bones.containsKey(above) && seen.add(above)) {
            if (legNames.contains(above)) return above;
            above = bones.get(above).getParent();
        }
        return null;
    }

    /**
     * The leg-named bones parented directly to one bone.
     */
    private static @NotNull List<String> childLegs(@NotNull String bone,
                                                   @NotNull Map<String, EntityModelData.Bone> bones,
                                                   @NotNull Set<String> legNames) {
        return legNames.stream()
            .filter(name -> bone.equals(bones.get(name).getParent()))
            .toList();
    }

    /**
     * Every leg-named bone below one root, nearest first.
     */
    private static @NotNull List<String> segmentsUnder(@NotNull String root,
                                                       @NotNull Map<String, EntityModelData.Bone> bones,
                                                       @NotNull Set<String> legNames,
                                                       @NotNull Map<String, Kind> kinds) {
        return legNames.stream()
            .filter(name -> kinds.get(name) == Kind.SEGMENT)
            .filter(name -> root.equals(owningRoot(name, bones, legNames, kinds)))
            .sorted(Comparator.comparingInt(name -> depth(name, root, bones, legNames)))
            .toList();
    }

    /**
     * The root one segment hangs below, climbing past the segments between them.
     */
    private static String owningRoot(@NotNull String bone,
                                     @NotNull Map<String, EntityModelData.Bone> bones,
                                     @NotNull Set<String> legNames,
                                     @NotNull Map<String, Kind> kinds) {
        String above = nearestLeg(bone, bones, legNames);
        while (above != null && kinds.get(above) == Kind.SEGMENT)
            above = nearestLeg(above, bones, legNames);
        return above;
    }

    /**
     * How many leg-named bones sit between one bone and its root, the nearest segment at one.
     */
    private static int depth(@NotNull String bone, @NotNull String root,
                             @NotNull Map<String, EntityModelData.Bone> bones,
                             @NotNull Set<String> legNames) {
        int depth = 0;
        String above = bone;
        while (above != null && !above.equals(root)) {
            above = nearestLeg(above, bones, legNames);
            depth++;
        }
        return depth;
    }

    /**
     * One bone and every bone below it.
     */
    private static @NotNull List<String> subtree(@NotNull String bone,
                                                 @NotNull Map<String, EntityModelData.Bone> bones) {
        List<String> below = new ArrayList<>(List.of(bone));
        for (int index = 0; index < below.size(); index++) {
            String at = below.get(index);
            bones.forEach((name, held) -> {
                if (at.equals(held.getParent()) && !below.contains(name)) below.add(name);
            });
        }
        return below;
    }

    /**
     * Where a bone sits once every ancestor's bind rotation has been composed - the anchor a naive
     * sum of pivots gets wrong wherever the chain turns.
     */
    private static @NotNull Vector3f accumulated(@NotNull String bone,
                                                 @NotNull Map<String, Matrix4f> chains) {
        Matrix4f chain = chains.get(bone);
        return chain == null ? Vector3f.ZERO : Vector3f.ZERO.transform(chain);
    }

}
