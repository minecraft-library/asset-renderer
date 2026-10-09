package lib.minecraft.renderer.call.result;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.StaticImageData;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * What one render hands back: the image it drew and every stand-in drawn in it.
 * <p>
 * A result is not an image. The image is {@link #image()}, a {@link StaticImageData} for one frame or an
 * {@link AnimatedImageData} for several, and whatever reads animation off an image's own type reads it
 * off that. The stand-ins are distinct, in their natural order and unmodifiable, so a render answers the
 * same list whichever frame worker reported first.
 * <p>
 * A render that places no other render answers this type exactly. The four that do answer a narrower
 * one - {@link AtlasResult}, {@link GridResult}, {@link LayoutResult} and {@link MenuResult} - each
 * adding where its parts were drawn, and each answering the union of its parts' stand-ins, so code
 * holding a result treats every render alike. Pixels drawn elsewhere are wrapped with
 * {@link #of(ImageData)}.
 */
public sealed interface RenderResult permits PlainResult, AtlasResult, GridResult, LayoutResult, MenuResult {

    /**
     * The drawn image, static for one frame and animated for several.
     */
    @NotNull ImageData image();

    /**
     * The stand-ins drawn in the image, distinct and in their natural order.
     */
    @NotNull ConcurrentList<Substitution> substitutions();

    /**
     * Answers whether anything in the image is a stand-in.
     *
     * @return whether the render drew any stand-in
     */
    default boolean substituted() {
        return !this.substitutions().isEmpty();
    }

    /**
     * Wraps an image drawn with no stand-in, such as one a caller rendered elsewhere.
     *
     * @param image the drawn image
     * @return a result carrying the image and no stand-in
     */
    static @NotNull RenderResult of(@NotNull ImageData image) {
        return new PlainResult(image, Concurrent.newUnmodifiableList());
    }

    /**
     * Pairs an image with the stand-ins drawn in it, deduplicated and sorted.
     *
     * @param image the drawn image
     * @param substitutions the stand-ins drawn in it, in any order and with repeats
     * @return a result carrying the image and each stand-in once, in their natural order
     */
    static @NotNull RenderResult of(@NotNull ImageData image, @NotNull Collection<Substitution> substitutions) {
        return new PlainResult(image, PlainResult.distinctSorted(substitutions.stream()));
    }

}
