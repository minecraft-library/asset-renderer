package lib.minecraft.renderer.call.result;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Stream;

/**
 * The result of a render that places no other render: the image and the stand-ins drawn in it, built by
 * {@link RenderResult#of} alone.
 *
 * @param image the drawn image, static for one frame and animated for several
 * @param substitutions the stand-ins drawn in the image, distinct and in their natural order
 */
record PlainResult(@NotNull ImageData image, @NotNull ConcurrentList<Substitution> substitutions) implements RenderResult {

    /**
     * Collects stand-ins in the form every result answers them: each once, in their natural order,
     * unmodifiable.
     *
     * @param substitutions the stand-ins, in any order and with repeats
     * @return the distinct stand-ins, sorted and unmodifiable
     */
    static @NotNull ConcurrentList<Substitution> distinctSorted(@NotNull Stream<Substitution> substitutions) {
        return substitutions.distinct()
            .sorted()
            .collect(Concurrent.toUnmodifiableList());
    }

}
