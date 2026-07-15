package lib.minecraft.renderer.pipeline.pack.rule;

import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Optional;

/**
 * The face a CTM rule targets, named by the OptiFine {@code faces=} grammar - never a renderer
 * {@code UP} / {@code DOWN} enum. The {@code sides} and {@code all} aliases expand at parse.
 */
public enum CtmFace {

    /** The north face. */
    NORTH,
    /** The south face. */
    SOUTH,
    /** The east face. */
    EAST,
    /** The west face. */
    WEST,
    /** The top face - the grammar name, never renderer {@code UP}. */
    TOP,
    /** The bottom face - the grammar name, never renderer {@code DOWN}. */
    BOTTOM;

    /** The four horizontal side faces. */
    private static final @NotNull EnumSet<CtmFace> SIDES = EnumSet.of(NORTH, SOUTH, EAST, WEST);

    /**
     * Parses a {@code faces=} value into the exact face set. An absent or blank value defaults to all
     * six faces; {@code sides} expands to N/S/E/W and {@code all} to all six; an unknown token returns
     * empty so the CTM parser can reject the rule (fail-closed) rather than falling back to ALL.
     *
     * @param raw the raw {@code faces} value, or {@code null} when the key is absent
     * @return the resolved face set, or empty when a token is unrecognised
     */
    public static @NotNull Optional<EnumSet<CtmFace>> parseSet(String raw) {
        if (raw == null || raw.isBlank()) return Optional.of(EnumSet.allOf(CtmFace.class));

        EnumSet<CtmFace> faces = EnumSet.noneOf(CtmFace.class);
        for (String token : raw.trim().toLowerCase().split("[,\\s]+")) {
            if (token.isEmpty()) continue;
            switch (token) {
                case "sides" -> faces.addAll(SIDES);
                case "all" -> faces.addAll(EnumSet.allOf(CtmFace.class));
                case "north" -> faces.add(NORTH);
                case "south" -> faces.add(SOUTH);
                case "east" -> faces.add(EAST);
                case "west" -> faces.add(WEST);
                case "top" -> faces.add(TOP);
                case "bottom" -> faces.add(BOTTOM);
                default -> {
                    return Optional.empty();
                }
            }
        }
        return faces.isEmpty() ? Optional.of(EnumSet.allOf(CtmFace.class)) : Optional.of(faces);
    }

}
