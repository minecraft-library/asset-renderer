package lib.minecraft.renderer.content.index;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * A built block or item index: the rows a lookup answers present for, and beside them the registered
 * ids it answers empty for - a block or item the game registers whose model declares nothing to draw.
 * <p>
 * The two never share an id. An id holding a row draws something, and an id in neither is one the
 * index does not know - an unregistered template model, a model that does not load, or a subject
 * another renderer draws.
 *
 * <p><b>Parity.</b> Reached only across the pipeline context, which is wiring, so no producer root
 * reaches it. It carries what the block and item index builders produce, so it is under everything
 * those indexes are.
 *
 * @param rows the rows keyed by namespaced id, unmodifiable
 * @param drawsNothing the registered ids that draw nothing, none of them a key of {@code rows},
 *     unmodifiable
 * @param <T> the row type
 */
@Parity(subject = {Subject.BLOCK, Subject.ENTITY, Subject.ITEM, Subject.MENU})
public record IndexRows<T>(@NotNull ConcurrentMap<String, T> rows, @NotNull Set<String> drawsNothing) {}
