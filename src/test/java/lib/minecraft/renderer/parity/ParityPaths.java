package lib.minecraft.renderer.parity;

import dev.simplified.annotations.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * The one owner of the vanilla reference tree's path.
 *
 * <p>The version segment used to be a literal in five Gradle task bodies, six sweep mains and one
 * round-trip test with nothing linking them, so a Minecraft version bump was twelve string edits and
 * a missed one produced a sweep that silently found no ground truth and reported a clean tree. The
 * build now derives the segment from the harness's own {@code gradle.properties} and hands it down as
 * {@code asset.parity.references}; the default here is what lets a bare {@code java} invocation and a
 * fork that never saw the property agree.
 */
@UtilityClass
public final class ParityPaths {

    /**
     * The harness's reference tree - the byte-stable ground truth every parity sweep diffs against.
     *
     * <p>Set on every {@code Test} and {@code JavaExec} fork by the build. The committed default is
     * the same string the build derives, so the two cannot disagree on a checkout that has the
     * harness, and a checkout without it still resolves to whatever is on disk.
     */
    public static final @NotNull Path REFERENCES = Path.of(
        System.getProperty("asset.parity.references", "cache/asset-renderer/vanilla/26.1/references"));

    /**
     * Returns one sub-tree of the reference tree.
     *
     * @param subTree the sub-tree name - {@code blocks}, {@code items}, {@code entities},
     *     {@code players}, {@code glint} or {@code armor}
     * @return its directory
     */
    public static @NotNull Path references(@NotNull String subTree) {
        return REFERENCES.resolve(subTree);
    }

}
