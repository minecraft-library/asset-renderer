package lib.minecraft.renderer.vanilla.appearance;

import lib.minecraft.renderer.call.request.AppearanceOptions;

/**
 * Age selection for an entity render. {@link #BABY} binds the entity's distinct baby mesh (and its
 * {@code <variant>_baby} texture) when the resolved entity has one; {@link #ADULT} (the default)
 * renders the adult mesh. The axis rests at {@code ADULT}, so an untouched appearance
 * {@link AppearanceOptions#selects selects} that option where an unset {@link Size} selects none.
 */
public enum Age implements Axis {

    /** The adult mesh - the family top-level geometry. */
    ADULT,

    /** The distinct baby mesh, when the entity ships a dedicated {@code Baby<X>Model}. */
    BABY

}
