/**
 * The shared native-read layer for the bundled {@code *.json} asset resources.
 *
 * <p>One read discipline behind every native loader: {@link lib.minecraft.renderer.pipeline.load.BundledResource}
 * is the sole classpath-read site (try-with-resources, per-file
 * {@link lib.minecraft.renderer.pipeline.load.BundledResource.MissingPolicy}),
 * {@link lib.minecraft.renderer.pipeline.load.ResourceDocument} is the envelope-aware reader (asserts
 * {@code format == 2}, warns on a {@code source_version} mismatch, deserialises into typed DTOs), and
 * {@link lib.minecraft.renderer.pipeline.load.ArgbHex} is the one ARGB hex parser with the one
 * white-fallback policy.
 *
 * <p>The read side reuses the tooling {@code JsonNode} and {@code Diagnostics} core rather than
 * duplicating it.
 */
package lib.minecraft.renderer.pipeline.load;
