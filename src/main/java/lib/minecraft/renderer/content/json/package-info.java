/**
 * Every type the module's Gson instance is built from - the contributor that installs the renderer's
 * adapters, and the adapters it installs or a record names.
 *
 * <p>{@link lib.minecraft.renderer.content.json.RendererGsonContributor RendererGsonContributor} is the
 * {@link dev.simplified.gson.GsonContributor GsonContributor} implementation
 * {@link java.util.ServiceLoader ServiceLoader} discovers through
 * {@code META-INF/services/dev.simplified.gson.GsonContributor}, so every
 * {@code GsonSettings.defaults()} build carries these adapters, and
 * {@link lib.minecraft.renderer.content.table.ResourceDocument ResourceDocument} and the pack readers
 * deserialise asset JSON into typed records without naming one.
 *
 * <p>It registers seven globally, for a type read wherever it appears. The
 * {@link lib.minecraft.renderer.content.json.Vector2fAdapter Vector2fAdapter},
 * {@link lib.minecraft.renderer.content.json.Vector3fAdapter Vector3fAdapter} and
 * {@link lib.minecraft.renderer.content.json.Vector4fAdapter Vector4fAdapter} read a vector as its
 * array, and {@link lib.minecraft.renderer.content.json.ResourceIdAdapter ResourceIdAdapter} a scalar
 * id field's {@code namespace:name} string. The three deserializers read the discriminated unions, a
 * nested term resolving through the context and so through the same registration:
 * {@link lib.minecraft.renderer.content.json.MultipartWhenDeserializer MultipartWhenDeserializer} a
 * multipart {@code when} condition and its {@code AND} / {@code OR} terms,
 * {@link lib.minecraft.renderer.content.json.ItemModelNodeDeserializer ItemModelNodeDeserializer} an
 * {@code items/*.json} {@code model} object as a node tree and every child in it, and
 * {@link lib.minecraft.renderer.content.json.LayerTintDeserializer LayerTintDeserializer} one
 * {@code tints[]} entry of such a node.
 *
 * <p>Five more are applied through {@code @JsonAdapter}, on the record whose form they read or on the
 * field that carries it: {@link lib.minecraft.renderer.content.json.EulerRotationAdapter
 * EulerRotationAdapter} a {@code [pitch, yaw, roll]} array,
 * {@link lib.minecraft.renderer.content.json.CubeGrowAdapter CubeGrowAdapter} a cube's {@code grow} as
 * a broadcast scalar or an {@code [x, y, z]} array,
 * {@link lib.minecraft.renderer.content.json.TextureSizeAdapter TextureSizeAdapter} a {@code [w, h]}
 * texture size, {@link lib.minecraft.renderer.content.json.ModelTextureAdapter ModelTextureAdapter} a
 * model texture's string or {@code sprite} / {@code force_translucent} object form, and
 * {@link lib.minecraft.renderer.content.json.ModelIdAdapter ModelIdAdapter} a model-dialect id,
 * collapsing {@code namespace:block/name} to its namespace and trailing name for a field that opts into
 * that dialect.
 *
 * <p>A type Gson is never handed does not belong here - one the contributor does not register and no
 * {@code @JsonAdapter} names. A loader that calls {@code fromJson} on bytes it read is a reader, not a
 * part of the instance.
 *
 * <p><b>Parity.</b> Every member declares its own claims.
 */
package lib.minecraft.renderer.content.json;
