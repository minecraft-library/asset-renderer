package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.pack.item.ItemModelTree;
import lib.minecraft.renderer.pipeline.pack.item.ItemModelTreeLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * What a {@code pack.mcmeta} {@code filter.block} section means, every part of it read off the 26.1
 * client rather than inferred: which string the path pattern is tested against, how it is matched
 * against that string, which subtrees the filter reaches, and how a multi-entry section combines.
 * Each was wrong in a way no sweep could see - a filter cannot fire on a stack whose packs declare
 * none, and no pack fixture in this repo declares one.
 *
 * <p>The authorities, all in {@code cache/dragon-extract}: {@code MultiPackResourceManager} installs
 * one predicate per pack, {@code FallbackResourceManager} applies it to the listing accumulated from
 * lower packs, {@code ResourceFilterSection} answers the two halves independently, and
 * {@code IdentifierPattern} compiles an absent pattern to a constant-true predicate.
 */
@DisplayName("pack filter matches the vanilla client")
class PackSubtreeFilterTest {

    @TempDir
    Path tmp;

    /**
     * The operand. The client's predicate is {@code id -> section.isPathFiltered(id.getPath())}, and
     * the identifier it receives is the file: {@code PathPackResources} builds it by relativising
     * against {@code <pack>/assets/<namespace>}, so the string is {@code blockstates/hideme.json} -
     * subdirectory and extension included, not the bare {@code hideme} a parsed row is keyed by. A
     * pack author anchoring on the subdirectory is writing the documented form, and it must work.
     */
    @Test
    @DisplayName("the path pattern is tested against the file path, subdirectory and extension included")
    void pathPatternTestsTheFilePath() throws IOException {
        Path van = this.tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/keepme.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/keepme\"}}}");
        write(van.resolve("assets/minecraft/blockstates/hideme.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/hideme\"}}}");

        BlockStateLoader.BlockStates result = BlockStateLoader.load(stackWith(van, "{\"path\":\"^blockstates/hideme\\\\.json$\"}"));

        assertThat(result.variants().containsKey("minecraft:keepme"), is(true));
        assertThat("anchored on the subdirectory, as vanilla is",
            result.variants().containsKey("minecraft:hideme"), is(false));
    }

    /**
     * The match, over the same operand. {@code IdentifierPattern} turns the compiled pattern into a
     * predicate through {@code Pattern.asPredicate}, which is defined as
     * {@code s -> matcher(s).find()}, so an unanchored fragment takes a row with neither the
     * subdirectory nor the extension spelled out. Reading it as an anchored full match leaves every
     * pack writing that short form unfiltered.
     */
    @Test
    @DisplayName("an unanchored pattern matches anywhere in the file path")
    void unanchoredPatternMatchesAnywhereInThePath() throws IOException {
        Path van = this.tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/keepme.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/keepme\"}}}");
        write(van.resolve("assets/minecraft/blockstates/hideme.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/hideme\"}}}");

        BlockStateLoader.BlockStates result = BlockStateLoader.load(stackWith(van, "{\"path\":\"hideme\"}"));

        assertThat(result.variants().containsKey("minecraft:keepme"), is(true));
        assertThat("a bare fragment takes the row, no anchor needed",
            result.variants().containsKey("minecraft:hideme"), is(false));
    }

    /**
     * The reach. The client installs the filter once per pack at the resource manager, where it is
     * blind to what is being listed - {@code ResourceFilterSection} is referenced by exactly two
     * classes in the whole client, and neither knows the subtree. Equipment assets list through the
     * same path as blockstates, so a filter that skipped them would be this renderer's invention.
     */
    @Test
    @DisplayName("the filter reaches the equipment subtree")
    void filterReachesEquipment() throws IOException {
        Path van = this.tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/equipment/keepme.json"), "{\"layers\":{}}");
        write(van.resolve("assets/minecraft/equipment/hideme.json"), "{\"layers\":{}}");

        Map<ResourceId, ?> result = EquipmentModelLoader.load(stackWith(van, "{\"path\":\"^equipment/hideme\\\\.json$\"}"));

        assertThat(result.containsKey(new ResourceId("minecraft", "keepme")), is(true));
        assertThat(result.containsKey(new ResourceId("minecraft", "hideme")), is(false));
    }

    /** The same reach, over the item-definition subtree, whose erase had no coverage at all. */
    @Test
    @DisplayName("the filter reaches the item-definition subtree")
    void filterReachesItemTrees() throws IOException {
        Path van = this.tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/items/keepme.json"), "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/keepme\"}}");
        write(van.resolve("assets/minecraft/items/hideme.json"), "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/hideme\"}}");

        ConcurrentMap<String, ItemModelTree> result = ItemModelTreeLoader.load(stackWith(van, "{\"path\":\"^items/hideme\\\\.json$\"}"));

        assertThat(result.containsKey("minecraft:keepme"), is(true));
        assertThat(result.containsKey("minecraft:hideme"), is(false));
    }

    /**
     * The combinator, and the widest of the three. {@code ResourceFilterSection} exposes
     * {@code isNamespaceFiltered} and {@code isPathFiltered} as two <b>independent</b>
     * {@code anyMatch} passes over the whole list, each testing only its own half of each entry, and
     * an absent pattern compiles to constant-true. The per-entry conjunction does exist in the
     * client - {@code IdentifierPattern.locationPredicate} - but pack filtering never calls it; its
     * only reader is the {@code minecraft:filter} atlas sprite source.
     * <p>
     * So a section with a path-less entry has a constant-true path half, and every file below is
     * hidden. Reading the two halves as a per-entry conjunction instead hides a narrow slice, which
     * is a far smaller set than the pack asked for.
     */
    @Test
    @DisplayName("an entry omitting path makes the whole section hide everything below")
    void pathlessEntryHidesEverything() throws IOException {
        Path van = this.tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/alpha.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/alpha\"}}}");
        write(van.resolve("assets/minecraft/blockstates/beta.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/beta\"}}}");

        // Entry 1 declares a namespace and no path; entry 2 a path and no namespace. The namespace
        // half is true via entry 2's absent namespace, so the filter installs for minecraft; the path
        // half is then true via entry 1's absent path, so it takes everything.
        BlockStateLoader.BlockStates result = BlockStateLoader.load(
            stackWith(van, "{\"namespace\":\"nomatch\"},{\"path\":\"alpha\"}"));

        assertThat(result.variants().containsKey("minecraft:alpha"), is(false));
        assertThat("and beta too, via the path-less entry",
            result.variants().containsKey("minecraft:beta"), is(false));
    }

    /** Builds a two-pack stack: an unfiltered vanilla pack, then an empty pack carrying the filter. */
    private PackStack stackWith(Path vanillaRoot, String filterEntries) throws IOException {
        Path user = this.tmp.resolve("user");
        Files.createDirectories(user.resolve("assets/minecraft"));
        MCMeta filtering = MCMeta.parse(
            "{\"pack\":{\"pack_format\":84},\"filter\":{\"block\":[" + filterEntries + "]}}",
            new ResourceId("userpack", "pack"));

        return PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, vanillaRoot, MCMeta.EMPTY),
            pack(new PackId("userpack"), user, filtering)));
    }

    private static ResourcePack pack(PackId id, Path root, MCMeta meta) {
        return new ResourcePack(id, new PackContainer.Directory(root), meta,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Concurrent.newUnmodifiableSet("minecraft"),
            Concurrent.newUnmodifiableSet(PackCapability.VANILLA_CORE));
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

}
