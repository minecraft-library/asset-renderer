/**
 * Client-jar acquisition: the download, the extraction and the paths every other read in the
 * repository starts from.
 *
 * <p>The one place here that reaches the network, and a leaf both the renderer and the generators
 * include and neither is included by. Its failures raise
 * {@link lib.minecraft.renderer.client.exception.ClientException ClientException} off
 * {@code RuntimeException} rather than the renderer's own root, so a batch renderer's
 * skip-and-continue cannot swallow a client that failed to acquire.
 *
 * <p><b>Parity.</b> A change to what is extracted or where it lands reaches both sides at once: the
 * pack stack the pipeline dump serialises, and the client classes the generator flows walk. No rule
 * covers that pairing but this one - the pack readers and the walkers each have their own over the
 * tree this module writes, and neither speaks for the acquisition itself. The renders are not on it:
 * they read the pack stack, so a change that moved one would move the dump first.
 *
 * @see lib.minecraft.renderer.client.ClientAcquisition
 * @see lib.minecraft.renderer.client.ClientAssets
 */
@Parity(claim = "client-acquisition")
package lib.minecraft.renderer.client;

import lib.minecraft.renderer.parity.Parity;
