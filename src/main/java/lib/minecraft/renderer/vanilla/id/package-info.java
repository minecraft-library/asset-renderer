/**
 * Minecraft's own identifier grammars - the string forms a resource id, a blockstate key and a pack
 * identity are written in, and the parse and print each one owns.
 *
 * <p>{@link lib.minecraft.renderer.vanilla.id.ResourceId ResourceId} is the {@code namespace:name} pair
 * the decoded records carry, reading a bare name into the {@code minecraft} namespace and deriving one
 * from a model id's trailing segment.
 * {@link lib.minecraft.renderer.vanilla.id.BlockStateKey BlockStateKey} parses a canonical
 * {@code prop=val,..} key into a property map and joins one back, the two being inverses on every key
 * vanilla and the shipped tables produce.
 * {@link lib.minecraft.renderer.vanilla.id.PackId PackId} is the letter-led, hyphen-separated identity
 * a resolved pack is addressed by when an id's prefix names no live namespace, with {@code vanilla} and
 * {@code minecraft} reserved.
 *
 * <p>A member that is not a parse of a Minecraft string form does not belong here. What an id names -
 * the file it resolves to, the record that answers it - is read elsewhere; this package holds only how
 * it is spelled.
 *
 * <p><b>Parity.</b> These identifiers are carried by the records the dump serialises and the renderers
 * read, so the package declares the asset layer's claim: a change here can be visible on both sides,
 * and which artifacts one type reaches is answered per file off the reference graph.
 */
@Parity(claim = "asset-layer")
package lib.minecraft.renderer.vanilla.id;

import lib.minecraft.renderer.parity.Parity;
