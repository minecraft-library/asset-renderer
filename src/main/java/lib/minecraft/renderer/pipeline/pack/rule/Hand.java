package lib.minecraft.renderer.pipeline.pack.rule;

import org.jetbrains.annotations.NotNull;

/**
 * The hand an OptiFine CIT rule requires the item to occupy - the {@code hand=} key (07-optifine
 * §2.1). GUI icon rendering counts as the main hand (07-optifine §7.1), so a {@link #OFF} rule never
 * matches this renderer.
 */
public enum Hand {

    /** No hand constraint - the default. */
    ANY,
    /** Main-hand only - satisfied by GUI icon rendering. */
    MAIN,
    /** Off-hand only - never satisfied by GUI icon rendering. */
    OFF;

    /**
     * Parses a {@code hand=} value, defaulting to {@link #ANY} for an absent or unrecognised token.
     *
     * @param raw the raw {@code hand} value, or {@code null} when the key is absent
     * @return the parsed hand, or {@link #ANY} when absent or unrecognised
     */
    public static @NotNull Hand parse(String raw) {
        if (raw == null) return ANY;
        return switch (raw.trim().toLowerCase()) {
            case "main" -> MAIN;
            case "off" -> OFF;
            default -> ANY;
        };
    }

}
