package lib.minecraft.renderer.asset.appearance;

import lib.minecraft.renderer.option.AppearanceOptions;
import org.jetbrains.annotations.NotNull;

/**
 * Age selection for an entity render. {@link #BABY} binds the entity's distinct baby mesh (and its
 * {@code <variant>_baby} texture) when the resolved entity has one; {@link #ADULT} (the default)
 * renders the adult mesh. The axis rests at {@code ADULT}, so that option answers
 * {@link #selectedIn} for an untouched appearance where an unset {@link Size} answers for none.
 */
public enum Age implements Axis {

    /** The adult mesh - the family top-level geometry. */
    ADULT,

    /** The distinct baby mesh, when the entity ships a dedicated {@code Baby<X>Model}. */
    BABY;

    /** {@inheritDoc} */
    @Override
    public boolean selectedIn(@NotNull AppearanceOptions appearance) {
        return appearance.getAge() == this;
    }

}
