package lib.minecraft.renderer.asset.model;

import org.jetbrains.annotations.NotNull;

/**
 * One of vanilla's worn-armor meshes, in the two forms its two armor layers wear it in.
 *
 * <p>Worn armor is not derived from the wearer's own model. Vanilla builds a handful of armor sets
 * and hands each renderer the one its subject wears, so a skeleton's narrow limbs and a giant's
 * scaled-up body dress in the very same boxes. Each set fans one base mesh out over the four
 * equipment slots at two per-side growths, which is exactly the pair carried here - every cube
 * arrives already grown, so a consumer reads its corners without further arithmetic.
 *
 * @param inner the mesh grown by the leggings layer's amount
 * @param outer the mesh grown by the helmet / chestplate / boots layer's amount
 */
public record ArmorMeshData(@NotNull EntityModelData inner, @NotNull EntityModelData outer) {}
