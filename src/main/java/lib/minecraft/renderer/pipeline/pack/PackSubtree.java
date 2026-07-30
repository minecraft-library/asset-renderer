package lib.minecraft.renderer.pipeline.pack;

import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The one {@code (pack x root x namespace)} walk of an assets subtree across a pack stack, and the
 * one place a pack's {@code filter.block} section is honoured.
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
     * One assets subtree to walk.
     *
     * @param subdir the subtree under {@code assets/<namespace>/} ({@code blockstates}, {@code models/block})
     * @param extension the file extension to match, leading dot included ({@code .json})
     * @param appliesTo which packs contribute this subtree - all of them, unless a caller narrows it
     */
    public record Subtree(
        @NotNull String subdir,
        @NotNull String extension,
        @NotNull Predicate<ResourcePack> appliesTo
    ) {

        /**
         * A subtree every pack contributes.
         *
         * @param subdir the subtree under {@code assets/<namespace>/}
         * @param extension the file extension to match, leading dot included
         * @return the subtree
         */
        public static @NotNull Subtree of(@NotNull String subdir, @NotNull String extension) {
            return new Subtree(subdir, extension, pack -> true);
        }

        /**
         * A subtree only some packs contribute - the legacy {@code models/item} overrides, which
         * only a pre-format-46 pack carries.
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
            return new Subtree(subdir, extension, appliesTo);
        }

    }

    /**
     * One surviving file: enough to read it, to key it, and to say which pack it came from.
     *
     * @param pack the pack the file was found in
     * @param subtree the subtree it was listed under
     * @param namespace the namespace it lives in
     * @param entryPath the container-relative path to hand {@link PackContainer#bytes}
     * @param resourcePath the path relative to {@code assets/<namespace>/}, extension included
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
    public static @NotNull List<Entry> walk(@NotNull PackStack stack, @NotNull Subtree @NotNull ... subtrees) {
        List<Entry> surviving = new ArrayList<>();

        for (ResourcePack pack : stack.ascending()) {
            pack.meta().pack().ifPresent(section ->
                surviving.removeIf(entry -> section.hidesFile(entry.namespace(), entry.resourcePath())));

            for (Subtree subtree : subtrees) {
                if (!subtree.appliesTo().test(pack)) continue;

                for (PackRoot root : pack.roots())
                    for (String namespace : pack.namespaces())
                        list(pack, subtree, root, namespace, surviving);
            }
        }

        return surviving;
    }

    /**
     * Lists one {@code (root x namespace)} subtree onto the running list. Sorted, so a container
     * that enumerates in filesystem order still resolves deterministically.
     */
    private static void list(
        @NotNull ResourcePack pack,
        @NotNull Subtree subtree,
        @NotNull PackRoot root,
        @NotNull String namespace,
        @NotNull List<Entry> out
    ) {
        String namespaceRoot = root.prefix() + VanillaSourcePaths.assetSubdir(namespace, "");
        String prefix = root.prefix() + VanillaSourcePaths.assetSubdir(namespace, subtree.subdir());

        List<String> files = pack.container().entries(prefix)
            .filter(path -> path.endsWith(subtree.extension()))
            .sorted()
            .toList();

        for (String file : files)
            out.add(new Entry(pack, subtree, namespace, file, file.substring(namespaceRoot.length())));
    }

}
