package lib.minecraft.refharness;

import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.sweep.ArmorSweep;
import lib.minecraft.refharness.sweep.BlockSweep;
import lib.minecraft.refharness.sweep.DepthQuantumSweep;
import lib.minecraft.refharness.sweep.EntityAnimationSweep;
import lib.minecraft.refharness.sweep.EntitySweep;
import lib.minecraft.refharness.sweep.GlintSweep;
import lib.minecraft.refharness.sweep.ItemSweep;
import lib.minecraft.refharness.sweep.MenuSweep;
import lib.minecraft.refharness.sweep.PitchRollSweep;
import lib.minecraft.refharness.sweep.PlayerSweep;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which sweeps a run performs, selected from the harness properties.
 *
 * <p><b>A run performs as many modes as it is given</b>, ordered by the {@link Gait} each renders at.
 * That is what lets one client boot produce the frozen sub-trees and both posed ones, where the
 * freezes being read once per JVM used to make each posed set a boot of its own.
 */
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
    /**
     * Only the animated entity references, with vanilla's own animation running.
     *
     * <p>Alone by construction rather than by choice: the freezes the other seven sweeps are defined
     * by are off for the whole boot, so no sweep that wants them can share it.
     */
    ANIMATION,
    /**
     * Only the animated entity references with a stride under them, written to {@code walk/}.
     *
     * <p>{@link #ANIMATION}'s run at a gait: the same sweep, the same schedule and the same
     * subjects, with the two figures a stride is carried on driven rather than held at the zero a
     * subject nothing has moved holds them at. Alone in its boot for {@link #ANIMATION}'s reason and
     * separate from it because a subject cannot be standing still and walking in one client.
     */
    WALK,
    /** Only the diagnostic pitch x roll pose sweep, which writes outside the reference tree. */
    PITCH_ROLL,
    /** Only the diagnostic depth-quantum probe, which writes outside the reference tree. */
    DEPTH_QUANTUM;

    /**
     * Resolves the modes this run performs, in the order they run.
     *
     * <p><b>One boot renders as many modes as are asked for.</b> {@code -Drefharness.modes=a,b,c}
     * names them by enum constant, case-insensitively; the single-mode boolean properties each name
     * one and compose with it, so every task that set one still resolves exactly what it did. An
     * empty selection is {@link #FULL}.
     *
     * <p>A mode is listed once however many times it is named, and the list is ordered by the
     * {@link Gait} its sweeps run at rather than by how it was asked for. That order is a
     * correctness requirement and not a tidiness one - see {@link Gait}.
     *
     * @return the selected modes, ordered
     * @throws IllegalArgumentException if a named mode is not a mode
     */
    public static List<HarnessMode> resolve() {
        Set<HarnessMode> selected = new LinkedHashSet<>();
        for (String name : HarnessConfig.MODES.split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) continue;
            try {
                selected.add(valueOf(trimmed.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Not a harness mode: '" + trimmed + "'. Known: "
                    + Arrays.toString(values()), ex);
            }
        }
        if (HarnessConfig.EVERY_SWEEP) selected.add(EVERY);
        if (HarnessConfig.GLINT_ONLY) selected.add(GLINT);
        if (HarnessConfig.PLAYERS_ONLY) selected.add(PLAYERS);
        if (HarnessConfig.ARMOR_ONLY) selected.add(ARMOR);
        if (HarnessConfig.MENUS_ONLY) selected.add(MENUS);
        if (HarnessConfig.ANIMATED) selected.add(ANIMATION);
        if (HarnessConfig.WALKING) selected.add(WALK);
        if (HarnessConfig.PITCH_ROLL_SWEEP) selected.add(PITCH_ROLL);
        if (HarnessConfig.DEPTH_QUANTUM_PROBE) selected.add(DEPTH_QUANTUM);
        if (selected.isEmpty()) selected.add(FULL);
        return selected.stream().sorted(Comparator.comparing(HarnessMode::gait)).toList();
    }

    /**
     * The gait this mode's sweeps render at.
     *
     * <p>Every mode but the two posed ones is {@link Gait#BIND}, and a mode mixing gaits within
     * itself would have no answer here - which none does, the two posed modes being one sweep each.
     *
     * @return the gait
     */
    public Gait gait() {
        return switch (this) {
            case ANIMATION -> Gait.IDLE;
            case WALK -> Gait.WALK;
            default -> Gait.BIND;
        };
    }

    /**
     * The sweeps a whole run performs, across every selected mode, in the order they run.
     *
     * <p>Duplicates are dropped by output directory rather than by mode, because two modes can name
     * one sweep - {@link #EVERY} and {@link #GLINT} both run the glint sweep - and rendering a
     * sub-tree twice in one boot would write it, overwrite it, and cost the time twice.
     *
     * @param modes the selected modes, already ordered
     * @return the sweeps
     */
    public static List<Sweep<?>> sweeps(List<HarnessMode> modes) {
        Map<String, Sweep<?>> byDirectory = new LinkedHashMap<>();
        for (HarnessMode mode : modes)
            for (Sweep<?> sweep : mode.sweeps()) byDirectory.putIfAbsent(sweep.outputDir(), sweep);
        return List.copyOf(byDirectory.values());
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
            case ANIMATION -> List.of(new EntityAnimationSweep(Gait.IDLE));
            case WALK -> List.of(new EntityAnimationSweep(Gait.WALK));
            case PITCH_ROLL -> List.of(new PitchRollSweep());
            case DEPTH_QUANTUM -> List.of(new DepthQuantumSweep());
        };
    }
}
