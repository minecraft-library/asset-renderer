package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.client.VanillaSourcePaths;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The one {@code (pack x root x namespace)} walk of a pack subtree, under either the {@code assets}
 * root or the {@code data} one, and the one place a pack's {@code filter.block} section is honoured.
 *
 * <p>Both halves of that sentence are the point. The walk was written out seven times, and the
 * copies had drifted into three different behaviours: four applied the filter, three did not, and
 * the four that did each tested it against their own key shape. Sharing the walk is what makes the
 * filter one statement rather than six that can disagree.
 *
 * <p><b>The unit is the file, not the parsed row.</b> That is what lets one walk serve loaders whose
 * accumulators disagree - a keyed map, a pair of keyed maps, an attributed map, an ordered list -
 * and it is the level the client filters at: {@code FallbackResourceManager.listResources} calls
 * {@code removeIf} on the keys accumulated from lower packs and only then lists the pack's own, so a
 * hidden file is never opened. It also fixes what the filter is tested against. The client's
 * predicate is {@code id -> section.isPathFiltered(id.getPath())} over an identifier built by
 * relativising the file against {@code <pack>/assets/<namespace>}, so it reads
 * {@code blockstates/stone.json} - subdirectory and extension included. A loader holding parsed rows
 * can only offer {@code stone}, which is why the erase could not be made faithful without moving it
 * here.
 *
 * <p>The walk hands back <b>every</b> surviving file in resolution order rather than only the winner
 * per id, because its callers do not agree on what winning means: most take the last file for an id,
 * one appends every file it sees, and the blockstate loader deliberately falls back to a lower pack
 * when the higher file is malformed. Ordering the files and letting each caller keep its own merge
 * rule is what makes this a filter fix rather than a merge-semantics change.
 *
 * <p>Within one pack, later roots win over earlier ones (base first, overlays after) and subtrees
 * are listed in the order the caller declares them; across packs, later packs win. A pack's own
 * files are never hidden by its own filter, matching the client, where the erase runs over the
 * accumulated lower-pack keys before that pack contributes.
 */
@UtilityClass
public class PackSubtree {

    /**
     * One subtree to walk.
     *
     * @param root the pack-relative root the subtree lives under - {@link VanillaSourcePaths#ASSETS_ROOT}
     *     for a client resource, {@link VanillaSourcePaths#DATA_ROOT} for a registry file. Vanilla names
     *     the pair on its {@code PackType}, where the constant's payload <em>is</em> the directory
     * @param subdir the subtree under {@code <root>/<namespace>/} ({@code blockstates}, {@code tags/block})
     * @param extension the file extension to match, leading dot included ({@code .json})
     * @param namespace the single namespace to scan, or empty to scan every namespace the pack declares
     * @param appliesTo which packs contribute this subtree - all of them, unless a caller narrows it
     */
    public record Subtree(
        @NotNull String root,
        @NotNull String subdir,
        @NotNull String extension,
        @NotNull Optional<String> namespace,
        @NotNull Predicate<ResourcePack> appliesTo
    ) {

        /**
         * An assets subtree every pack contributes, scanned across every namespace the pack declares.
         *
         * @param subdir the subtree under {@code assets/<namespace>/}
         * @param extension the file extension to match, leading dot included
         * @return the subtree
         */
        public static @NotNull Subtree of(@NotNull String subdir, @NotNull String extension) {
            return new Subtree(VanillaSourcePaths.ASSETS_ROOT, subdir, extension, Optional.empty(), pack -> true);
        }

        /**
         * An assets subtree only some packs contribute - the legacy {@code models/item} overrides,
         * which only a pre-format-46 pack carries.
         *
         * @param subdir the subtree under {@code assets/<namespace>/}
         * @param extension the file extension to match, leading dot included
         * @param appliesTo which packs contribute it
         * @return the subtree
         */
        public static @NotNull Subtree gated(
            @NotNull String subdir,
            @NotNull String extension,
            @NotNull Predicate<ResourcePack> appliesTo
        ) {
            return new Subtree(VanillaSourcePaths.ASSETS_ROOT, subdir, extension, Optional.empty(), appliesTo);
        }

        /**
         * An assets subtree at one named namespace - for a convention that only ever lives under a
         * fixed one, such as the OptiFine rule trees under {@code assets/minecraft/optifine}.
         *
         * @param namespace the namespace directory to scan under {@code assets/}
         * @param subdir the subtree under {@code assets/<namespace>/}
         * @param extension the file extension to match, leading dot included
         * @return the subtree
         */
        public static @NotNull Subtree assets(
            @NotNull String namespace,
            @NotNull String subdir,
            @NotNull String extension
        ) {
            return new Subtree(VanillaSourcePaths.ASSETS_ROOT, subdir, extension, Optional.of(namespace), pack -> true);
        }

        /**
         * A data subtree at one named namespace.
         * <p>
         * The namespace is named rather than enumerated because {@code ResourcePack.namespaces()} is
         * discovered from the directories under {@code assets/} alone - it is the wrong set for a data
         * walk in both directions, and a loader that derives its ids under a fixed namespace would key
         * a foreign one's files wrongly if it were handed that set.
         *
         * @param namespace the namespace directory to scan under {@code data/}
         * @param subdir the subtree under {@code data/<namespace>/}
         * @param extension the file extension to match, leading dot included
         * @return the subtree
         */
        public static @NotNull Subtree data(
            @NotNull String namespace,
            @NotNull String subdir,
            @NotNull String extension
        ) {
            return new Subtree(VanillaSourcePaths.DATA_ROOT, subdir, extension, Optional.of(namespace), pack -> true);
        }

    }

    /**
     * One surviving file: enough to read it, to key it, and to say which pack it came from.
     *
     * @param pack the pack the file was found in
     * @param subtree the subtree it was listed under
     * @param namespace the namespace it lives in
     * @param entryPath the container-relative path to hand {@link PackContainer#bytes}
     * @param resourcePath the path relative to {@code <root>/<namespace>/}, extension included
     */
    public record Entry(
        @NotNull ResourcePack pack,
        @NotNull Subtree subtree,
        @NotNull String namespace,
        @NotNull String entryPath,
        @NotNull String resourcePath
    ) {

        /** The container the file is read from. */
        public @NotNull PackContainer container() {
            return this.pack.container();
        }

        /**
         * The file's stem - its path with the subtree prefix and the extension removed, which is
         * what every caller keys by ({@code stone} under {@code blockstates}, {@code oak_planks}
         * under {@code models/block}).
         *
         * @return the stem between prefix and extension
         */
        public @NotNull String stem() {
            return this.resourcePath.substring(
                this.subtree.subdir().length() + 1,
                this.resourcePath.length() - this.subtree.extension().length());
        }

        /** Whether the stem names a file directly under the subtree rather than in a sub-directory. */
        public boolean isDirectChild() {
            return stem().indexOf('/') < 0;
        }

    }

    /**
     * Walks one or more assets subtrees across the whole stack, packs ascending, and returns every
     * surviving file in resolution order.
     * <p>
     * Before a pack's own files are listed, every already-accumulated file its {@code filter.block}
     * section hides is dropped; then each declared subtree is listed over that pack's
     * {@code (root x namespace)} grid.
     *
     * @param stack the resolved pack stack
     * @param subtrees the subtrees to walk, listed per pack in this order
     * @return the surviving files, in resolution order
     */
    public static @NotNull ConcurrentList<Entry> walk(@NotNull PackStack stack, @NotNull Subtree @NotNull ... subtrees) {
        List<Entry> surviving = new ArrayList<>();

        for (ResourcePack pack : stack.ascending()) {
            pack.meta().pack().ifPresent(section -> surviving.removeIf(entry -> section.hidesFile(entry.namespace(), entry.resourcePath())));
            surviving.addAll(walk(pack, subtrees));
        }

        return Concurrent.adoptList(surviving).toUnmodifiable();
    }

    /**
     * Walks one pack's own {@code (root x namespace)} grid, in the order its roots and the declared
     * subtrees give.
     * <p>
     * No filter is applied here, and that is the client's rule rather than an omission: a pack's own
     * files are never hidden by its own {@code filter.block}, which only erases what lower packs
     * contributed. The erase therefore belongs to the stack form, which is the only level that has a
     * lower pack to erase from.
     *
     * @param pack the pack to list
     * @param subtrees the subtrees to walk, listed in this order
     * @return that pack's files, in resolution order
     */
    public static @NotNull ConcurrentList<Entry> walk(@NotNull ResourcePack pack, @NotNull Subtree @NotNull ... subtrees) {
        return Arrays.stream(subtrees)
            .filter(subtree -> subtree.appliesTo().test(pack))
            .flatMap(subtree -> pack.roots()
                .stream()
                .flatMap(root -> subtree.namespace()
                    .<Collection<String>>map(List::of)
                    .orElseGet(pack::namespaces)
                    .stream()
                    .flatMap(namespace -> list(pack, subtree, root, namespace))))
            .collect(Concurrent.toWideUnmodifiableList());
    }

    /**
     * The files one {@code (root x namespace)} subtree contributes. Sorted, so a container that
     * enumerates in filesystem order still resolves deterministically.
     */
    private static @NotNull Stream<Entry> list(
        @NotNull ResourcePack pack,
        @NotNull Subtree subtree,
        @NotNull PackRoot root,
        @NotNull String namespace
    ) {
        String namespaceRoot = root.prefix() + VanillaSourcePaths.subtreeDir(subtree.root(), namespace, "");
        String prefix = root.prefix() + VanillaSourcePaths.subtreeDir(subtree.root(), namespace, subtree.subdir());

        return pack.container().entries(prefix)
            .filter(path -> path.endsWith(subtree.extension()))
            .sorted()
            .map(file -> new Entry(pack, subtree, namespace, file, file.substring(namespaceRoot.length())));
    }

}
