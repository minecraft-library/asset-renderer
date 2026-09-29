package lib.minecraft.renderer.asset.rule;

import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The subject an OptiFine CIT rule retextures - the {@code type=} key.
 *
 * <p>Only {@link #ITEM} rules enter the item-icon resolution walk; {@link #ENCHANTMENT} feeds the
 * glint policy. {@link #ARMOR} and {@link #ELYTRA} feed the armour texture override, which walks
 * {@link #ELYTRA} rules for the wings layer and {@link #ARMOR} rules for every other layer.
 * {@link #ARMOR} rules retexture worn armour on every slot a caller hands an item, and
 * {@link #ELYTRA} rules the wings only where a caller hands {@code ElytraKit} an item, which no
 * renderer does. An absent or unrecognised {@code type} defaults to {@link #ITEM}, matching
 * OptiFine.
 */
@Parity(claim = "cit-grammar", mode = Mode.DEMOTE)
public enum CitType {

    /** A held / inventory item retexture - the default and the only kind that reaches icon resolution. */
    ITEM,
    /** An enchantment-glint retexture - feeds the glint policy, never the item texture walk. */
    ENCHANTMENT,
    /** An armor retexture - reaches worn armour on any slot the caller hands an item. */
    ARMOR,
    /**
     * An elytra retexture - reaches the wings only where a caller hands {@code ElytraKit} an item,
     * which no renderer does.
     */
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
