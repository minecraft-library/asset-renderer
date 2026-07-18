/**
 * The Catharsis {@code pack.cats} container decoder - the recursive header index
 * ({@link lib.minecraft.renderer.asset.pack.cats.CatsIndex CatsIndex}) and its per-file record
 * ({@link lib.minecraft.renderer.asset.pack.cats.CatsEntry CatsEntry}) backing the
 * {@link lib.minecraft.renderer.asset.pack.PackContainer.Cats Cats} container kind.
 *
 * <p>{@code CatsIndex} decodes the {@code "CATS"}-magic blob - a depth-first directory index inline in
 * the header, then a data region of the files' bytes back-to-back - into a path-keyed store of
 * {@code CatsEntry} records, each reading and decompressing its own slice on demand. The container is
 * read-only; the decoder never writes {@code .cats}.
 */
package lib.minecraft.renderer.asset.pack.cats;
