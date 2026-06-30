/**
 * Caller-composed render-input value types - the typed vocabulary a caller binds into a render
 * {@link lib.minecraft.renderer.options options} object to drive a render. These are not
 * parsed-from-pack assets (those live in {@link lib.minecraft.renderer.asset asset}); they are fixed
 * parameter shapes and small composition records the caller supplies per render, consumed downstream
 * by the {@link lib.minecraft.renderer.engine.kit kit} layer. The package stays free of engine state
 * so it sits below {@code engine} in the dependency order.
 * <ul>
 *   <li>{@link lib.minecraft.renderer.request.EulerRotation EulerRotation} - immutable
 *       pitch / yaw / roll triple, the rotation shape every renderer accepts for caller-side
 *       rotation. Internal use converts to a
 *       {@link lib.minecraft.renderer.tensor.Quaternionf Quaternionf} or chained rotation
 *       matrices.</li>
 *   <li>{@link lib.minecraft.renderer.request.ArmorMaterial ArmorMaterial},
 *       {@link lib.minecraft.renderer.request.ArmorTrim ArmorTrim},
 *       {@link lib.minecraft.renderer.request.ArmorPiece ArmorPiece} - the equipped-armor vocabulary:
 *       material atlas, trim slot / colour / pattern, and the composed per-slot piece.</li>
 *   <li>{@link lib.minecraft.renderer.request.BannerLayer BannerLayer} - one banner / shield layer
 *       pairing a {@link lib.minecraft.renderer.asset.BannerPattern BannerPattern} with a
 *       {@link lib.minecraft.renderer.asset.DyeColor DyeColor}.</li>
 * </ul>
 */
package lib.minecraft.renderer.request;
