package lib.minecraft.renderer.tooling.geometry;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The part tree a mesh factory builds, replayed call for call on {@code java.util.HashMap}s so the
 * mesh's bones can be written in the order vanilla's baked model draws them.
 *
 * <p>Vanilla keeps a part's children in {@code PartDefinition.children}, a {@code HashMap}, and draws
 * them in its iteration order, which follows from the sequence of map calls the factory made rather
 * than from the parts it ends with. So this holds one {@code HashMap} per part and makes each call as
 * {@code PartDefinition} makes it:
 * <ul>
 *   <li>{@link #addOrReplace} is {@code addOrReplaceChild}: a {@code put} of a fresh part, and where the
 *       name was already a child, a {@code putAll} of the old part's children into the fresh one;</li>
 *   <li>{@link #clearChild}, {@link #clearSubtree}, {@link #retainPartsAndChildren} and
 *       {@link #retainExactParts} are the surgeries, each emptying a part by replacing it through that
 *       same call;</li>
 *   <li>{@link #transformRoot} is {@code MeshDefinition.transformed}, which every whole-mesh scaling goes
 *       through: a fresh root and a {@code putAll} of the old root's children;</li>
 *   <li>{@link #babyTransform} is {@code BabyModelTransform.apply}: a fresh root, each old top-level part
 *       put into it in the old root's order as a fresh part holding a {@code putAll} of its children.</li>
 * </ul>
 *
 * <p><b>A copy is sized by the JDK running the replay.</b> {@code putAll} into an empty map presizes its
 * table, and from JDK {@value #COPY_SIZING_SINCE} on it sizes {@code n} names
 * {@code tableSizeFor(ceil(n / 0.75))} - two to eight slots for one to six names, where a level built by
 * {@code put} holds sixteen - which is what the JDK vanilla runs on does. An older JDK sizes a copy
 * larger and iterates it in another order, so {@link #requireVanillaCopySizing} refuses one.
 *
 * <p><b>It holds every part vanilla holds.</b> A part the parser drops, or the marking drops later, stays
 * here, because a copy is sized by how many children its level holds at that moment and dropping a name
 * from a hash map moves no other name - so the bones that survive are written in this tree's order with
 * the dropped names left out.
 *
 * <p>It rides the parsed entry as {@link #MEMBER} from the parse to the write, spelled as the calls it
 * replayed: a mesh derived by a deep copy carries them, a derivation replaying a vanilla surgery appends
 * it, and the write replays them, orders the bones by the result and takes the member off. A call it
 * cannot replay - a part hung from a name it does not hold, a name hung under a second parent, a surgery
 * the walk cannot place - refuses the tree, naming why, and the entity flow stops on a refused tree rather
 * than write an order it does not know.
 */
final class PartTree {

    /** The member a parsed entry carries its part tree as, from the parse to the write. */
    static final @NotNull String MEMBER = "part_tree";

    /** The first JDK feature release whose {@code HashMap.putAll} sizes a copy as vanilla's runtime does. */
    static final int COPY_SIZING_SINCE = 19;

    /** The call {@link #addOrReplace} replays. */
    private static final @NotNull String ADD_OR_REPLACE = "addOrReplaceChild";

    /** The call {@link #clearChild} replays. */
    private static final @NotNull String CLEAR_CHILD = "clearChild";

    /** The call {@link #clearSubtree} replays. */
    private static final @NotNull String CLEAR_SUBTREE = "clearRecursively";

    /** The call {@link #retainPartsAndChildren} replays. */
    private static final @NotNull String RETAIN_PARTS_AND_CHILDREN = "retainPartsAndChildren";

    /** The call {@link #retainExactParts} replays. */
    private static final @NotNull String RETAIN_EXACT_PARTS = "retainExactParts";

    /** The call {@link #transformRoot} replays. */
    private static final @NotNull String TRANSFORMED = "transformed";

    /** The call {@link #babyTransform} replays. */
    private static final @NotNull String BABY_TRANSFORM = "babyTransform";

    /** The tree's root part, which a whole-mesh transform replaces. */
    private @NotNull Part root = new Part();

    /** Every call replayed so far, each spelled as its name and then its arguments. */
    private final @NotNull List<List<String>> calls = new ArrayList<>();

    /** Why the tree cannot be replayed, empty while every call so far has been. */
    private @NotNull Optional<String> refusal = Optional.empty();

    /**
     * Stops a run whose JDK sizes a {@code HashMap} copy unlike the JDK vanilla runs on.
     *
     * @param feature the feature release of the JDK the replay runs on
     * @throws ToolingException if that release sizes a copy as JDKs before {@value #COPY_SIZING_SINCE} do
     */
    static void requireVanillaCopySizing(int feature) {
        if (feature < COPY_SIZING_SINCE) {
            throw new ToolingException("The part tree replays vanilla's HashMap copies on JDK '%s', which sizes a copy unlike the JDK vanilla runs on - the geometry flow needs JDK '%s' or later",
                feature, COPY_SIZING_SINCE);
        }
    }

    /**
     * Reads the tree a parsed entry carries, replaying its calls.
     *
     * @param entry the parsed geometry entry
     * @return the tree, refused where the entry carries none
     */
    static @NotNull PartTree of(@NotNull JsonTree entry) {
        PartTree tree = new PartTree();
        JsonTree node = entry.find(MEMBER).orElse(null);
        if (node == null) {
            tree.refuse("The entry carries no part tree");
            return tree;
        }
        Optional<String> refused = node.findString("refused");
        if (refused.isPresent()) {
            tree.refusal = refused;
            return tree;
        }
        node.find("calls")
            .ifPresent(calls -> calls.elements().forEach(call -> tree.replay(call.elements()
                .map(argument -> argument.asString().orElseThrow())
                .toList())));
        return tree;
    }

    /**
     * Writes the tree onto a parsed entry, in place of any it carried.
     *
     * @param entry the parsed geometry entry
     */
    void writeTo(@NotNull JsonTree entry) {
        entry.put(MEMBER, this.toJson());
    }

    /**
     * Spells the tree as a parsed entry carries it: the calls it replayed, in order, or the reason it
     * was refused.
     *
     * @return the tree's node
     */
    @NotNull JsonTree toJson() {
        JsonTree node = JsonTree.object();
        if (this.refusal.isPresent()) return node.put("refused", this.refusal.get());
        JsonTree calls = node.childArray("calls");
        this.calls.forEach(call -> calls.add(JsonTree.arrayOf(call.toArray(String[]::new))));
        return node;
    }

    /**
     * Why the tree cannot be replayed.
     *
     * @return the reason, empty where every call was replayed
     */
    @NotNull Optional<String> refusal() {
        return this.refusal;
    }

    /**
     * Refuses the tree, keeping the first reason given.
     *
     * @param reason why the tree cannot be replayed
     * @param args the reason's arguments
     */
    void refuse(@NotNull @PrintFormat String reason, @Nullable Object... args) {
        if (this.refusal.isEmpty()) this.refusal = Optional.of(String.format(reason, args));
    }

    /**
     * Replays {@code addOrReplaceChild}: puts the name under its parent, and where the parent already
     * holds a part of that name, copies the old part's children into the fresh one.
     *
     * @param parent the part the child hangs from, {@code null} for the mesh root
     * @param name the child's name
     */
    void addOrReplace(@Nullable String parent, @NotNull String name) {
        if (this.refusal.isPresent()) return;
        Optional<Part> holder = parent == null ? Optional.of(this.root) : this.find(parent);
        if (holder.isEmpty()) {
            this.refuse("Part '%s' hangs from '%s', which the tree holds no part of", name, parent);
            return;
        }
        if (!holder.get().children().containsKey(name) && this.find(name).isPresent()) {
            this.refuse("Part '%s' is added under '%s' while a part of that name hangs elsewhere",
                name, parent == null ? "the root" : parent);
            return;
        }
        replace(holder.get(), name);
        this.calls.add(parent == null ? List.of(ADD_OR_REPLACE, name) : List.of(ADD_OR_REPLACE, name, parent));
    }

    /**
     * Replays {@code clearChild}: replaces the named part with an empty one holding a copy of its
     * children.
     *
     * @param name the part cleared
     */
    void clearChild(@NotNull String name) {
        if (this.refusal.isPresent()) return;
        Optional<Part> holder = this.parentOf(name);
        if (holder.isEmpty()) {
            this.refuse("Cleared part '%s' is not in the tree", name);
            return;
        }
        replace(holder.get(), name);
        this.calls.add(List.of(CLEAR_CHILD, name));
    }

    /**
     * Replays {@code clearChild(name).clearRecursively()}: the named part and every part below it
     * replaced by an empty one, each holding a copy of its children.
     *
     * @param name the part whose subtree is emptied
     */
    void clearSubtree(@NotNull String name) {
        if (this.refusal.isPresent()) return;
        Optional<Part> holder = this.parentOf(name);
        if (holder.isEmpty()) {
            this.refuse("Cleared part '%s' is not in the tree", name);
            return;
        }
        clearRecursively(replace(holder.get(), name));
        this.calls.add(List.of(CLEAR_SUBTREE, name));
    }

    /**
     * Replays {@code retainPartsAndChildren} on the mesh root: every part not named is replaced by an
     * empty one and the walk goes on below it, and a named part is left whole.
     *
     * @param keep the parts kept with their children
     */
    void retainPartsAndChildren(@NotNull Set<String> keep) {
        if (this.refusal.isPresent()) return;
        retainPartsAndChildren(this.root, keep);
        this.calls.add(spelled(RETAIN_PARTS_AND_CHILDREN, keep));
    }

    /**
     * Replays {@code retainExactParts} on the mesh root: every part not named is replaced by an empty one
     * and the walk goes on below it, and a named part keeps its own cubes while everything below it is
     * cleared.
     *
     * @param keep the parts kept
     */
    void retainExactParts(@NotNull Set<String> keep) {
        if (this.refusal.isPresent()) return;
        retainExactParts(this.root, keep);
        this.calls.add(spelled(RETAIN_EXACT_PARTS, keep));
    }

    /**
     * Replays {@code MeshDefinition.transformed}: a fresh root holding a copy of the old root's
     * children.
     */
    void transformRoot() {
        if (this.refusal.isPresent()) return;
        this.root = this.root.copied();
        this.calls.add(List.of(TRANSFORMED));
    }

    /**
     * Replays {@code BabyModelTransform.apply}: a fresh root, each old top-level part put into it in the
     * old root's order as a fresh part holding a copy of its children.
     */
    void babyTransform() {
        if (this.refusal.isPresent()) return;
        Part rebuilt = new Part();
        this.root.children().forEach((name, part) -> rebuilt.children().put(name, part.copied()));
        this.root = rebuilt;
        this.calls.add(List.of(BABY_TRANSFORM));
    }

    /**
     * Lists every part in the order vanilla's baked model draws them: each part's children in its map's
     * order, a part before the parts below it, depth first.
     *
     * @return the part names in draw order
     */
    @NotNull List<String> order() {
        List<String> out = new ArrayList<>();
        visit(this.root, out);
        return out;
    }

    // ------------------------------------------------------------------------------------
    // the replay
    // ------------------------------------------------------------------------------------

    /** Replays one call read back off a parsed entry, through the method that made it. */
    private void replay(@NotNull List<String> call) {
        List<String> arguments = call.subList(1, call.size());
        switch (call.getFirst()) {
            case ADD_OR_REPLACE -> this.addOrReplace(arguments.size() > 1 ? arguments.get(1) : null, arguments.getFirst());
            case CLEAR_CHILD -> this.clearChild(arguments.getFirst());
            case CLEAR_SUBTREE -> this.clearSubtree(arguments.getFirst());
            case RETAIN_PARTS_AND_CHILDREN -> this.retainPartsAndChildren(Set.copyOf(arguments));
            case RETAIN_EXACT_PARTS -> this.retainExactParts(Set.copyOf(arguments));
            case TRANSFORMED -> this.transformRoot();
            case BABY_TRANSFORM -> this.babyTransform();
            default -> this.refuse("The part tree holds a call it does not replay: '%s'", call.getFirst());
        }
    }

    /** A call taking a set of names, spelled with the names sorted so the spelling is one per set. */
    private static @NotNull List<String> spelled(@NotNull String call, @NotNull Set<String> names) {
        List<String> spelled = new ArrayList<>();
        spelled.add(call);
        spelled.addAll(new TreeSet<>(names));
        return List.copyOf(spelled);
    }

    /** Puts a name under a part as {@code addOrReplaceChild} does, answering the part now under it. */
    private static @NotNull Part replace(@NotNull Part holder, @NotNull String name) {
        Part fresh = new Part();
        Part previous = holder.children().put(name, fresh);
        if (previous != null) fresh.children().putAll(previous.children());
        return fresh;
    }

    /** Replaces every part below one, in its map's order, as {@code clearRecursively} does. */
    private static void clearRecursively(@NotNull Part part) {
        for (String name : List.copyOf(part.children().keySet()))
            clearRecursively(replace(part, name));
    }

    /** Replays {@code retainPartsAndChildren} on one part. */
    private static void retainPartsAndChildren(@NotNull Part part, @NotNull Set<String> keep) {
        for (String name : List.copyOf(part.children().keySet()))
            if (!keep.contains(name)) retainPartsAndChildren(replace(part, name), keep);
    }

    /** Replays {@code retainExactParts} on one part. */
    private static void retainExactParts(@NotNull Part part, @NotNull Set<String> keep) {
        for (String name : List.copyOf(part.children().keySet())) {
            if (keep.contains(name)) clearRecursively(part.children().get(name));
            else retainExactParts(replace(part, name), keep);
        }
    }

    /** Writes each part's children below one, in its map's order, depth first. */
    private static void visit(@NotNull Part part, @NotNull List<String> out) {
        part.children().forEach((name, child) -> {
            out.add(name);
            visit(child, out);
        });
    }

    /** The part of a name, anywhere in the tree. */
    private @NotNull Optional<Part> find(@NotNull String name) {
        return this.parentOf(name).map(holder -> holder.children().get(name));
    }

    /** The part a name hangs from, anywhere in the tree. */
    private @NotNull Optional<Part> parentOf(@NotNull String name) {
        return parentOf(this.root, name);
    }

    /** The part a name hangs from, at or below one part. */
    private static @NotNull Optional<Part> parentOf(@NotNull Part part, @NotNull String name) {
        if (part.children().containsKey(name)) return Optional.of(part);
        for (Part child : part.children().values()) {
            Optional<Part> found = parentOf(child, name);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /**
     * One part of the tree, as a {@code PartDefinition} holds it.
     *
     * @param children the part's children by name, in a {@code HashMap} as vanilla's are
     */
    private record Part(@NotNull HashMap<String, Part> children) {

        /** A part with no children, as a fresh {@code PartDefinition} is. */
        Part() {
            this(new HashMap<>());
        }

        /**
         * A fresh part holding a copy of this one's children, as a replace, a transform and every
         * surgery make it - the parts below are the same parts, only the map holding them is new.
         */
        @NotNull Part copied() {
            Part copy = new Part();
            copy.children().putAll(this.children);
            return copy;
        }

    }

}
