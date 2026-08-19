/**
 * The entity appearance axes - the vanilla vocabulary an
 * {@link lib.minecraft.renderer.option.AppearanceOptions AppearanceOptions} selection is drawn from
 * and a shipped {@code entity_models.json} row is gated on.
 *
 * <p>An axis here is a dimension the model form declares, not a field a renderer branches on: the
 * typed {@link lib.minecraft.renderer.asset.appearance.Age Age} and
 * {@link lib.minecraft.renderer.asset.appearance.Size Size} meshes, the dye
 * {@link lib.minecraft.renderer.asset.appearance.TintAxis TintAxis} an overlay names its
 * {@code tint_by} target with, and the per-subject texture families
 * ({@link lib.minecraft.renderer.asset.appearance.HorseMarking HorseMarking},
 * {@link lib.minecraft.renderer.asset.appearance.IronGolemCrackiness IronGolemCrackiness},
 * {@link lib.minecraft.renderer.asset.appearance.CopperWeathering CopperWeathering},
 * {@link lib.minecraft.renderer.asset.appearance.TropicalFishPattern TropicalFishPattern},
 * {@link lib.minecraft.renderer.asset.appearance.Villager Villager}).
 * {@link lib.minecraft.renderer.asset.appearance.AppearanceGate AppearanceGate} is the parsed
 * {@code when} object that tests a selection, so it sits with what it reads rather than with the
 * bag the caller fills.
 *
 * <p><b>Parity.</b> Each of these is named by an options bag and read by
 * {@link lib.minecraft.renderer.asset.Entity#resolve Entity.resolve}, so a value here reaches both
 * what the pipeline builds and what a renderer draws - the union of the two claims declared over
 * it, and neither one alone.
 */
@Parity(claim = "option-surface")
package lib.minecraft.renderer.asset.appearance;

import lib.minecraft.renderer.parity.Parity;
