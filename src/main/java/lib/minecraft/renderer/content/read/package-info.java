/**
 * Reading a named path out of a byte source - the tables bundled in this JAR on one side, the files of
 * a resolved pack stack on the other - without deciding what the bytes mean.
 *
 * <p>{@link lib.minecraft.renderer.content.read.BundledResource BundledResource} is the one classpath
 * read site for the tables shipped under {@code lib/minecraft/renderer/}. Each call opens exactly one
 * resource under try-with-resources and hands it back as a
 * {@link lib.minecraft.renderer.content.table.ResourceDocument ResourceDocument} whose envelope is
 * already validated - {@code format == 2}, or one of the formats a caller names for a table shipped
 * under more than one grammar. The caller states per file whether the table is load-bearing by the
 * method it picks: {@code require} treats an absent resource as fatal, {@code read} answers empty.
 *
 * <p>{@link lib.minecraft.renderer.content.read.PackSubtree PackSubtree} is its twin on the pack side:
 * the one {@code (pack x root x namespace)} walk of an {@code assets} or {@code data} subtree across a
 * stack, and the one place a pack's {@code filter.block} section is honoured. It hands back every
 * surviving file in resolution order as a
 * {@link lib.minecraft.renderer.content.read.PackSubtree.Entry PackSubtree.Entry}, and each caller
 * keeps its own merge rule over them.
 *
 * <p>{@link lib.minecraft.renderer.content.read.BlockRendererOverrides BlockRendererOverrides} gathers
 * the three {@code renderer/*.json} files a pack may ship at its root into three {@code JsonTree}
 * accumulators keyed by top-level entry, the higher pack winning per key. It knows three paths and
 * checks an envelope, but what an entry is gets decided by the table readers that overlay these onto
 * their bundled snapshot.
 *
 * <p>A type that knows what the bytes mean does not belong here. Parsing a model, merging a
 * blockstate or keying a row by the subject it describes is the business of the package that reads
 * through these.
 */
package lib.minecraft.renderer.content.read;
