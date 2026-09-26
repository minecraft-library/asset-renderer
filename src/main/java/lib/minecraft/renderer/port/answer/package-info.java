/**
 * The values a lookup returns that no parser builds - what a resolution hands back, rather than a
 * record decoded from a file.
 *
 * <p>Pack rules answer through three of them.
 * {@link lib.minecraft.renderer.port.answer.CitResult CitResult} is the effect of the
 * highest-precedence matching CIT rule on one render - the {@code layer0} replacement, the named
 * sub-texture replacements, a model override and the glint decision - with {@code NONE} as the
 * no-match answer. {@link lib.minecraft.renderer.port.answer.GlintPolicy GlintPolicy} is that glint
 * decision: vanilla's own, suppressed, or replaced by a custom texture; the rules decide it and the
 * compose stage applies it. {@link lib.minecraft.renderer.port.answer.CtmContext CtmContext} is the
 * query side of the connected-texture lookup, the per-face facts a block render hands the matcher.
 *
 * <p>{@link lib.minecraft.renderer.port.answer.ResolvedTexture ResolvedTexture} is the outcome of a
 * texture resolution: the winning pack, the resolved id, the container and container-relative entry
 * path the PNG is read from, and the texture's whole sidecar.
 *
 * <p>A type a parser builds does not belong here.
 *
 * <p><b>Parity.</b> The package declares the asset-layer claim, which answers for these as data the
 * renderers read and holds no artifact blind to a change here. The three rule answers also carry the
 * pack-rule-layer claim on their own types.
 */
@Parity(claim = "asset-layer")
package lib.minecraft.renderer.port.answer;

import lib.minecraft.renderer.parity.Parity;
