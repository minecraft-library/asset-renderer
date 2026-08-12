package lib.minecraft.renderer.client;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * The output of {@link ClientAcquisition#acquire} - the client options plus the extracted vanilla
 * pack root - handed to {@code PackAcquisition.acquire} to compile the resolved pack stack. The
 * client jar path stays inside {@code ClientAcquisition.acquire} and is deliberately absent here.
 *
 * @param options the client options the acquisition ran for
 * @param vanillaRoot the extracted vanilla pack root ({@code <cacheRoot>/vanilla/<version>})
 */
public record ClientAssets(@NotNull ClientOptions options, @NotNull Path vanillaRoot) {}
