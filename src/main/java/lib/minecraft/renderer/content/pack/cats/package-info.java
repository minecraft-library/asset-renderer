/**
 * The Catharsis conventions a pack opts into, evaluated during acquisition - which
 * {@code fabric:overlays} an offline renderer activates and against what.
 *
 * <p>{@link lib.minecraft.renderer.content.pack.cats.CatharsisOverlays CatharsisOverlays} resolves the
 * active overlay directories from a pack's {@code pack.mcmeta}, each entry's
 * {@link lib.minecraft.renderer.content.pack.cats.CatharsisCondition CatharsisCondition} evaluated
 * against the option defaults {@link lib.minecraft.renderer.content.pack.cats.CatharsisConfig
 * CatharsisConfig} reads and the renderer's
 * {@link lib.minecraft.renderer.content.pack.cats.CatharsisTarget CatharsisTarget}. The
 * {@code pack.cats} container format itself is a storage kind, decoded in
 * {@link lib.minecraft.renderer.content.container}.
 *
 * <p><b>Parity.</b> The overlay-and-container half of pack resolution has no dump section, so an
 * identical dump is silent about a change here. What sees one is a render against a fixture that
 * carries an overlay.
 */
@Parity(claim = "catharsis-selection")
package lib.minecraft.renderer.content.pack.cats;

import lib.minecraft.renderer.parity.Parity;
