package lib.minecraft.renderer.asset.appearance;

/**
 * Age selection for an entity render. {@link #BABY} binds the entity's distinct baby mesh (and its
 * {@code <variant>_baby} texture) when the resolved entity has one; {@link #ADULT} (the default)
 * renders the adult mesh.
 */
public enum Age {

    /** The adult mesh - the family top-level geometry. */
    ADULT,

    /** The distinct baby mesh, when the entity ships a dedicated {@code Baby<X>Model}. */
    BABY

}
