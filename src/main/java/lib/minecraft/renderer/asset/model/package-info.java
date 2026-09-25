/**
 * The block and item model schema as parsed.
 *
 * <p>{@link lib.minecraft.renderer.asset.model.ModelData ModelData} is a whole model once its parent
 * chain has been walked and merged: the ambient-occlusion flag, the {@code textures} bindings, the
 * {@code elements} list and the {@code display} transforms, and nothing left to resolve at render
 * time. It answers the questions a merged model can answer about itself - whether it would render
 * nothing, where a {@code #variable} chain ends, which face references a caller's resolver should
 * load, and which are forced into the translucent pass - without choosing how a texture id becomes
 * pixels. {@link lib.minecraft.renderer.asset.Block Block} and
 * {@link lib.minecraft.renderer.asset.Item Item} both hold one.
 *
 * <p>The shapes it is built of are
 * {@link lib.minecraft.renderer.asset.model.ModelElement ModelElement}, one axis-aligned box with its
 * faces keyed by direction in authored order, its optional
 * {@link lib.minecraft.renderer.asset.model.ModelElement.ElementRotation ElementRotation}, its shade
 * flag and its light emission; {@link lib.minecraft.renderer.asset.model.ModelFace ModelFace}, one
 * face's texture reference, UV rectangle, cull direction, tint index and UV rotation; and
 * {@link lib.minecraft.renderer.asset.model.ModelTexture ModelTexture}, one value of the
 * {@code textures} map, a sprite reference and the {@code force_translucent} flag whichever of the two
 * authored forms it was written in.
 *
 * <p>The transform is {@link lib.minecraft.renderer.asset.model.ModelTransform ModelTransform}, one
 * {@code display} entry's rotation, translation and scale.
 *
 * <p>A type that is not a shape of a {@code models/*.json} file does not belong here. An entity's bone
 * tree is a different dialect and is {@link lib.minecraft.renderer.asset.mesh asset.mesh}.
 */
package lib.minecraft.renderer.asset.model;
