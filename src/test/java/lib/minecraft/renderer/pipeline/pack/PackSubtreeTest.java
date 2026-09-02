package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * The shape of the one {@code (pack x root x namespace)} walk, read straight off {@link PackSubtree}
 * over synthetic packs rather than through a loader: what the erase reaches and what it spares, what
 * order the surviving files come back in, and the substring arithmetic {@link PackSubtree.Entry}
 * hands every caller.
 *
 * <p>These are the claims a loader test cannot see. A loader keys its accumulator, so it observes a
 * winner per id and never the file list underneath it; it walks one subtree, so it observes no
 * declaration order; and it holds one pack, so it never exercises the rule that a pack's own files
 * survive its own {@code filter.block}. Every one of them breaks silently - a pack still loads, with
 * a different set of files in it.
 *
 * <p>The fixtures are {@code @TempDir} directory packs whose file contents are never parsed, because
 * the walk reads only paths.
 */
@DisplayName("PackSubtree walk order, erase reach and entry arithmetic")
class PackSubtreeTest {

    /** the blockstate subtree, the shared shape so each test varies only what it pins */
    private static final PackSubtree.Subtree BLOCKSTATES = PackSubtree.Subtree.of("blockstates", ".json");

    /** the item-definition subtree, declared second where subtree order is the thing being pinned */
    private static final PackSubtree.Subtree ITEMS = PackSubtree.Subtree.of("items", ".json");

    @TempDir
    Path tmp;

    /**
     * Pins the negative half of the filter rule, which nothing else in this repository covers: the
     * erase runs over the files accumulated from lower packs and only then lists the filtering pack's
     * own, so a pack that filters {@code shared} still ships its own {@code shared}. Reading the
     * filter as a predicate over the merged listing instead deletes the pack's replacement along with
     * the file it was replacing, and the subject renders as if neither pack had shipped it.
     *
     * @throws IOException if a fixture pack cannot be written
     */
    @Test
    @DisplayName("a pack's own files are never hidden by its own filter.block")
    void ownFilesSurviveThePacksOwnFilter() throws IOException {
        Path van = this.tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/shared.json"), "{}");

        Path user = this.tmp.resolve("user");
        write(user.resolve("assets/minecraft/blockstates/shared.json"), "{}");
        ResourcePack userPack = pack(new PackId("userpack"), user,
            filtering("userpack", "{\"path\":\"shared\"}"), Set.of("minecraft"));

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, MCMeta.EMPTY, Set.of("minecraft")), userPack));

        List<PackSubtree.Entry> entries = PackSubtree.walk(stack, BLOCKSTATES);

        assertThat(entries, hasSize(1));
        assertThat("the lower pack's copy is erased, the filtering pack's own is not",
            entries.getFirst().pack().id(), is(new PackId("userpack")));
        assertThat("and the single-pack overload applies no filter at all, having nothing lower to erase",
            PackSubtree.walk(userPack, BLOCKSTATES), hasSize(1));
    }

    /**
     * Pins that the walk hands back files rather than a winner per id. Two packs shipping the same
     * blockstate yield two entries carrying one {@code resourcePath}, in pack-ascending order, and it
     * is each caller that decides what winning means - most take the last, one appends every file it
     * sees, and the blockstate loader falls back to a lower pack when the higher file is malformed.
     * Collapsing the walk to a map here would delete that fallback with no test failing.
     *
     * @throws IOException if a fixture pack cannot be written
     */
    @Test
    @DisplayName("every surviving file comes back, in resolution order, not a winner per id")
    void everyFileComesBackInResolutionOrder() throws IOException {
        Path van = this.tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/stone.json"), "{}");

        Path user = this.tmp.resolve("user");
        write(user.resolve("assets/minecraft/blockstates/stone.json"), "{}");

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, MCMeta.EMPTY, Set.of("minecraft")),
            pack(new PackId("userpack"), user, MCMeta.EMPTY, Set.of("minecraft"))));

        List<PackSubtree.Entry> entries = PackSubtree.walk(stack, BLOCKSTATES);

        assertThat(entries, hasSize(2));
        assertThat("packs ascending, so the last entry for an id is the winning pack's",
            entries.stream().map(entry -> entry.pack().id().value()).toList(),
            equalTo(List.of("vanilla", "userpack")));
        assertThat("both entries name one id; the walk keys nothing",
            entries.stream().map(PackSubtree.Entry::resourcePath).distinct().toList(),
            equalTo(List.of("blockstates/stone.json")));
    }

    /**
     * Pins the {@code sorted()} inside the listing as a determinism guarantee rather than a tidy-up.
     * The sort is over the container path, so it is code-point order: {@code Mid} and {@code Zeta}
     * come before {@code alpha}. A container enumerating in filesystem order would offer these three
     * in whatever order the directory happens to hold them - case-insensitive on NTFS, hash order on
     * ext4 - and with last-wins merging downstream that decides which file a colliding id resolves
     * to, differently per machine.
     *
     * @throws IOException if a fixture pack cannot be written
     */
    @Test
    @DisplayName("files list in code-point order, not the container's enumeration order")
    void listingIsSortedByContainerPath() throws IOException {
        Path van = this.tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/alpha.json"), "{}");
        write(van.resolve("assets/minecraft/blockstates/Mid.json"), "{}");
        write(van.resolve("assets/minecraft/blockstates/Zeta.json"), "{}");

        List<PackSubtree.Entry> entries = PackSubtree.walk(
            pack(PackId.VANILLA, van, MCMeta.EMPTY, Set.of("minecraft")), BLOCKSTATES);

        assertThat(entries.stream().map(PackSubtree.Entry::stem).toList(),
            equalTo(List.of("Mid", "Zeta", "alpha")));
    }

    /**
     * Pins the nesting of the two within-pack loops, which the walk's prose leaves open. The declared
     * subtrees are the outer loop and the pack's roots the inner one, so every file of the first
     * subtree - base root and overlay alike - precedes every file of the second, and a base file can
     * follow an overlay file. Inverting the two loops still satisfies "later roots win" and still
     * satisfies "subtrees in declaration order", yet hands a different list to every last-wins caller
     * that walks more than one subtree.
     *
     * <p>Also pins that the root is a prefix the resource path is measured past: the base and overlay
     * copies differ in {@code entryPath} and agree in {@code resourcePath}, which is what lets the
     * filter and the keying read one string whichever root supplied the file.
     *
     * @throws IOException if a fixture pack cannot be written
     */
    @Test
    @DisplayName("subtree declaration order is the outer loop and the pack's roots the inner one")
    void subtreesNestOutsideRoots() throws IOException {
        Path root = this.tmp.resolve("overlaid");
        write(root.resolve("assets/minecraft/blockstates/stone.json"), "{}");
        write(root.resolve("overlay_1/assets/minecraft/blockstates/stone.json"), "{}");
        write(root.resolve("assets/minecraft/items/stone.json"), "{}");

        ResourcePack overlaid = new ResourcePack(new PackId("overlaid"), new PackContainer.Directory(root),
            MCMeta.EMPTY, Concurrent.newList(PackRoot.BASE, PackRoot.overlay("overlay_1")).toUnmodifiable(),
            Concurrent.newUnmodifiableSet("minecraft"), Concurrent.newUnmodifiableSet(PackCapability.VANILLA_CORE));

        List<PackSubtree.Entry> entries = PackSubtree.walk(overlaid, BLOCKSTATES, ITEMS);

        assertThat(entries.stream().map(PackSubtree.Entry::entryPath).toList(), equalTo(List.of(
            "assets/minecraft/blockstates/stone.json",
            "overlay_1/assets/minecraft/blockstates/stone.json",
            "assets/minecraft/items/stone.json")));
        assertThat("the overlay copy is the later of the two, so a last-wins caller takes it",
            entries.get(1).pack().id(), is(new PackId("overlaid")));
        assertThat("and both copies carry one resource path, the root prefix measured off",
            entries.getFirst().resourcePath(), equalTo(entries.get(1).resourcePath()));
    }

    /**
     * Pins where in the ascending walk the erase happens: at the filtering pack's own turn, over
     * exactly what is accumulated by then. A middle pack's filter therefore takes the vanilla copy
     * and leaves the higher pack's, which is the whole difference between a filter and a stack-wide
     * ban. Hoisting the erase to a single post-pass over the finished list would silently delete the
     * top pack's file too.
     *
     * @throws IOException if a fixture pack cannot be written
     */
    @Test
    @DisplayName("a filter takes lower packs only, never one stacked above it")
    void filterReachesLowerPacksOnly() throws IOException {
        Path van = this.tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/target.json"), "{}");

        Path middle = this.tmp.resolve("middle");
        Files.createDirectories(middle.resolve("assets/minecraft"));

        Path top = this.tmp.resolve("top");
        write(top.resolve("assets/minecraft/blockstates/target.json"), "{}");

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, MCMeta.EMPTY, Set.of("minecraft")),
            pack(new PackId("middle"), middle, filtering("middle", "{\"path\":\"target\"}"), Set.of("minecraft")),
            pack(new PackId("top"), top, MCMeta.EMPTY, Set.of("minecraft"))));

        List<PackSubtree.Entry> entries = PackSubtree.walk(stack, BLOCKSTATES);

        assertThat(entries, hasSize(1));
        assertThat("the pack above the filter is untouched by it",
            entries.getFirst().pack().id(), is(new PackId("top")));
    }

    /** Writes one fixture file, creating its parent directories. */
    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    /** Builds a base-root directory pack. */
    private static ResourcePack pack(PackId id, Path root, MCMeta meta, Set<String> namespaces) {
        return new ResourcePack(id, new PackContainer.Directory(root), meta,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Concurrent.newUnmodifiableSet(namespaces),
            Concurrent.newUnmodifiableSet(PackCapability.VANILLA_CORE));
    }

    /** Parses a {@code pack.mcmeta} whose {@code filter.block} array holds the given entry objects. */
    private static MCMeta filtering(String packId, String entries) {
        return MCMeta.parse("{\"pack\":{\"pack_format\":84},\"filter\":{\"block\":[" + entries + "]}}",
            new ResourceId(packId, "pack"));
    }

}
