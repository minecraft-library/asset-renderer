package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.BlockTag;
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
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * The recursive half of {@link BlockTagLoader} - the flatten that turns a tag file's mixed list of
 * block ids and {@code #} tag references into concrete ids, and the keying the lookup depends on.
 *
 * <p>Resolution is worth pinning because three of its rules are only visible in the output. A
 * reference expands <b>in place</b>, so a referenced tag's members land between the ids written
 * either side of it rather than appended; a repeat is dropped by a membership test on the running
 * list, so a block reachable two ways appears once, at its first position; and the guard against
 * re-entering a tag is what makes a cycle terminate rather than overflow.
 *
 * <p>The lookup is by the exact string left after the {@code #} is stripped, and tag ids are keyed
 * {@code <namespace>:<stem>}. A reference must therefore spell the namespace, and vanilla's shipped
 * block tags do - every {@code #} entry in them is namespaced. A bare one resolves to nothing and is
 * pinned here as the silent answer it is, alongside the reference to a tag that does not exist at
 * all, which resolves the same way.
 *
 * <p>The pack-stack half is a replace rather than a value merge: a higher pack's file substitutes the
 * lower one's whole list. The one case that is not a replace is a higher file the reader cannot use -
 * malformed, or carrying no {@code values} - which is skipped, leaving the lower pack's entry
 * standing.
 */
@DisplayName("BlockTagLoader flatten and keying")
class BlockTagLoaderTest {

    @TempDir
    Path tmp;

    /** Writes one tag file under a pack root's {@code data/minecraft/tags/block} directory. */
    private static void tag(Path packRoot, String stem, String json) throws IOException {
        Path file = packRoot.resolve("data/minecraft/tags/block").resolve(stem + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    /** A single-pack (vanilla) stack rooted at the given directory. */
    private static PackStack stackOf(Path packRoot) {
        return PackStack.of(Concurrent.newList(pack(PackId.VANILLA, packRoot)));
    }

    /** One directory-backed pack contributing the vanilla namespace at its base root. */
    private static ResourcePack pack(PackId id, Path root) {
        return new ResourcePack(id, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Set.of("minecraft"),
            Set.of(PackCapability.VANILLA_CORE));
    }

    /** A {@code values} document holding the given entries verbatim. */
    private static String values(String... entries) {
        return "{\"values\":[\"" + String.join("\",\"", entries) + "\"]}";
    }

    /**
     * Asserts a referenced tag's members land where the reference was written, between the ids
     * either side of it, rather than being appended after them.
     */
    @Test
    @DisplayName("a tag reference expands in place, not at the end")
    void aTagReferenceExpandsInPlace() throws IOException {
        tag(this.tmp, "parent", values("minecraft:a", "#minecraft:child", "minecraft:b"));
        tag(this.tmp, "child", values("minecraft:c", "minecraft:d"));

        ConcurrentMap<String, BlockTag> tags = BlockTagLoader.load(stackOf(this.tmp));
        assertThat(tags.get("minecraft:parent").values(),
            contains("minecraft:a", "minecraft:c", "minecraft:d", "minecraft:b"));
        assertThat(tags.get("minecraft:child").values(), contains("minecraft:c", "minecraft:d"));
        assertThat(tags.get("minecraft:parent").id(), is(equalTo(new ResourceId("minecraft", "parent"))));
    }

    /**
     * Asserts a block reachable both directly and through a reference is kept once, at the first
     * position it was seen - the membership test runs against the list built so far, so the later
     * copy is the one dropped.
     */
    @Test
    @DisplayName("a block reachable twice keeps only its first position")
    void aBlockReachableTwiceKeepsOnlyItsFirstPosition() throws IOException {
        tag(this.tmp, "parent", values("minecraft:x", "#minecraft:child", "minecraft:y"));
        tag(this.tmp, "child", values("minecraft:x", "minecraft:z"));

        ConcurrentMap<String, BlockTag> tags = BlockTagLoader.load(stackOf(this.tmp));
        assertThat(tags.get("minecraft:parent").values(),
            contains("minecraft:x", "minecraft:z", "minecraft:y"));
    }

    /**
     * Asserts two tags referencing each other both resolve, terminating on the re-entry guard and
     * still collecting every member reachable from the tag being flattened.
     */
    @Test
    @DisplayName("a reference cycle terminates and still collects every reachable member")
    void aReferenceCycleTerminatesAndCollectsEveryMember() throws IOException {
        tag(this.tmp, "a", values("#minecraft:b", "minecraft:x"));
        tag(this.tmp, "b", values("#minecraft:a", "minecraft:y"));

        ConcurrentMap<String, BlockTag> tags = BlockTagLoader.load(stackOf(this.tmp));
        assertThat(tags.get("minecraft:a").values(), containsInAnyOrder("minecraft:x", "minecraft:y"));
        assertThat(tags.get("minecraft:b").values(), containsInAnyOrder("minecraft:x", "minecraft:y"));
    }

    /**
     * Asserts both ways a reference can miss resolve to nothing rather than to a diagnostic: a tag
     * that does not exist, and a reference spelled without the namespace the id is keyed under.
     * Neither drops the surrounding entries.
     */
    @Test
    @DisplayName("an unknown reference and a namespace-less one both resolve to nothing")
    void anUnknownOrNamespacelessReferenceResolvesToNothing() throws IOException {
        tag(this.tmp, "stairs", values("minecraft:oak_stairs"));
        tag(this.tmp, "mixed", values("#stairs", "#minecraft:missing", "minecraft:kept"));

        ConcurrentMap<String, BlockTag> tags = BlockTagLoader.load(stackOf(this.tmp));
        assertThat(tags.get("minecraft:mixed").values(), contains("minecraft:kept"));
        assertThat(tags.get("minecraft:stairs").values(), contains("minecraft:oak_stairs"));
    }

    /**
     * Asserts a tag file in a sub-directory keeps its path segments in the tag id, so the id's name
     * half carries the slash and a reference has to spell it the same way.
     */
    @Test
    @DisplayName("a tag file in a sub-directory keeps its path segments in the id")
    void aTagFileInASubDirectoryKeepsItsPathSegments() throws IOException {
        tag(this.tmp, "wood/oak", values("minecraft:oak_planks"));
        tag(this.tmp, "planks", values("#minecraft:wood/oak"));

        ConcurrentMap<String, BlockTag> tags = BlockTagLoader.load(stackOf(this.tmp));
        assertThat(tags.get("minecraft:wood/oak").id(), is(equalTo(new ResourceId("minecraft", "wood/oak"))));
        assertThat(tags.get("minecraft:planks").values(), contains("minecraft:oak_planks"));
    }

    /**
     * Asserts the two ways a tag can carry no members are not the same answer - a file with no
     * {@code values} array never enters the map at all, where an empty array produces a tag whose
     * member list is empty.
     */
    @Test
    @DisplayName("a file with no values array is absent where an empty array is a present empty tag")
    void aFileWithNoValuesArrayIsAbsentWhereAnEmptyArrayIsPresent() throws IOException {
        tag(this.tmp, "ghost", "{\"replace\":false}");
        tag(this.tmp, "hollow", "{\"values\":[]}");
        tag(this.tmp, "real", values("minecraft:stone"));

        ConcurrentMap<String, BlockTag> tags = BlockTagLoader.load(stackOf(this.tmp));
        assertThat(tags.containsKey("minecraft:ghost"), is(false));
        assertThat(tags.containsKey("minecraft:hollow"), is(true));
        assertThat(tags.get("minecraft:hollow").values(), is(empty()));
        assertThat(tags.get("minecraft:real").values(), contains("minecraft:stone"));
    }

    /**
     * Asserts a higher pack's file substitutes the lower one's whole member list rather than adding
     * to it, so an id the lower pack listed is gone unless the higher file lists it too.
     */
    @Test
    @DisplayName("a higher pack replaces the whole member list rather than merging into it")
    void aHigherPackReplacesTheWholeMemberList() throws IOException {
        Path vanilla = this.tmp.resolve("vanilla");
        Path user = this.tmp.resolve("user");
        tag(vanilla, "t", values("minecraft:a"));
        tag(user, "t", values("minecraft:b"));

        ConcurrentMap<String, BlockTag> tags = BlockTagLoader.load(stackOf(vanilla, user));
        assertThat(tags.get("minecraft:t").values(), contains("minecraft:b"));
    }

    /**
     * Asserts a higher file the reader cannot use is skipped rather than shadowing, so the lower
     * pack's entry stands - both for a truncated file and for one carrying no {@code values} array.
     */
    @Test
    @DisplayName("a malformed or values-less higher file leaves the lower entry standing")
    void aMalformedOrValuelessHigherFileLeavesTheLowerEntryStanding() throws IOException {
        Path vanilla = this.tmp.resolve("vanilla");
        Path user = this.tmp.resolve("user");
        tag(vanilla, "truncated", values("minecraft:a"));
        tag(vanilla, "valueless", values("minecraft:a"));
        tag(user, "truncated", "{\"values\":[\"minecraft:b\"");
        tag(user, "valueless", "{\"replace\":true}");

        ConcurrentMap<String, BlockTag> tags = BlockTagLoader.load(stackOf(vanilla, user));
        assertThat(tags.get("minecraft:truncated").values(), contains("minecraft:a"));
        assertThat(tags.get("minecraft:valueless").values(), contains("minecraft:a"));
    }

    /**
     * Asserts the flatten runs once over the merged map rather than per pack, so a tag a higher pack
     * introduces still resolves through a reference whose target only the lower pack ships.
     */
    @Test
    @DisplayName("a higher pack's new tag resolves through a reference only the lower pack ships")
    void aHigherPackTagResolvesThroughALowerPackReference() throws IOException {
        Path vanilla = this.tmp.resolve("vanilla");
        Path user = this.tmp.resolve("user");
        tag(vanilla, "base", values("minecraft:stone", "minecraft:granite"));
        tag(user, "top", values("#minecraft:base", "minecraft:tuff"));

        ConcurrentMap<String, BlockTag> tags = BlockTagLoader.load(stackOf(vanilla, user));
        assertThat(tags.get("minecraft:top").values(),
            contains("minecraft:stone", "minecraft:granite", "minecraft:tuff"));
    }

    /** A two-pack stack with the user pack above the vanilla one. */
    private static PackStack stackOf(Path vanillaRoot, Path userRoot) {
        return PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, vanillaRoot),
            pack(new PackId("userpack"), userRoot)));
    }

}
