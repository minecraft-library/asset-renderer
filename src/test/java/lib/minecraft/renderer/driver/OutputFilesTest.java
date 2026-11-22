package lib.minecraft.renderer.driver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Holds an output name to one path segment directly inside the directory it is written to.
 *
 * <p>The drivers and the sweeps that take a subject id from the command line build a file or
 * directory name from it and resolve that name here, so these cases are what keeps their output in
 * the directory each one names.
 */
@DisplayName("An output name resolves directly inside its directory or not at all")
final class OutputFilesTest {

    /** The directory every case resolves against; nothing is written into it. */
    @TempDir
    Path directory;

    @Test
    @DisplayName("a plain name resolves to the path a bare resolve gives, so no output moves")
    void aPlainNameResolvesAsItAlwaysDid() {
        for (String name : List.of("minecraft_tnt.png", "minecraft_zombie_variant-red_baby.png", "before", "a.b-c_d", "..."))
            assertThat(name, OutputFiles.inside(this.directory, name), equalTo(this.directory.resolve(name)));
    }

    @Test
    @DisplayName("a relative directory is answered relative, as the drivers print it")
    void aRelativeDirectoryStaysRelative() {
        Path relative = Path.of("cache/visual/block-render-3d");
        assertThat(OutputFiles.inside(relative, "minecraft_tnt.png"), equalTo(relative.resolve("minecraft_tnt.png")));
    }

    @Test
    @DisplayName("a name that is not one segment inside the directory is refused")
    void aNameThatIsNotOneSegmentIsRefused() {
        for (String name : List.of("", ".", "..", "a/b", "../a", "a/../b", "a\\b", "/a", this.directory.resolve("a").toString(), "a\0b"))
            assertThrows(IllegalArgumentException.class, () -> OutputFiles.inside(this.directory, name), name);
    }

}
