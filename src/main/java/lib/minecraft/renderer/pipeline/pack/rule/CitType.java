package lib.minecraft.renderer.pipeline.pack.rule;

import org.jetbrains.annotations.NotNull;

/**
 * The subject an OptiFine CIT rule retextures - the {@code type=} key (07-optifine §2.1).
 *
 * <p>Only {@link #ITEM} rules enter the item-icon resolution walk; {@link #ENCHANTMENT} feeds the
 * glint policy (03-rules §7) and {@link #ARMOR} / {@link #ELYTRA} are parse-and-hold until pack-aware
 * equipment rendering lands (D3.7). An absent or unrecognised {@code type} defaults to {@link #ITEM},
 * matching OptiFine.
 */
public enum CitType {

    /** A held / inventory item retexture - the default and the only kind that reaches icon resolution. */
    ITEM,
    /** An enchantment-glint retexture - feeds the glint policy, never the item texture walk. */
    ENCHANTMENT,
    /** An armor retexture - parsed and held until equipment rendering consumes packs. */
    ARMOR,
    /** An elytra retexture - parsed and held alongside {@link #ARMOR}. */
    ELYTRA;

    /**
     * Parses a {@code type=} value, defaulting to {@link #ITEM} for an absent or unrecognised token.
     *
     * @param raw the raw {@code type} value, or {@code null} when the key is absent
     * @return the parsed type, or {@link #ITEM} when absent or unrecognised
     */
    public static @NotNull CitType parse(String raw) {
        if (raw == null) return ITEM;
        return switch (raw.trim().toLowerCase()) {
            case "enchantment" -> ENCHANTMENT;
            case "armor" -> ARMOR;
            case "elytra" -> ELYTRA;
            default -> ITEM;
        };
    }

}
