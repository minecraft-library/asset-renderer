/**
 * Facts about Minecraft that are true before any run starts - the tables, rosters and curves the
 * client fixes in its own code and data, which no pack declares and no caller supplies.
 *
 * <p>The colour tables are the largest group.
 * {@link lib.minecraft.renderer.vanilla.Biome Biome} holds each biome's temperature, downfall, colour
 * overrides and grass modifier, with the custom biome a caller builds beside them,
 * {@link lib.minecraft.renderer.vanilla.TintSource TintSource} names the colormap a tinted face samples
 * and the colour it falls back to, {@link lib.minecraft.renderer.vanilla.RedstoneTint RedstoneTint} is
 * the wire's tint per power level, {@link lib.minecraft.renderer.vanilla.DyeColor DyeColor} the sixteen
 * dyes and the wool colour each draws in, and
 * {@link lib.minecraft.renderer.vanilla.PortalPalette PortalPalette} the end-portal star-field's
 * samplers, layer colours, layer counts and slab.
 *
 * <p>The rosters name what a subject may be.
 * {@link lib.minecraft.renderer.vanilla.BannerPattern BannerPattern} is one entry of the banner pattern
 * registry and the banner and shield masks it resolves to, and
 * {@link lib.minecraft.renderer.vanilla.SpecialModels SpecialModels} the hardcoded-render kinds a
 * {@code minecraft:special} item node may name.
 * {@link lib.minecraft.renderer.vanilla.ItemModelProperties ItemModelProperties} holds the properties
 * an item definition's {@code condition}, {@code select} and {@code range_dispatch} nodes may dispatch
 * on, and {@link lib.minecraft.renderer.vanilla.DataComponents DataComponents} the data components a
 * stack may hold and the predicate types a component condition tests them by.
 * {@link lib.minecraft.renderer.vanilla.DecodedComponent DecodedComponent} is the codec of the four of
 * them a component select keys on, which reduces a value of each to one canonical key.
 * {@link lib.minecraft.renderer.vanilla.SunAngle SunAngle} is the eased curve a day-time tick puts the
 * sun at, which the {@code minecraft:time} dispatch property reads.
 *
 * <p>The addressing constants close the set:
 * {@link lib.minecraft.renderer.vanilla.VanillaPaths VanillaPaths} holds the roots, namespace and
 * subtree names every client and pack walk descends through, and
 * {@link lib.minecraft.renderer.vanilla.FluidTextures FluidTextures} the still and flow texture ids of
 * water and lava.
 *
 * <p>Each member traces to a named vanilla class, field or JSON file - a registry, a shader, a
 * renderer's constant. A member that cannot be traced to one does not belong here, however vanilla it
 * looks.
 *
 * <p><b>Parity.</b> Every member declares its own claims.
 */
package lib.minecraft.renderer.vanilla;
