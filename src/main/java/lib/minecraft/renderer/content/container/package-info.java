/**
 * Where a pack's bytes come from - the storage kinds a pack is read out of, each answering the same
 * read contract.
 *
 * <p>{@link lib.minecraft.renderer.content.container.PackContainer PackContainer} is that contract and
 * its one entry point: it answers "what entries exist" and "give me these bytes" for an exploded
 * directory, a plain zip or a Catharsis archive, and
 * {@link lib.minecraft.renderer.content.container.PackContainer#detect PackContainer.detect} chooses
 * the kind for a source by its content. Each kind owns how it is built and how it reads.
 * {@link lib.minecraft.renderer.content.container.CatsIndex CatsIndex} decodes the Catharsis
 * {@code pack.cats} format - a depth-first directory index inline in the header, then a data region of
 * the files' bytes back to back - into a path-keyed store of
 * {@link lib.minecraft.renderer.content.container.CatsEntry CatsEntry} records, each reading and
 * decompressing its own slice on demand.
 *
 * <p>The package imports nothing from the library but its exceptions, which is what lets every reader
 * of a pack - the pack model, the subtree walk, the rule scanner, the loaders - hold a container by its
 * own type. The Catharsis <em>conventions</em> a pack opts into - its overlays and their conditions -
 * are evaluated during acquisition, in {@link lib.minecraft.renderer.content.pack.cats}.
 *
 * <p><b>Parity.</b> The claim is derived: the reference graph answers each file here for itself, so a
 * change to the contract plans every render that reads a pack, and a change to one kind plans what
 * reaches that kind.
 */
@Parity(claim = "asset-layer")
package lib.minecraft.renderer.content.container;

import lib.minecraft.renderer.parity.Parity;
