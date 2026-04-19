package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.binding.BannerPattern;
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
 * Verifies {@link BannerPatternLoader} against a {@link TempDir}-staged banner pattern
 * directory. Covers the vanilla JSON shape, the id-from-path convention, and the empty
 * directory fallback.
 */
class BannerPatternLoaderTest {

    @Test
    @DisplayName("loads a pattern file with asset_id and translation_key")
    void loadsPattern(@TempDir Path packRoot) throws IOException {
        Path patternDir = packRoot.resolve("data/minecraft/banner_pattern");
        Files.createDirectories(patternDir);
        Files.writeString(patternDir.resolve("creeper.json"),
            "{\"asset_id\":\"minecraft:creeper\",\"translation_key\":\"block.minecraft.banner.creeper\"}");

        ConcurrentMap<String, BannerPattern> patterns = BannerPatternLoader.load(packRoot);
        assertThat(patterns.size(), is(1));
        BannerPattern creeper = patterns.get("minecraft:creeper");
        assertThat(creeper.id(), is("minecraft:creeper"));
        assertThat(creeper.assetId(), is("minecraft:creeper"));
        assertThat(creeper.translationKey(), is("block.minecraft.banner.creeper"));
    }

    @Test
    @DisplayName("derives pattern id from relative path")
    void derivesIdFromPath(@TempDir Path packRoot) throws IOException {
        Path patternDir = packRoot.resolve("data/minecraft/banner_pattern");
        Files.createDirectories(patternDir);
        Files.writeString(patternDir.resolve("base.json"),
            "{\"asset_id\":\"minecraft:base\",\"translation_key\":\"block.minecraft.banner.base\"}");
        Files.writeString(patternDir.resolve("flow.json"),
            "{\"asset_id\":\"minecraft:flow\",\"translation_key\":\"block.minecraft.banner.flow\"}");

        ConcurrentMap<String, BannerPattern> patterns = BannerPatternLoader.load(packRoot);
        assertThat(patterns.get("minecraft:base").id(), is("minecraft:base"));
        assertThat(patterns.get("minecraft:flow").id(), is("minecraft:flow"));
    }

    @Test
    @DisplayName("derives banner and shield texture paths from assetId")
    void derivesTextureIds(@TempDir Path packRoot) throws IOException {
        Path patternDir = packRoot.resolve("data/minecraft/banner_pattern");
        Files.createDirectories(patternDir);
        Files.writeString(patternDir.resolve("creeper.json"),
            "{\"asset_id\":\"minecraft:creeper\",\"translation_key\":\"block.minecraft.banner.creeper\"}");

        BannerPattern creeper = BannerPatternLoader.load(packRoot).get("minecraft:creeper");
        assertThat(creeper.bannerTexture(), is(equalTo("minecraft:entity/banner/creeper")));
        assertThat(creeper.shieldTexture(), is(equalTo("minecraft:entity/shield/creeper")));
    }

    @Test
    @DisplayName("returns empty map when the pattern directory is missing")
    void emptyWhenMissing(@TempDir Path packRoot) {
        ConcurrentMap<String, BannerPattern> patterns = BannerPatternLoader.load(packRoot);
        assertThat(patterns.size(), is(0));
    }

    @Test
    @DisplayName("skips files missing asset_id")
    void skipsMalformedFiles(@TempDir Path packRoot) throws IOException {
        Path patternDir = packRoot.resolve("data/minecraft/banner_pattern");
        Files.createDirectories(patternDir);
        Files.writeString(patternDir.resolve("bad.json"), "{\"translation_key\":\"foo\"}");
        Files.writeString(patternDir.resolve("good.json"),
            "{\"asset_id\":\"minecraft:good\",\"translation_key\":\"bar\"}");

        ConcurrentMap<String, BannerPattern> patterns = BannerPatternLoader.load(packRoot);
        assertThat(patterns.size(), is(1));
        assertThat(patterns.containsKey("minecraft:good"), is(true));
    }

}
