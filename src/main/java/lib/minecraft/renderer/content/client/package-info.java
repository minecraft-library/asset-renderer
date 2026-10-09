/**
 * Client-jar acquisition: the download, the vanilla pack read out of the jar - into memory by default,
 * or extracted to disk when the options ask - and the paths every other read in the repository starts
 * from, and the Mojang texture fetch a player render reads a URL-sourced skin through.
 *
 * <p>The one place here that reaches the network. An acquisition failure raises
 * {@link lib.minecraft.renderer.exception.ClientException ClientException}, or the Mojang API's own
 * {@link api.simplified.mojang.exception.MojangApiException MojangApiException} for a request that API
 * fails, off {@code RuntimeException} rather than the renderer's own root, so a batch renderer's
 * skip-and-continue cannot swallow a client that failed to acquire. A texture fetch is one subject's
 * read instead, and raises {@link lib.minecraft.renderer.exception.ContentException ContentException},
 * which that catch does skip.
 *
 * <p><b>Parity.</b> A change to what is read out of the jar, or where the jar lands, reaches both sides
 * at once: the pack stack the pipeline dump serialises, and the client classes the generator flows
 * walk. No rule covers that pairing but this one - the pack readers and the walkers each have their own
 * over what this module hands them, and neither speaks for the acquisition itself. The renders are
 * not on it: they read the pack stack, so a change that moved one would move the dump first.
 *
 * @see lib.minecraft.renderer.content.client.ClientAcquisition
 * @see lib.minecraft.renderer.content.client.ClientAssets
 */
@Parity(claim = "client-acquisition")
package lib.minecraft.renderer.content.client;

import lib.minecraft.renderer.parity.Parity;
