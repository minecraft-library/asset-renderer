package lib.minecraft.refharness;

import java.util.ArrayList;
import java.util.List;

import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.sweep.ArmorSweep;
import lib.minecraft.refharness.sweep.BlockSweep;
import lib.minecraft.refharness.sweep.EntitySweep;
import lib.minecraft.refharness.sweep.GlintSweep;
import lib.minecraft.refharness.sweep.ItemSweep;
import lib.minecraft.refharness.sweep.PitchRollSweep;
import lib.minecraft.refharness.sweep.PlayerSweep;

/** Which sweeps a run performs. Selected once, from the harness properties. */
public enum HarnessMode {

    /** The reference sweep proper - blocks, items, entities and the player. */
    FULL,
    /** Only the animated-glint frame sequences. */
    GLINT,
    /** Only the player references. */
    PLAYERS,
    /** Only the armored-mob diagnostics. */
    ARMOR,
    /** Only the diagnostic pitch x roll pose sweep, which writes outside the reference tree. */
    PITCH_ROLL;

    /**
     * Resolves the mode from the harness properties.
     *
     * <p>The modes are mutually exclusive and there is no sensible reading of two at once, so
     * setting more than one is rejected rather than resolved by declaration order - which is what
     * the three separate property ladders this replaces did, silently and identically.
     *
     * @return the selected mode, or {@link #FULL} when no mode property is set
     * @throws IllegalStateException if more than one mode property is set
     */
    public static HarnessMode resolve() {
        List<HarnessMode> selected = new ArrayList<>();
        if (HarnessConfig.GLINT_ONLY) selected.add(GLINT);
        if (HarnessConfig.PLAYERS_ONLY) selected.add(PLAYERS);
        if (HarnessConfig.ARMOR_ONLY) selected.add(ARMOR);
        if (HarnessConfig.PITCH_ROLL_SWEEP) selected.add(PITCH_ROLL);
        if (selected.size() > 1)
            throw new IllegalStateException("More than one harness mode selected: " + selected);
        return selected.isEmpty() ? FULL : selected.getFirst();
    }

    /**
     * Returns the sweeps this mode runs, in the order they run.
     *
     * @return the sweeps
     */
    public List<Sweep<?>> sweeps() {
        return switch (this) {
            case FULL -> List.of(new BlockSweep(), new ItemSweep(), new EntitySweep(), new PlayerSweep());
            case GLINT -> List.of(new GlintSweep());
            case PLAYERS -> List.of(new PlayerSweep());
            case ARMOR -> List.of(new ArmorSweep());
            case PITCH_ROLL -> List.of(new PitchRollSweep());
        };
    }
}
