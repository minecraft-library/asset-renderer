/**
 * Pipeline-internal infrastructure - the shared native-read layer for the bundled {@code *.json}
 * asset resources plus the Gson service wiring the whole pipeline reads through.
 *
 * <p>One read discipline behind every native loader: {@link lib.minecraft.renderer.pipeline.util.BundledResource}
 * is the sole classpath-read site (try-with-resources, and a per-file choice between
 * {@link lib.minecraft.renderer.pipeline.util.BundledResource#require(java.lang.String) require}
 * and {@link lib.minecraft.renderer.pipeline.util.BundledResource#read(java.lang.String) read}),
 * {@link lib.minecraft.renderer.pipeline.util.ResourceDocument} is the envelope-aware reader (asserts
 * {@code format == 2}, deserialises into typed DTOs), and
 * {@link lib.minecraft.renderer.pipeline.util.ArgbHex} is the one ARGB hex parser with the one
 * white-fallback policy.
 *
 * <p>The read side reuses the {@code JsonTree} core rather than duplicating it.
 *
 * <p><b>Gson.</b> {@link lib.minecraft.renderer.pipeline.util.PipelineGsonContributor
 * PipelineGsonContributor} is the {@code dev.simplified.gson.GsonContributor} {@link java.util.ServiceLoader
 * ServiceLoader} implementation that registers the renderer's type adapters on every
 * {@code GsonSettings.defaults()} build, so {@link lib.minecraft.renderer.pipeline.util.ResourceDocument}
 * and the pack readers deserialise asset JSON into typed DTOs automatically.
 *
 * <p>Nothing outside the pipeline depends on this package - it is pipeline wiring, not domain code.
 */
package lib.minecraft.renderer.pipeline.util;
