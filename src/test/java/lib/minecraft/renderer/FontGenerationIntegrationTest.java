package lib.minecraft.renderer;

import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.text.tooling.ToolingFonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The font generator's own test, and the one place either suite runs it.
 *
 * <p>{@link ToolingFonts#main} clones {@code font-generator} into {@code cache/font-generator}, builds
 * it in a virtualenv off the host's Python and writes every OTF to {@code cache/fonts}, which is where
 * {@link MinecraftFontsExtension} reads them for each fast-suite class that renders text. It needs
 * {@code git}, Python 3.10+ and the network, which is what the slow tag is for. A cache that already
 * holds every font is left as it is.
 */
@Tag("slow")
@DisplayName("The font generator writes every font the fast suite reads")
class FontGenerationIntegrationTest {

    @Test
    @DisplayName("cache/fonts holds every Minecraft font, generated where it did not")
    void theGeneratorWritesEveryFont() throws Exception {
        Path fonts = MinecraftFontsExtension.CACHE_FONTS_DIR;
        if (!MinecraftFontsExtension.FONT_FILES.stream().allMatch(file -> Files.isRegularFile(fonts.resolve(file))))
            ToolingFonts.main(new String[]{MinecraftFontsExtension.VERSION});

        for (String file : MinecraftFontsExtension.FONT_FILES)
            assertThat("'" + file + "' under '" + fonts + "'", Files.isRegularFile(fonts.resolve(file)), is(true));
        assertThat("the fast suite's font extension reads what this wrote", MinecraftFontsExtension.isPresent(), is(true));
    }

}
