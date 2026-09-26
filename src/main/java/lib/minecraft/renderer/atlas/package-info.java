/**
 * Everything {@link lib.minecraft.renderer.AtlasRenderer AtlasRenderer} alone reads or emits - the
 * tables that decide which tiles it lays down and in what order, and the shape of what it hands back.
 *
 * <p>What it reads. {@link lib.minecraft.renderer.atlas.AtlasOrder AtlasOrder} is the grouping order the
 * known block and item ids are sorted into, which clusters related subjects into neighbouring tiles.
 * {@link lib.minecraft.renderer.atlas.AtlasDispatch AtlasDispatch} names the block ids whose vanilla
 * model draws a blank tile, and which the block pass hands to the fluid or the portal renderer instead.
 *
 * <p>What it emits. {@link lib.minecraft.renderer.atlas.AtlasResult AtlasResult} is the whole output -
 * the composed grid image and the sidecar placing every tile in it.
 * {@link lib.minecraft.renderer.atlas.AtlasSidecar AtlasSidecar} is the typed {@code atlas.json} schema,
 * parsed and written by one type so neither side spells it out twice, and
 * {@link lib.minecraft.renderer.atlas.AtlasTile AtlasTile} is one row of it: the subject a tile was
 * rendered from, its kind and source path, and where it sits in the grid. A row carries coordinates and
 * never pixels.
 *
 * <p>A type that any production class other than {@code AtlasRenderer} imports does not belong here.
 *
 * <p><b>Parity.</b> Every member declares its own claims; the package declares none.
 */
package lib.minecraft.renderer.atlas;
