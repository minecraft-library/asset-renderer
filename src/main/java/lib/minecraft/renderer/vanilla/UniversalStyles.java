package lib.minecraft.renderer.vanilla;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The style rows every entity answers whether or not it ships one - the synthesized {@code bind}
 * still, and the standing and walking rows an entity carrying neither is answered with.
 *
 * <p>Fixed before any run: none of the three is parsed, none varies by subject, and what they hold
 * is vanilla's own default behaviour rather than a pack's declaration.
 */
@UtilityClass
@Parity(claim = "asset-layer")
public class UniversalStyles {

    /** The synthesized still row - nothing sourced, nothing driven, nothing toggled, either age. */
    public static final @NotNull PoseStyle BIND_ROW = new PoseStyle(PoseStyle.BIND,
        Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableMap(),
        Concurrent.newUnmodifiableList(), Optional.empty(), Optional.empty());

    /**
     * The standing row an entity that ships none is answered with - elapsed age ramped at slope
     * one, nothing else driven.
     */
    public static final @NotNull PoseStyle UNIVERSAL_IDLE = new PoseStyle(PoseStyle.IDLE,
        Concurrent.newUnmodifiableList(),
        Concurrent.newUnmodifiableMap(Map.of("ageInTicks",
            new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()))),
        Concurrent.newUnmodifiableList(), Optional.empty(), Optional.empty());

    /**
     * The walking row an entity that ships none is answered with - the standing drivers plus the
     * pair a stride is carried on, the amplitude held at one and the phase ramped by it.
     */
    public static final @NotNull PoseStyle UNIVERSAL_STRIDE = strideOver(UNIVERSAL_IDLE);

    /** The universal walking row, composed as the given standing row's drivers plus the walk pair. */
    private static @NotNull PoseStyle strideOver(@NotNull PoseStyle idle) {
        LinkedHashMap<String, StyleDriver> drivers = new LinkedHashMap<>(idle.drivers());
        drivers.put("walkAnimationSpeed",
            new StyleDriver("walkAnimationSpeed", StyleDriver.Wave.HOLD, 0f, 1f, Optional.empty()));
        drivers.put("walkAnimationPos",
            new StyleDriver("walkAnimationPos", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()));
        return new PoseStyle(PoseStyle.STRIDE, Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(drivers), Concurrent.newUnmodifiableList(),
            Optional.empty(), Optional.empty());
    }

}
