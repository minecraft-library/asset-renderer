/**
 * Facts about Minecraft that are true before any run starts - the tables, rosters and curves the
 * client fixes in its own code and data, which no pack declares and no caller supplies.
 *
 * <p>The colour tables are the largest group.
 * {@link lib.minecraft.renderer.vanilla.BiomeClimate BiomeClimate} holds each biome's temperature,
 * downfall and colour overrides and the grass modifier it applies,
 * {@link lib.minecraft.renderer.vanilla.TintSource TintSource} names the colormap a tinted face samples
 * and the colour it falls back to, {@link lib.minecraft.renderer.vanilla.RedstoneTint RedstoneTint} is
 * the wire's tint per power level, {@link lib.minecraft.renderer.vanilla.DyeColor DyeColor} the sixteen
 * dyes and the wool colour each draws in, and
 * {@link lib.minecraft.renderer.vanilla.PortalPalette PortalPalette} the end-portal star-field's
 * samplers, layer colours, layer counts and slab.
 *
 * <p>The rosters name what a subject may be.
 * {@link lib.minecraft.renderer.vanilla.BannerPattern BannerPattern} is one entry of the banner pattern
 * registry and the banner and shield masks it resolves to,
 * {@link lib.minecraft.renderer.vanilla.SpecialModels SpecialModels} the hardcoded-render kinds a
 * {@code minecraft:special} item node may name, and
 * {@link lib.minecraft.renderer.vanilla.UniversalStyles UniversalStyles} the bind, standing and walking
 * style rows every entity answers whether or not it ships one.
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
