/**
 * The shared native-read layer for the {@code v2/*.json} asset resources.
 *
 * <p>One read discipline behind every native loader: {@link lib.minecraft.renderer.pipeline.load.V2Resources}
 * is the sole classpath-read site (try-with-resources, per-file
 * {@link lib.minecraft.renderer.pipeline.load.V2Resources.MissingPolicy}),
 * {@link lib.minecraft.renderer.pipeline.load.V2Document} is the envelope-aware reader (asserts
 * {@code format == 2}, warns on a {@code source_version} mismatch, deserialises into typed DTOs), and
 * {@link lib.minecraft.renderer.pipeline.load.ArgbHex} is the one ARGB hex parser with the one
 * white-fallback policy.
 *
 * <p>The read side reuses the tooling {@code JsonNode} and {@code Diagnostics} core rather than
 * duplicating it; the shared JSON core relocates to a neutral package in a later pass.
 */
package lib.minecraft.renderer.pipeline.load;
