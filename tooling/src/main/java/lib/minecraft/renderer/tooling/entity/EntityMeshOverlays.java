package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryIds.Derivation;
import lib.minecraft.renderer.tooling.geometry.GeometryIds;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Writes what an overlay pass does to the mesh it draws into a mesh of its own, and takes the three
 * members asking for it off the model table.
 *
 * <p>Each of the three is a vanilla operation on a baked mesh: a {@code retainExactParts} subset
 * restricting a pass to the bones vanilla's own subset definition draws, a {@code CubeDeformation}
 * inflating every cube so the pass surrounds the body rather than z-fighting it, and a
 * {@code clearChild().clearRecursively()} emptying a subtree for the pass drawn when a subject wears
 * no hat. They compose in that order, which is the order a key spells them in.
 *
 * <p><b>An inflate is not the request grow under another name.</b> The request's grow pre-seeds the
 * factory's default deformation, which a cube the factory deforms inline overrides; an inflate adds
 * to whatever a cube ended up carrying. The equine's {@code body} is deformed inline at 0.05 and a
 * request's {@code @grow=0.1} leaves it there, where an inflate of the same amount would take it to
 * 0.15, so the two never share a spelling.
 *
 * <p><b>But a mesh the CLIENT registers is a request rather than a derivation.</b> Where vanilla
 * builds a layer by handing the same factory the same deformation, the mesh a pass wants already
 * ships under its own key, and minting an {@code @inflate=} beside it would model one registration
 * twice with one bone set between the two. The pass names the registered mesh instead.
 *
 * <p><b>The suppressed pass's mesh ships BESIDE the primary, never in place of it.</b> Both subjects
 * carrying one name their pass's mesh with the body's own coordinate, and a pass drawing the body's
 * mesh is excluded from the canvas union because the body already contributes that silhouette.
 * Replacing the reference would lose that, and the villager and the wandering trader share a canvas.
 * So the derived mesh is named by a member of its own and the reference is left alone.
 *
 * <p>Runs after the marking and the shift, so what a pass derives from is the mesh as it finally
 * stands rather than the mesh before the two passes that move it.
 */
@UtilityClass
public final class EntityMeshOverlays {

    /**
     * Derives every overlay pass's mesh, and strips the three members asking for one.
     *
     * @param diagnostics the flow's sink
     * @param models the model table's {@code models} node
     * @param geometries the parsed entries, keyed by minted coordinate, modified in place
     * @throws ToolingException if a pass derives from a body its coats name differently
     */
    public static void apply(
        @NotNull Diagnostics diagnostics, @NotNull JsonTree models,
        @NotNull Map<String, JsonTree> geometries, @NotNull GeometryManifest manifest) {

        List<Site> sites = sitesIn(models);
        byCoordinate(sites).forEach((coordinate, states) ->
            derive(diagnostics, geometries, manifest, coordinate, states));
        for (Site site : sites) site.strip();
    }

    // ------------------------------------------------------------------------------------
    // the derivation
    // ------------------------------------------------------------------------------------

    /**
     * Derives one coordinate's meshes: the state every site shares is written where the mesh stands,
     * and states that disagree each mint a mesh of their own.
     *
     * <p>The bare coordinate is left to the sites that draw the mesh as it is, and removed only where
     * there are none - a mesh a subject draws whole is that subject's mesh, and deriving it in place
     * because a pass wants it inflated would inflate the body too.
     */
    private static void derive(
        @NotNull Diagnostics diagnostics, @NotNull Map<String, JsonTree> geometries,
        @NotNull GeometryManifest manifest, @NotNull String coordinate,
        @NotNull Map<Map<Derivation, String>, List<Site>> states) {

        JsonTree entry = geometries.get(coordinate);
        if (entry == null) return;                              // a dangling ref the closure test owns
        // Every site does the same thing to it, so there is nothing for a discriminator to tell apart
        // and the mesh is derived where it stands.
        boolean inPlace = states.size() == 1 && !states.keySet().iterator().next().isEmpty();
        boolean bare = false;

        for (Map.Entry<Map<Derivation, String>, List<Site>> state : states.entrySet()) {
            Map<Derivation, String> materialisation = state.getKey();
            List<Site> group = state.getValue();
            String key = coordinate;
            JsonTree mesh = entry;

            if (materialisation.isEmpty()) {
                bare = true;
            } else if (inPlace) {
                materialise(mesh, group.getFirst().surgery());
                diagnostics.info("derived '%s' where it stands, as %s",
                    coordinate, materialisation.values());
            } else {
                String registered = registeredAsRequest(manifest, coordinate, materialisation);
                if (registered != null) {
                    key = registered;
                    mesh = geometries.get(registered);
                    for (Site site : group) site.node().put("geometry", registered);
                    diagnostics.info("'%s' is registered as '%s' rather than derived, for %d site(s)",
                        coordinate, registered, group.size());
                } else {
                    key = GeometryIds.derived(coordinate, materialisation);
                    mesh = entry.deepCopy();
                    GeometryIds.stampSource(mesh, materialisation);
                    materialise(mesh, group.getFirst().surgery());
                    geometries.put(key, mesh);
                    for (Site site : group) site.node().put("geometry", key);
                    diagnostics.info("derived '%s' as '%s' for %d site(s)", coordinate, key, group.size());
                }
            }

            for (Site site : group) {
                String drawn = key;
                JsonTree from = mesh;
                site.surgery().cleared().ifPresent(root ->
                    clearInto(diagnostics, geometries, site, drawn, from, root));
            }
        }
        if (!bare && !inPlace) geometries.remove(coordinate);
    }

    /**
     * The key vanilla's own registration already holds this derivation's mesh under, or {@code null}
     * where the derivation is this flow's and not the client's.
     *
     * <p>An inflate adds to whatever a cube ended up carrying and a request's {@code grow} pre-seeds
     * the factory's default deformation, so the two are different operations and keep different
     * spellings. But where the CLIENT builds a layer by handing the same factory the same deformation,
     * the mesh a pass wants is one {@code LayerDefinitions} already registers - and minting an
     * {@code @inflate=} beside it models one registration twice, under two keys, with one bone set
     * between them.
     *
     * <p>Answered off the manifest, which is the record of what was registered, rather than by
     * comparing materialised payloads: byte-equality would collapse the pair for the wrong reason and
     * would say nothing where the two operations differ but the meshes still coincide.
     *
     * @param manifest the registered requests
     * @param coordinate the key of the mesh being derived from
     * @param materialisation what the derivation does to it
     * @return the registered key, or {@code null} where nothing registers this mesh
     */
    private static @Nullable String registeredAsRequest(
        @NotNull GeometryManifest manifest, @NotNull String coordinate,
        @NotNull Map<Derivation, String> materialisation) {

        if (materialisation.size() != 1) return null;
        String inflate = materialisation.get(Derivation.INFLATE);
        if (inflate == null) return null;
        // A request spells its pre-seed FIRST among the discriminators, so the registered mesh for a
        // bare coordinate is that coordinate with the pre-seed appended.
        String candidate = coordinate + "@grow=" + inflate;
        return manifest.entries().containsKey(candidate) ? candidate : null;
    }

    /**
     * Derives the mesh a pass draws where it is not suppressed - the subset it is restricted to, then
     * the inflate it surrounds the body with, which is the order vanilla applies them in.
     */
    private static void materialise(@NotNull JsonTree mesh, @NotNull Surgery surgery) {
        JsonTree bones = mesh.find("bones").orElse(null);
        if (bones == null) return;
        if (!surgery.retain().isEmpty()) retain(bones, surgery.retain());
        if (surgery.inflate() != 0f) inflate(bones, surgery.inflate());
    }

    /**
     * Mints the mesh the suppressed pass draws and names it on the row, beside the mesh the pass
     * draws otherwise.
     *
     * <p>A root the mesh does not carry writes nothing, which leaves the pass with no alternate mesh
     * at all - the same answer as a subject whose pass is never suppressed, and the one thing that
     * cannot happen is a mesh cleared somewhere nobody asked for.
     */
    private static void clearInto(
        @NotNull Diagnostics diagnostics, @NotNull Map<String, JsonTree> geometries,
        @NotNull Site site, @NotNull String drawn, @NotNull JsonTree mesh, @NotNull String root) {

        JsonTree bones = mesh.find("bones").orElse(null);
        if (bones == null || !bones.has(root)) {
            diagnostics.warn("'%s' clears below '%s', which is not a bone it carries", drawn, root);
            return;
        }
        Map<Derivation, String> derivation = Map.of(Derivation.CLEARED, root);
        String key = GeometryIds.derived(drawn, derivation);
        if (!geometries.containsKey(key)) {
            JsonTree cleared = mesh.deepCopy();
            GeometryIds.stampSource(cleared, derivation);
            clearSubtree(cleared, root);
            geometries.put(key, cleared);
            diagnostics.info("cleared '%s' below '%s' as '%s'", drawn, root, key);
        }
        site.node().put("no_hat_geometry", key);
    }

    // ------------------------------------------------------------------------------------
    // the three surgeries
    // ------------------------------------------------------------------------------------

    /**
     * Restricts a mesh to the vanilla {@code retainExactParts} subset: a bone draws its cubes where it
     * is named and no ancestor of it is, vanilla's own {@code clearRecursively} emptying a retained
     * part's descendants. Every other bone stays as a pose-only node, so the chain above a drawn bone
     * is whole.
     */
    private static void retain(@NotNull JsonTree bones, @NotNull List<String> retained) {
        Set<String> named = new LinkedHashSet<>(retained);
        for (String name : names(bones)) {
            JsonTree bone = bones.find(name).orElse(null);
            if (bone == null) continue;
            if (named.contains(name) && !hasNamedAncestor(bones, bone, named)) continue;
            bone.remove("cubes");
        }
    }

    /** Adds one deformation to every cube of a mesh, on every axis. */
    private static void inflate(@NotNull JsonTree bones, float delta) {
        for (String name : names(bones))
            bones.find(name)
                .flatMap(bone -> bone.findArray("cubes"))
                .ifPresent(cubes -> cubes.elements().forEach(cube -> grow(cube, delta)));
    }

    /**
     * Adds one deformation to a cube's own, written the way a cube's grow is written - a scalar where
     * the three axes agree, omitted where they are all zero.
     */
    private static void grow(@NotNull JsonTree cube, float delta) {
        JsonTree held = cube.find("grow").orElse(null);
        float x = 0f;
        float y = 0f;
        float z = 0f;
        if (held != null && held.isArray()) {
            x = held.getFloat(0, 0f);
            y = held.getFloat(1, 0f);
            z = held.getFloat(2, 0f);
        } else if (held != null) {
            x = held.asFloat(0f);
            y = x;
            z = x;
        }
        x += delta;
        y += delta;
        z += delta;
        if (x == 0f && y == 0f && z == 0f) cube.remove("grow");
        else if (x == y && y == z) cube.put("grow", x);
        else cube.putFloats("grow", x, y, z);
    }

    /**
     * Empties the cubes of one bone and of every descendant - vanilla's
     * {@code clearChild(name).clearRecursively()}. Every bone keeps its pivot, rotations, scale and
     * parent, so the chain is untouched and a bone with no cubes simply draws nothing.
     */
    private static void clearSubtree(@NotNull JsonTree mesh, @NotNull String root) {
        JsonTree bones = mesh.find("bones").orElse(null);
        if (bones == null) return;
        Set<String> named = Set.of(root);
        for (String name : names(bones)) {
            JsonTree bone = bones.find(name).orElse(null);
            if (bone == null) continue;
            if (root.equals(name) || hasNamedAncestor(bones, bone, named)) bone.remove("cubes");
        }
    }

    /** Whether any proper ancestor of one bone is named. */
    private static boolean hasNamedAncestor(
        @NotNull JsonTree bones, @NotNull JsonTree bone, @NotNull Set<String> named) {

        for (String parent = bone.findString("parent").orElse(null); parent != null; ) {
            if (named.contains(parent)) return true;
            JsonTree above = bones.find(parent).orElse(null);
            if (above == null) return false;
            parent = above.findString("parent").orElse(null);
        }
        return false;
    }

    /** The bone names of a mesh, taken as a list so a bone can be edited while the mesh is walked. */
    private static @NotNull List<String> names(@NotNull JsonTree bones) {
        return bones.members()
            .keys()
            .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------------------------
    // the sites
    // ------------------------------------------------------------------------------------

    /**
     * One place in the model table that names a mesh, paired with what is done to the mesh there and
     * the node the members asking for it have to come off.
     */
    private record Site(@NotNull JsonTree node, @NotNull String coordinate, @NotNull Surgery surgery) {

        /**
         * Takes the three members off, the mesh they asked for now being named rather than described.
         *
         * <p>A place that does nothing to its mesh keeps every member it carries. An armor row names
         * a mesh and carries a {@code grow} of its own - the two layer deformations, which are not a
         * pass's inflate and are not this pass's to take.
         */
        void strip() {
            if (this.surgery.isEmpty()) return;
            this.node.remove("retain_bones");
            this.node.remove("grow");
            this.node.remove("no_hat_root");
        }

    }

    /**
     * What one place does to the mesh it names.
     *
     * @param retain the vanilla {@code retainExactParts} subset the pass is restricted to
     * @param inflate the deformation added to every cube, on every axis
     * @param cleared the bone whose subtree the suppressed pass empties
     */
    private record Surgery(
        @NotNull List<String> retain, float inflate, @NotNull Optional<String> cleared) {

        /** What one place does to a mesh it does nothing to. */
        static final Surgery NONE = new Surgery(List.of(), 0f, Optional.empty());

        /** Whether this says anything at all. */
        boolean isEmpty() {
            return this.retain.isEmpty() && this.inflate == 0f && this.cleared.isEmpty();
        }

        /**
         * The derivations naming the mesh the pass draws where it is not suppressed, which is what a
         * reference to it is replaced by.
         *
         * <p>The subset is sorted, so a list the walk happens to order differently derives the same
         * mesh. What clears a subtree is absent: that mesh ships beside this one rather than as it.
         */
        @NotNull Map<Derivation, String> materialisation() {
            Map<Derivation, String> out = new EnumMap<>(Derivation.class);
            if (!this.retain.isEmpty())
                out.put(Derivation.RETAIN, String.join(",", new TreeSet<>(this.retain)));
            if (this.inflate != 0f) out.put(Derivation.INFLATE, String.valueOf(this.inflate));
            return out;
        }

    }

    /** The sites grouped by the mesh they name and then by the mesh they derive from it. */
    private static @NotNull Map<String, Map<Map<Derivation, String>, List<Site>>> byCoordinate(
        @NotNull List<Site> sites) {

        return sites.stream()
            .collect(Collectors.groupingBy(Site::coordinate, LinkedHashMap::new,
                Collectors.groupingBy(site -> site.surgery().materialisation(), LinkedHashMap::new,
                    Collectors.toList())));
    }

    /**
     * Every place the table names a mesh: the overlay rows against what they do to it, and every
     * other place against the fact that it draws the mesh as it is.
     */
    private static @NotNull List<Site> sitesIn(@NotNull JsonTree models) {
        List<Site> sites = new ArrayList<>();
        models.members().forEach((id, subject) -> passesOf(id, subject, sites));
        models.members().forEach((id, subject) -> drawnBy(subject, sites));
        return sites;
    }

    /** Every overlay row of one subject, against the mesh it draws on. */
    private static void passesOf(
        @NotNull String id, @NotNull JsonTree subject, @NotNull List<Site> sites) {

        JsonTree axes = subject.find("axes").orElse(null);
        JsonTree age = axes == null ? null : axes.find("age").orElse(null);
        String babyCoord = coordinateOf(option(age, "baby"));
        rowsOf(subject.findArray("overlays").orElse(null), id,
            coordinateOf(option(age, "adult")), babyCoord, coatsNameTheirOwn(axes), sites);

        // The large shape's passes draw on the large body, which is a mesh of its own.
        JsonTree shape = axes == null ? null : axes.find("shape").orElse(null);
        JsonTree large = option(shape, "large");
        if (large != null)
            rowsOf(large.findArray("overlays").orElse(null), id,
                coordinateOf(large), babyCoord, false, sites);
    }

    /** One list of overlay rows, each with the baby form it substitutes. */
    private static void rowsOf(
        @Nullable JsonTree rows, @NotNull String id, @Nullable String baseCoord,
        @Nullable String babyCoord, boolean coatsDiffer, @NotNull List<Site> sites) {

        if (rows == null) return;
        rows.elements().forEach(row -> {
            passAt(row, id, baseCoord, coatsDiffer, sites);
            row.find("baby").ifPresent(baby -> passAt(baby, id, babyCoord, false, sites));
        });
    }

    /**
     * One overlay row, where it does something to the mesh it draws.
     *
     * @throws ToolingException if the row draws on the family body while the family's coats name
     *     bodies of their own, which is a different mesh per coat and so not one mesh to derive
     */
    private static void passAt(
        @NotNull JsonTree row, @NotNull String id, @Nullable String baseCoord, boolean coatsDiffer,
        @NotNull List<Site> sites) {

        Surgery surgery = surgeryOf(row);
        if (surgery.isEmpty()) return;
        String own = row.findString("geometry").orElse(null);
        if (own == null && coatsDiffer)
            throw new ToolingException(
                "entity '%s' derives a pass from the family body while its coats name bodies of their "
                    + "own, so what it derives from is a different mesh for each of them",
                id);
        String coordinate = own == null ? baseCoord : own;
        if (coordinate == null) return;                 // a pass on a mesh the family does not carry
        sites.add(new Site(row, coordinate, surgery));
    }

    /**
     * What one node does to the mesh it names, which is nothing for all but an overlay row.
     *
     * <p>An inflate is read only off a number. An armor row carries a {@code grow} of its own that is
     * the two layer deformations rather than one amount, and reading that as an inflate would make an
     * armor shell look like a pass that derives a mesh.
     */
    private static @NotNull Surgery surgeryOf(@NotNull JsonTree node) {
        List<String> retain = node.findArray("retain_bones")
            .map(named -> named.elements()
                .map(JsonTree::asString)
                .flatMap(Optional::stream)
                .collect(Collectors.toList()))
            .orElseGet(List::of);
        float inflate = node.find("grow")
            .filter(JsonTree::isPrimitive)
            .map(grow -> grow.asFloat(0f))
            .orElse(0f);
        return new Surgery(retain, inflate, node.findString("no_hat_root"));
    }

    /**
     * Every place the table names a mesh and does nothing to it, which are the places that draw it as
     * it is.
     *
     * <p>Walked rather than enumerated, so a member added beside the ones known today is counted as a
     * place that draws the mesh rather than passed over - and passing over one is what would let a
     * mesh two subjects share be derived in place for the one that wanted it changed.
     */
    private static void drawnBy(@NotNull JsonTree node, @NotNull List<Site> sites) {
        if (node.isObject()) {
            if (surgeryOf(node).isEmpty())
                node.findString("geometry").ifPresent(coordinate ->
                    sites.add(new Site(node, coordinate, Surgery.NONE)));
            node.members().forEach((name, child) -> drawnBy(child, sites));
        } else if (node.isArray()) {
            node.elements().forEach(child -> drawnBy(child, sites));
        }
    }

    /** Whether the family's coats name bodies of their own rather than sharing the family's. */
    private static boolean coatsNameTheirOwn(@Nullable JsonTree axes) {
        JsonTree variant = axes == null ? null : axes.find("variant").orElse(null);
        if (variant == null) return false;
        JsonTree options = variant.find("options").orElse(null);
        if (options == null) return false;
        return options.members()
            .values()
            .anyMatch(option -> option.findString("geometry").isPresent());
    }

    /** One option of an axis, or {@code null} where the axis or the option is absent. */
    private static @Nullable JsonTree option(@Nullable JsonTree axis, @NotNull String name) {
        if (axis == null) return null;
        return axis.find("options").flatMap(options -> options.find(name)).orElse(null);
    }

    /** The mesh one node names, or {@code null} where it names none. */
    private static @Nullable String coordinateOf(@Nullable JsonTree node) {
        return node == null ? null : node.findString("geometry").orElse(null);
    }

}
