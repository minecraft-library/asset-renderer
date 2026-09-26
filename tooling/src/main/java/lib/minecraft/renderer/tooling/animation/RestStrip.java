package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import org.jetbrains.annotations.NotNull;

/**
 * What the model table stops stating once the fold has read it - the resting answer each subject
 * carried into the pose flow, and the idle period the style catalogs are stated against.
 */
@UtilityClass
public class RestStrip {

    /**
     * The ticks one whole idle excursion spans, stated once in the table's header for every
     * family's style catalog rather than restated per row.
     */
    public static final int PERIOD_TICKS = 24;

    /**
     * Removes the {@code rest} member from every model row. The fold has taken what each subject
     * rests at and folded that answer into the rows the pose table ships, so a written row states
     * only what a reader joins on.
     *
     * @param models the model table's {@code models} node
     */
    public static void apply(@NotNull JsonTree models) {
        models.members().forEach((entity, row) -> row.remove("rest"));
    }

}
