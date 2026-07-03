package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.rule.CtmMethod;
import lib.minecraft.renderer.asset.rule.CtmRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Verifies {@link CtmLoader} against {@link TempDir}-staged OptiFine {@code .properties} rules.
 * <p>
 * The multi-root {@code load(ConcurrentList<Path>)} overload must merge rules from every root and
 * produce a globally weight-descending list; the single-root {@code load(Path)} overload must parse
 * a {@code method=fixed} rule into {@link CtmMethod#FIXED} with its {@code tiles}. The single-root
 * overload is otherwise exercised indirectly through this test plus the matcher tests.
 */
class CtmLoaderTest {

    @Test
    @DisplayName("multi-root overload merges and weight-sorts rules across every root")
    void multiRootMergesAndSorts(@TempDir Path baseDir) throws IOException {
        Path rootA = baseDir.resolve("packA");
        Path rootB = baseDir.resolve("packB");
        writeCtmRule(rootA, "stone.properties",
            "method=fixed\nweight=5\nmatchBlocks=minecraft:stone\ntiles=pack_a/stone_a\n");
        writeCtmRule(rootB, "bricks.properties",
            "method=fixed\nweight=20\nmatchBlocks=minecraft:stone_bricks\ntiles=pack_b/bricks\n");
        writeCtmRule(rootA, "cobble.properties",
            "method=fixed\nweight=10\nmatchBlocks=minecraft:cobblestone\ntiles=pack_a/cobble\n");

        ConcurrentList<CtmRule> rules = CtmLoader.load(Concurrent.newList(rootA, rootB));

        assertThat(rules.size(), equalTo(3));
        // Descending weight: 20, 10, 5
        assertThat(rules.get(0).weight(), equalTo(20));
        assertThat(rules.get(0).matchedBlocks().get(0), equalTo("minecraft:stone_bricks"));
        assertThat(rules.get(1).weight(), equalTo(10));
        assertThat(rules.get(1).matchedBlocks().get(0), equalTo("minecraft:cobblestone"));
        assertThat(rules.get(2).weight(), equalTo(5));
        assertThat(rules.get(2).matchedBlocks().get(0), equalTo("minecraft:stone"));
    }

    @Test
    @DisplayName("multi-root overload returns empty when no root has CTM files")
    void multiRootHandlesEmptyRoots(@TempDir Path baseDir) throws IOException {
        Path empty = baseDir.resolve("empty");
        Files.createDirectories(empty);
        ConcurrentList<CtmRule> rules = CtmLoader.load(Concurrent.newList(empty));
        assertThat(rules.isEmpty(), is(true));
    }

    @Test
    @DisplayName("single-root overload still parses fixed-method rules with method enum")
    void singleRootParsesFixedMethod(@TempDir Path packRoot) throws IOException {
        writeCtmRule(packRoot, "stone.properties",
            "method=fixed\nmatchBlocks=minecraft:stone\ntiles=pack/stone_custom\n");
        ConcurrentList<CtmRule> rules = CtmLoader.load(packRoot);
        assertThat(rules.size(), equalTo(1));
        assertThat(rules.get(0).method(), is(CtmMethod.FIXED));
        assertThat(rules.get(0).tiles().get(0), equalTo("pack/stone_custom"));
    }

    /**
     * Writes an OptiFine CTM {@code .properties} rule into the canonical
     * {@code assets/minecraft/optifine/ctm} directory under {@code packRoot}, creating it if absent.
     *
     * @param packRoot pack root the rule is staged under
     * @param filename properties file name (e.g. {@code "stone.properties"})
     * @param content raw properties body
     * @throws IOException if creating the directory or writing the file fails
     */
    private static void writeCtmRule(Path packRoot, String filename, String content) throws IOException {
        Path ctmDir = packRoot.resolve("assets/minecraft/optifine/ctm");
        Files.createDirectories(ctmDir);
        Files.writeString(ctmDir.resolve(filename), content);
    }

}
