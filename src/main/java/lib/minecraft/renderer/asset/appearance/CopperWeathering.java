package lib.minecraft.renderer.asset.appearance;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;

/**
 * A copper-golem weathering state - one of the four vanilla
 * {@code WeatheringCopper.WeatherState} values. Each state fixes both the body base texture and the
 * emissive eye texture the copper golem renders with, oxidising from {@link #UNAFFECTED} (the
 * default, freshly-placed copper) through {@link #OXIDIZED}. Texture mappings mirror
 * {@code CopperGolemOxidationLevels.getOxidationLevel}'s per-state
 * {@code CopperGolemOxidationLevel(texture, eyeTexture)} pairs.
 */
@EnumLookup
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
     * The behavioural state whose base texture this weathering draws, as the entity state axis keys it.
     *
     * <p>Empty for {@link #UNAFFECTED}, whose texture is the subject's own base state rather than an
     * alternate: freshly-placed copper is what a copper golem already is, so there is nothing for a
     * selection to swap to and the axis carries no entry for it.
     *
     * @return the state key, or empty when this weathering is the subject's base state
     */
    public @NotNull Optional<String> stateKey() {
        return this == UNAFFECTED ? Optional.empty() : Optional.of(name().toLowerCase(Locale.ROOT));
    }

}
