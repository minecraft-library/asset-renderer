package lib.minecraft.refharness;

import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.sweep.ArmorSweep;
import lib.minecraft.refharness.sweep.BlockSweep;
import lib.minecraft.refharness.sweep.DepthQuantumSweep;
import lib.minecraft.refharness.sweep.EntitySweep;
import lib.minecraft.refharness.sweep.GlintSweep;
import lib.minecraft.refharness.sweep.ItemSweep;
import lib.minecraft.refharness.sweep.MenuSweep;
import lib.minecraft.refharness.sweep.PitchRollSweep;
import lib.minecraft.refharness.sweep.PlayerSweep;

import java.util.ArrayList;
import java.util.List;

/** Which sweeps a run performs. Selected once, from the harness properties. */
public enum HarnessMode {

    /**
     * The reference sweep proper - blocks, items, entities and the player. Despite the name this is
     * not the whole reference tree: the glint and armor sweeps are not in it, so a renderer change
     * this mode covers can still leave those two behind. {@link #EVERY} is the one that covers all of
     * it.
     */
    FULL,
    /** Every reference sweep there is, in one run - {@link #FULL}'s four plus glint, armor and menus. */
    EVERY,
    /** Only the animated-glint frame sequences. */
    GLINT,
    /** Only the player references. */
    PLAYERS,
    /** Only the armored-mob diagnostics. */
    ARMOR,
    /** Only the container-screen references. */
    MENUS,
    /** Only the diagnostic pitch x roll pose sweep, which writes outside the reference tree. */
    PITCH_ROLL,
    /** Only the diagnostic depth-quantum probe, which writes outside the reference tree. */
    DEPTH_QUANTUM;

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
        if (HarnessConfig.EVERY_SWEEP) selected.add(EVERY);
        if (HarnessConfig.GLINT_ONLY) selected.add(GLINT);
        if (HarnessConfig.PLAYERS_ONLY) selected.add(PLAYERS);
        if (HarnessConfig.ARMOR_ONLY) selected.add(ARMOR);
        if (HarnessConfig.MENUS_ONLY) selected.add(MENUS);
        if (HarnessConfig.PITCH_ROLL_SWEEP) selected.add(PITCH_ROLL);
        if (HarnessConfig.DEPTH_QUANTUM_PROBE) selected.add(DEPTH_QUANTUM);
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
            case EVERY -> List.of(new BlockSweep(), new ItemSweep(), new EntitySweep(), new PlayerSweep(),
                new GlintSweep(), new ArmorSweep(), new MenuSweep());
            case GLINT -> List.of(new GlintSweep());
            case PLAYERS -> List.of(new PlayerSweep());
            case ARMOR -> List.of(new ArmorSweep());
            case MENUS -> List.of(new MenuSweep());
            case PITCH_ROLL -> List.of(new PitchRollSweep());
            case DEPTH_QUANTUM -> List.of(new DepthQuantumSweep());
        };
    }
}
