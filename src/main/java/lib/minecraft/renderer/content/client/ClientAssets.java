package lib.minecraft.renderer.content.client;

import lib.minecraft.renderer.content.container.PackContainer;
import lib.minecraft.renderer.exception.ContentException;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The output of {@link ClientAcquisition#acquire} - the client options plus the vanilla pack - handed to
 * {@code PackAcquisition.acquire} to compile the resolved pack stack.
 *
 * <p>The vanilla pack is held as the container it is read through, so every load over one
 * {@code ClientAssets} reads the same snapshot of the client jar rather than reading the jar again.
 *
 * @param options the client options the acquisition ran for
 * @param vanilla the vanilla pack - the client jar's asset tree held in memory, or the tree it was
 *     extracted to when the options asked for an extraction
 */
public record ClientAssets(@NotNull ClientOptions options, @NotNull PackContainer vanilla) {

    /**
     * Pairs the options with a vanilla pack extracted to a directory.
     *
     * @param options the client options the acquisition ran for
     * @param vanillaRoot the extracted vanilla pack root
     * @throws ContentException if the root does not exist or is not a directory
     */
    public ClientAssets(@NotNull ClientOptions options, @NotNull Path vanillaRoot) {
        this(options, directory(vanillaRoot));
    }

    /** Opens an extracted vanilla pack root, refusing one that is not a directory. */
    private static @NotNull PackContainer directory(@NotNull Path vanillaRoot) {
        if (!Files.isDirectory(vanillaRoot))
            throw new ContentException("Vanilla pack root '%s' does not exist or is not a directory", vanillaRoot);

        return new PackContainer.Directory(vanillaRoot);
    }

}
