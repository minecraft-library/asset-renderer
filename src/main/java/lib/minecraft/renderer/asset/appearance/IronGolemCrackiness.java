package lib.minecraft.renderer.asset.appearance;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * An iron-golem damage level - one of the four vanilla {@code Crackiness.Level} values drawn as a
 * same-geometry crack-texture overlay over the body. {@link #NONE} (the default, an undamaged golem)
 * draws nothing; the three damaged levels each carry the {@code iron_golem/iron_golem_crackiness_*}
 * overlay texture {@code IronGolemCrackinessLayer} composites over the body. Names and texture
 * mappings mirror {@code IronGolemCrackinessLayer}'s per-level texture map (which omits {@code NONE}).
 */
@EnumLookup
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum IronGolemCrackiness {

    NONE(null),
    LOW("iron_golem/iron_golem_crackiness_low"),
    MEDIUM("iron_golem/iron_golem_crackiness_medium"),
    HIGH("iron_golem/iron_golem_crackiness_high");

    /** The crack-overlay texture ref drawn over the body, or {@code null} for {@link #NONE}. */
    private final @Nullable String overlayTexture;

    /**
     * The crack-overlay texture ref drawn over the body (e.g.
     * {@code iron_golem/iron_golem_crackiness_low}), or empty for {@link #NONE}, which draws no overlay.
     *
     * @return the overlay texture ref, or empty when this level draws nothing
     */
    public @NotNull Optional<String> overlayTexture() {
        return Optional.ofNullable(this.overlayTexture);
    }
}
