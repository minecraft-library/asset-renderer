package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryIds.Derivation;
import lib.minecraft.renderer.tooling.geometry.GeometryIds;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Writes what a subject rests without onto the mesh it rests in, and takes the two members saying it
 * off the model table.
 *
 * <p>A bone the subject never draws is dropped, because nothing can ever ask for it. A bone it rests
 * without but a selection can draw stays, standing at {@code visible = false} and naming the
 * selection that flips it. Every bone a selection moves names it either way, so which way a toggle
 * points is read off the mesh that renders rather than declared beside it.
 *
 * <p>Runs after the pose flow, which is what settles the resting half of the {@code undrawn} lists
 * this reads - and therefore after the geometry has been parsed but before it is written.
 *
 * <p><b>A coordinate two sites rest differently splits.</b> One mesh cannot stand at two rest states,
 * so each state that says something mints a key naming what it rests without, and the bare
 * coordinate is left to the sites that say nothing - removed only where there are none. The corpus
 * splits exactly one: the four illagers share a body mesh and rest three ways in it. Where every
 * site agrees - which is every other shared coordinate - the mesh is marked where it stands and its
 * key is untouched, a discriminator that distinguishes nothing being noise in a grammar built to be
 * read.
 *
 * <p><b>Resting whole in a mesh is a state like any other.</b> A site saying nothing is counted for
 * exactly that reason: a mesh one subject rests without a bone in and another rests whole in is two
 * states, and reading only the first would mark the shared mesh and stop the second drawing a bone
 * it draws.
 */
@UtilityClass
public final class EntityMeshMarking {

    /**
     * Marks every parsed mesh with what its subjects rest without, splitting a coordinate its sites
     * rest differently in, and strips {@code undrawn} and {@code toggles} from the model table.
     *
     * @param diagnostics the flow's sink
     * @param models the model table's {@code models} node, after the pose flow has filled it
     * @param geometries the parsed entries, keyed by minted coordinate, modified in place
     */
    public static void apply(
        @NotNull Diagnostics diagnostics, @NotNull JsonTree models,
        @NotNull Map<String, JsonTree> geometries) {

        List<Site> sites = sitesIn(models);
        byCoordinate(sites).forEach((coordinate, states) -> {
            JsonTree entry = geometries.get(coordinate);
            if (entry == null) return;                          // a dangling ref the closure test owns
            if (states.size() == 1) {
                Rest only = states.keySet().iterator().next();
                if (!only.isEmpty()) mark(entry, only);
                return;
            }
            split(diagnostics, geometries, coordinate, entry, states);
        });

        for (Site site : sites) site.strip();
        models.members().forEach((id, subject) -> {
            dropEmptyBones(subject);
            subject.findArray("equipment").ifPresent(rows ->
                rows.elements().forEach(EntityMeshMarking::dropEmptyBones));
        });
    }

    /**
     * Splits one coordinate its sites rest differently in: every state that says something mints a
     * mesh of its own and the sites in it are pointed at the minted key.
     *
     * <p>The bare coordinate is left to the sites that say nothing, and removed only where there
     * are none - a mesh a subject rests whole in is that subject's mesh, and marking it because
     * another subject rests without a bone would stop the first one drawing it.
     *
     * @throws ToolingException if two states mint one key, which is two meshes under one name
     */
    private static void split(
        @NotNull Diagnostics diagnostics, @NotNull Map<String, JsonTree> geometries,
        @NotNull String coordinate, @NotNull JsonTree entry, @NotNull Map<Rest, List<Site>> states) {

        Set<String> minted = new LinkedHashSet<>();
        boolean bare = false;
        for (Map.Entry<Rest, List<Site>> state : states.entrySet()) {
            Rest rest = state.getKey();
            if (rest.isEmpty()) {
                bare = true;
                continue;
            }
            Map<Derivation, String> derivation = Map.of(Derivation.REST, rest.discriminator());
            String key = GeometryIds.derived(coordinate, derivation);
            // Two states the key cannot tell apart differ in their toggles alone, which the key does
            // not name. Refused rather than spelled, because a discriminator that has to say what a
            // selection moves is naming the render rather than the mesh.
            if (!minted.add(key))
                throw new ToolingException(
                    "geometry '%s' rests two ways that both mint '%s', and one name cannot hold two meshes",
                    coordinate, key);
            JsonTree derived = entry.deepCopy();
            GeometryIds.stampSource(derived, derivation);
            mark(derived, rest);
            geometries.put(key, derived);
            for (Site site : state.getValue()) site.node().put("geometry", key);
            diagnostics.info("split '%s' at rest '%s' for %d site(s)",
                coordinate, rest.discriminator(), state.getValue().size());
        }
        if (!bare) geometries.remove(coordinate);
    }

    /**
     * Drops a {@code bones} node left saying nothing. What it held was the two members that have
     * just moved onto the mesh, and a node whose only remaining member is the poser keeps it.
     */
    private static void dropEmptyBones(@NotNull JsonTree owner) {
        owner.find("bones").filter(JsonTree::isEmpty).ifPresent(empty -> owner.remove("bones"));
    }

    // ------------------------------------------------------------------------------------
    // the marking
    // ------------------------------------------------------------------------------------

    /**
     * Writes one rest state onto a parsed mesh: the bones nothing can draw go, and the bones a
     * selection moves say so.
     */
    private static void mark(@NotNull JsonTree entry, @NotNull Rest rest) {
        JsonTree bones = entry.find("bones").orElse(null);
        if (bones == null) return;

        // First-wins where two selections name one bone, so the bone answers to the selection that
        // reached it first.
        Map<String, String> toggleOf = rest.toggles()
            .entrySet()
            .stream()
            .flatMap(toggle -> toggle.getValue().stream().map(bone -> Map.entry(bone, toggle.getKey())))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                (first, second) -> first, LinkedHashMap::new));

        Set<String> hidden = withDescendants(bones, rest.undrawn());
        for (String name : names(bones)) {
            JsonTree bone = bones.find(name).orElse(null);
            if (bone == null) continue;
            String toggle = toggleOf.get(name);
            if (hidden.contains(name) && toggle == null) {
                bones.remove(name);
                continue;
            }
            if (hidden.contains(name)) bone.put("visible", false);
            if (toggle != null) bone.put("toggle", toggle);
        }
    }

    /**
     * Closes a set of bones downwards over the bone forest, so what it holds is whole subtrees.
     *
     * <p>A fixpoint rather than one pass, because a bone object is in the walk's own
     * {@code addOrReplaceChild} order and a child can precede its parent in it.
     */
    private static @NotNull Set<String> withDescendants(
        @NotNull JsonTree bones, @NotNull Set<String> seed) {

        Set<String> closed = seed
            .stream()
            .filter(bones::has)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (closed.isEmpty()) return closed;
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String name : names(bones)) {
                if (closed.contains(name)) continue;
                String parent = bones.find(name).flatMap(bone -> bone.findString("parent")).orElse(null);
                if (parent != null && closed.contains(parent)) {
                    closed.add(name);
                    grew = true;
                }
            }
        }
        return closed;
    }

    /** The bone names of a mesh, taken as a list so the object can be edited while it is walked. */
    private static @NotNull List<String> names(@NotNull JsonTree bones) {
        return bones.keys().collect(Collectors.toCollection(ArrayList::new));
    }

    // ------------------------------------------------------------------------------------
    // the sites
    // ------------------------------------------------------------------------------------

    /**
     * One place in the model table that names a mesh, paired with the rest state its subject is in
     * there and the node the two members saying so have to come off.
     */
    private record Site(@NotNull JsonTree node, @NotNull String coordinate, @NotNull Rest rest,
                        @Nullable JsonTree bones) {

        /** Takes the two members off, and the node holding them when it is left saying nothing. */
        void strip() {
            if (this.bones == null) return;
            this.bones.remove("undrawn");
            this.bones.remove("toggles");
        }
    }

    /**
     * What one site rests without: the bones it never draws, and the bones each selection moves.
     *
     * @param undrawn the bones it rests not drawing
     * @param toggles the bones each named selection moves
     */
    private record Rest(@NotNull Set<String> undrawn, @NotNull Map<String, List<String>> toggles) {

        /** Whether this state says anything at all. */
        boolean isEmpty() {
            return this.undrawn.isEmpty() && this.toggles.isEmpty();
        }

        /**
         * The key a mesh in this state is minted under, which is what it rests without - sorted, so
         * a list the walk happens to order differently mints the same mesh.
         */
        @NotNull String discriminator() {
            return String.join(",", new TreeSet<>(this.undrawn));
        }
    }

    /** Every place the table names a mesh, with the rest state that place is in. */
    private static @NotNull List<Site> sitesIn(@NotNull JsonTree models) {
        return models.members()
            .values()
            .flatMap(subject -> sitesOf(subject).stream())
            .collect(Collectors.toCollection(ArrayList::new));
    }

    /** Every place ONE subject names a mesh. */
    private static @NotNull List<Site> sitesOf(@NotNull JsonTree subject) {
        List<Site> sites = new ArrayList<>();
        JsonTree bones = subject.find("bones").orElse(null);
        Rest family = restOf(bones);
        JsonTree axes = subject.find("axes").orElse(null);

        JsonTree age = axes == null ? null : axes.find("age").orElse(null);
        // The family list serves the body and every coat alike: a coat swaps the mesh and never the
        // model class, so what it rests without is the family's own answer.
        addTo(sites, siteOf(option(age, "adult"), family, bones));
        JsonTree variant = axes == null ? null : axes.find("variant").orElse(null);
        if (variant != null)
            variant.find("options").ifPresent(options -> options.members()
                .forEach((name, option) -> addTo(sites, siteOf(option, family, bones))));

        // A baby and a size mesh each carry their own list, read off their own model class.
        JsonTree baby = option(age, "baby");
        addTo(sites, siteOf(baby, restOf(baby), baby));
        JsonTree size = axes == null ? null : axes.find("size").orElse(null);
        if (size != null)
            size.find("options").ifPresent(options -> options.members()
                .forEach((name, option) -> addTo(sites, siteOf(option, restOf(option), option))));

        subject.findArray("equipment").ifPresent(rows -> rows.elements().forEach(row -> {
            JsonTree rowBones = row.find("bones").orElse(null);
            addTo(sites, siteOf(row, restOf(rowBones), rowBones));
        }));
        return sites;
    }

    /**
     * The sites grouped by the mesh they name and then by the state they rest it in.
     *
     * <p>Grouped by the WHOLE state rather than by the key it would mint, so two states one key
     * cannot tell apart arrive as the two states they are and are refused where they are split.
     */
    private static @NotNull Map<String, Map<Rest, List<Site>>> byCoordinate(
        @NotNull List<Site> sites) {

        return sites.stream()
            .collect(Collectors.groupingBy(Site::coordinate, LinkedHashMap::new,
                Collectors.groupingBy(Site::rest, LinkedHashMap::new,
                    Collectors.toCollection(ArrayList::new))));
    }

    /** Keeps a site, where the node named a mesh and rested in something worth saying. */
    private static void addTo(@NotNull List<Site> sites, @Nullable Site site) {
        if (site != null) sites.add(site);
    }

    /** Reads one node's {@code undrawn} and {@code toggles} into the state they say. */
    private static @NotNull Rest restOf(@Nullable JsonTree node) {
        Set<String> undrawn = new LinkedHashSet<>();
        Map<String, List<String>> toggles = new LinkedHashMap<>();
        if (node != null) {
            node.findArray("undrawn").ifPresent(named ->
                named.elements().forEach(bone -> bone.asString().ifPresent(undrawn::add)));
            node.findObject("toggles").ifPresent(declared -> declared.members().forEach((name, spec) -> {
                List<String> named = spec.findArray("bones")
                    .stream()
                    .flatMap(JsonTree::elements)
                    .flatMap(bone -> bone.asString().stream())
                    .collect(Collectors.toCollection(ArrayList::new));
                if (!named.isEmpty()) toggles.put(name, named);
            }));
        }
        return new Rest(undrawn, toggles);
    }

    /** One option of an axis, or {@code null} where the axis or the option is absent. */
    private static @Nullable JsonTree option(@Nullable JsonTree axis, @NotNull String name) {
        if (axis == null) return null;
        return axis.find("options").flatMap(options -> options.find(name)).orElse(null);
    }

    /**
     * One site, or {@code null} where the node names no mesh.
     *
     * <p>A place resting in nothing worth saying is still a site, because what it says about the
     * mesh is that a subject rests WHOLE in it. A census that dropped those would read a mesh one
     * site rests without a bone in as a mesh every site rests that way in, and mark it for the
     * subjects that draw the bone.
     *
     * @param node the node naming the mesh, which is where a split rewrites the reference
     * @param rest what the subject rests without there
     * @param bones the node the two members saying so have to come off, or {@code null}
     * @return the site, or {@code null} for a place naming no mesh
     */
    private static @Nullable Site siteOf(
        @Nullable JsonTree node, @NotNull Rest rest, @Nullable JsonTree bones) {

        if (node == null) return null;
        String coordinate = node.findString("geometry").orElse(null);
        return coordinate == null ? null : new Site(node, coordinate, rest, bones);
    }

}
