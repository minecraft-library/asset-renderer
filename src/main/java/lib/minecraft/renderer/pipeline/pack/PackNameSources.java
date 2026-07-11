package lib.minecraft.renderer.pipeline.pack;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The raw naming inputs available for one pack, gathered at acquisition time and fed to the
 * {@link PackIdDeriver} source ladder.
 *
 * <p>Only the source path is mandatory - the filename stem is the reliable primary id input across
 * the surveyed packs. The license title line and mcmeta description are optional fallbacks; both are
 * pre-flattened to plain strings by the caller (the description with its {@code §} codes preserved,
 * stripped later by {@link PackId#normalize}).
 *
 * @param source the pack source as supplied - a directory or a {@code .zip} / {@code .cats} /
 *     {@code .cats.zip} archive; its filename drives ladder rung 1
 * @param licenseTitleLine the first non-blank line of a root {@code LICENSE}, if one exists (rung 2)
 * @param description the flattened {@code pack.mcmeta} description, if any (rung 3)
 */
public record PackNameSources(
    @NotNull Path source,
    @NotNull Optional<String> licenseTitleLine,
    @NotNull Optional<String> description
) {

    /**
     * Convenience factory for a source whose only naming input is its path.
     *
     * @param source the pack source path
     * @return name sources carrying just the filename rung
     */
    public static @NotNull PackNameSources of(@NotNull Path source) {
        return new PackNameSources(source, Optional.empty(), Optional.empty());
    }

    /**
     * The pack source's filename, as it appears on disk (extension chain intact).
     *
     * @return the source filename
     */
    public @NotNull String fileName() {
        return this.source.getFileName().toString();
    }

}
