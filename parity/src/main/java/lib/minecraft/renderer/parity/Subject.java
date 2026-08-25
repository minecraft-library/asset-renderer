package lib.minecraft.renderer.parity;

/**
 * The renderer a claim is about, one constant per renderer this library ships, plus the two answers
 * that are not a renderer.
 * <p>
 * Shared code names none of these and the empty list is that statement rather than an omission: most
 * of the engine, the face vocabulary and the tensor package serve every renderer there is. A claim
 * naming exactly one constant asserts that the files it reaches belong to that renderer and to no
 * other, which is a closure a guard can hold it to.
 * <p>
 * On a TYPE the list answers a related question: what this type reaches, written where the reference
 * graph cannot derive it. A type reached only through a wiring seam, or constructed by a service
 * loader out of a file no constant pool mentions, is reachable from no producer root and answers
 * nothing at all - and so does a renderer this store holds no artifact for. Those two look identical
 * from outside and only one of them is correct, so a library type that reaches nothing says which
 * and {@code reach check} refuses one that says neither.
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
    TEXT,

    /**
     * Every pipeline there is, and the one constant that is not a renderer.
     * <p>
     * The roster guard reads it out before comparing, because there is no {@code EngineRenderer}. It
     * is what a type says when a human knows it is under every render and the reference graph cannot
     * see the edge - a Gson contributor a service file registers, whose adapters decide how every
     * pipeline value parses, being the one in this tree.
     * <p>
     * Spelled rather than left to the empty list, which already means <b>undeclared</b>: silence and
     * intent reading as one token is the failure this codebase has been bitten by repeatedly.
     */
    ENGINE

}
