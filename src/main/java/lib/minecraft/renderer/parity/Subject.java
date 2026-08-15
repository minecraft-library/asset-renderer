package lib.minecraft.renderer.parity;

/**
 * The renderer a claim is about, one constant per renderer this library ships.
 * <p>
 * Shared code names none of these and the empty list is that statement rather than an omission: most
 * of the engine, the face vocabulary and the tensor package serve every renderer there is. A claim
 * naming exactly one constant asserts that the files it reaches belong to that renderer and to no
 * other, which is a closure a guard can hold it to.
 */
public enum Subject {

    /** {@code AtlasRenderer}, and the option surface only it reads. */
    ATLAS,
    /** {@code BlockRenderer}, and the blockstate and model reads behind it. */
    BLOCK,
    /** {@code EntityRenderer}, and the entity index, mesh and appearance surface. */
    ENTITY,
    /** {@code FluidRenderer}. */
    FLUID,
    /** {@code GridRenderer}. */
    GRID,
    /** {@code ItemRenderer}, and the item model tree. */
    ITEM,
    /** {@code LayoutRenderer}. */
    LAYOUT,
    /** {@code MenuRenderer}, and the screen arithmetic and chrome only a menu draws. */
    MENU,
    /** {@code PlayerRenderer}, and the skin scopes and worn layers only a player draws. */
    PLAYER,
    /** {@code PortalRenderer}. */
    PORTAL,
    /** {@code TextRenderer}. */
    TEXT
}
