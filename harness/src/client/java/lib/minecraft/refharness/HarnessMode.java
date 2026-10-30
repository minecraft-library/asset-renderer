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
import lib.minecraft.refharness.sweep.PackIconSweep;
import lib.minecraft.refharness.sweep.PackSourceSweep;
import lib.minecraft.refharness.sweep.PitchRollSweep;
import lib.minecraft.refharness.sweep.PlayerSweep;
import lib.minecraft.refharness.sweep.ScheduleRoster;
import lib.minecraft.refharness.sweep.TooltipSweep;

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
     * not the whole reference tree - {@link #EVERY} with the two posed modes is - so a renderer change
     * this mode covers can still leave every sub-tree it does not write behind.
     */
    FULL,
    /**
     * Every frozen reference sweep, in one run - {@link #FULL}'s four plus glint, armor, menus and
     * tooltips.
     */
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
     * Only the tooltip references - every subject of asset-renderer's tooltip roster drawn through the
     * client's own tooltip call, and its box alone, and every plain line through the text draw of the
     * client's hotbar-name call.
     */
    TOOLTIPS,
    /**
     * Only the animated entity references, with vanilla's own animation running, written to
     * {@code idle/}.
     *
     * <p>The freeze is lifted per sweep rather than per boot, so this shares a boot with the frozen
     * sweeps, which run before it. Each subject is rendered at its idle schedule from the asset side's
     * roster, read when the run starts.
     */
    ANIMATION,
    /**
     * Only the animated entity references with a stride under them, written to {@code walk/}.
     *
     * <p>{@link #ANIMATION}'s sweep at the walking gait: the same subjects, each rendered at its walk
     * schedule from the roster read when the run starts, the walk speed and the walk-clock rate it
     * names armed under it. It shares a boot with the frozen sweeps and with {@link #ANIMATION}, every
     * one of which runs before it.
     */
    WALK,
    /** Only the diagnostic pitch x roll pose sweep, which writes outside the reference tree. */
    PITCH_ROLL,
    /** Only the diagnostic depth-quantum probe, which writes outside the reference tree. */
    DEPTH_QUANTUM,
    /**
     * Only the diagnostic pack-source probe, which renders nothing and writes outside the reference
     * tree: which pack the client takes each resource from, with {@link HarnessConfig#PACKS}
     * selected over vanilla.
     *
     * <p>A {@linkplain #loadsPacks() pack mode}, the only kind a run naming packs may select - the
     * packs are selected before the first resource load, so every other sweep in the boot would render
     * through them.
     */
    PACK_SOURCES,
    /**
     * Only the diagnostic pack-icon sweep, which draws the stacks asset-renderer's pack-icon roster
     * names through the slot path, with {@link HarnessConfig#PACKS} selected over vanilla, into a
     * directory outside the reference tree.
     *
     * <p>A {@linkplain #loadsPacks() pack mode}, for {@link #PACK_SOURCES}'s reason.
     */
    PACK_ICONS,
    /**
     * Only the tooltip sweep over the section of asset-renderer's tooltip roster for
     * {@link HarnessConfig#PACKS}, with those packs selected over vanilla, into
     * {@code pack-tooltips/<label>/} beside the reference tree.
     *
     * <p>A {@linkplain #loadsPacks() pack mode}, for {@link #PACK_SOURCES}'s reason.
     */
    PACK_TOOLTIPS;

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
     * <p>A run naming {@link HarnessConfig#PACKS} is refused unless every mode it selects is a
     * {@linkplain #loadsPacks() pack mode}, because the packs load at boot and every sweep after them
     * would draw through them.
     *
     * @return the selected modes, ordered
     * @throws IllegalArgumentException if a named mode is not a mode
     * @throws IllegalStateException if packs are named for a run selecting any other mode
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
        if (!HarnessConfig.packIds().isEmpty() && !selected.stream().allMatch(HarnessMode::loadsPacks))
            throw new IllegalStateException(String.format(
                "Resource packs '%s' load only under the pack modes '%s', and this run selects '%s'",
                HarnessConfig.PACKS, Arrays.stream(values()).filter(HarnessMode::loadsPacks).toList(), selected));
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
     * Whether this mode may run with resource packs selected - true only of the pack modes, whose
     * sweeps write outside the reference tree and exist to answer for a pack.
     *
     * @return whether the mode may load packs
     */
    public boolean loadsPacks() {
        return this == PACK_SOURCES || this == PACK_ICONS || this == PACK_TOOLTIPS;
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
     * <p>The two posed modes read the entity schedule roster beside the output root here, at the start
     * of the run and ahead of every sweep, so a roster that is absent, malformed or resolved for
     * another client stops the run before any reference is written.
     *
     * @return the sweeps
     * @throws IllegalStateException if a posed mode's schedule roster cannot be read
     */
    public List<Sweep<?>> sweeps() {
        return switch (this) {
            case FULL -> List.of(new BlockSweep(), new ItemSweep(), new EntitySweep(), new PlayerSweep());
            case EVERY -> List.of(new BlockSweep(), new ItemSweep(), new EntitySweep(), new PlayerSweep(),
                new GlintSweep(), new ArmorSweep(), new MenuSweep(), new TooltipSweep());
            case GLINT -> List.of(new GlintSweep());
            case PLAYERS -> List.of(new PlayerSweep());
            case ARMOR -> List.of(new ArmorSweep());
            case MENUS -> List.of(new MenuSweep());
            case TOOLTIPS -> List.of(new TooltipSweep());
            case ANIMATION -> List.of(new EntityAnimationSweep(Gait.IDLE, ScheduleRoster.load(HarnessConfig.OUTPUT_DIR)));
            case WALK -> List.of(new EntityAnimationSweep(Gait.WALK, ScheduleRoster.load(HarnessConfig.OUTPUT_DIR)));
            case PITCH_ROLL -> List.of(new PitchRollSweep());
            case DEPTH_QUANTUM -> List.of(new DepthQuantumSweep());
            case PACK_SOURCES -> List.of(new PackSourceSweep());
            case PACK_ICONS -> List.of(new PackIconSweep());
            case PACK_TOOLTIPS -> List.of(new TooltipSweep(HarnessConfig.packIds()));
        };
    }
}
