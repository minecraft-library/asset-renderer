package lib.minecraft.renderer.option;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * A copper-golem weathering state - one of the four vanilla
 * {@code WeatheringCopper.WeatherState} values. Each state fixes both the body base texture and the
 * emissive eye texture the copper golem renders with, oxidising from {@link #UNAFFECTED} (the
 * default, freshly-placed copper) through {@link #OXIDIZED}. Texture mappings mirror
 * {@code CopperGolemOxidationLevels.getOxidationLevel}'s per-state
 * {@code CopperGolemOxidationLevel(texture, eyeTexture)} pairs.
 */
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum CopperWeathering {

    UNAFFECTED("copper_golem/copper_golem", "copper_golem/copper_golem_eyes"),
    EXPOSED("copper_golem/copper_golem_exposed", "copper_golem/copper_golem_eyes_exposed"),
    WEATHERED("copper_golem/copper_golem_weathered", "copper_golem/copper_golem_eyes_weathered"),
    OXIDIZED("copper_golem/copper_golem_oxidized", "copper_golem/copper_golem_eyes_oxidized");

    /** The body base texture ref for this weathering state (e.g. {@code copper_golem/copper_golem_exposed}). */
    private final @NotNull String baseTexture;

    /** The emissive eye texture ref for this weathering state (e.g. {@code copper_golem/copper_golem_eyes_exposed}). */
    private final @NotNull String eyeTexture;

    /**
     * Looks up a weathering state by name (case-insensitive), e.g. {@code "oxidized"}.
     *
     * @param name the state name
     * @return the matching state, or {@code null} when the name is not a vanilla weathering state
     */
    public static @Nullable CopperWeathering ofName(@Nullable String name) {
        if (name == null) return null;
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAWeathering) {
            return null;
        }
    }
}
