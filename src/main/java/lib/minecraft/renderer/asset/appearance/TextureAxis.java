package lib.minecraft.renderer.asset.appearance;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.option.AppearanceOptions;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A texture axis - one independent dimension along which an overlay pass selects the sheet it draws,
 * resolved at render from the {@link AppearanceOptions} selection. Each axis owns the
 * {@code texture_by} token an overlay names in the model form ({@code entity_models.json}) and the
 * mapping from a selection to the texture ref that pass binds, mirroring vanilla's per-layer texture
 * lookups (tropical fish {@code TropicalFishPatternLayer}, the villager clothing trio, the horse
 * marking pair).
 *
 * <p>Owning the mapping on the axis is what keeps the renderer free of a per-token branch - an
 * overlay holds a {@code TextureAxis} already and asks it, the shape {@link TintAxis} takes for the
 * dye axes. A new texture-driven dimension is one enum constant here plus its {@code texture_by}
 * emission in the tooling.
 */
@EnumLookup
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor
public enum TextureAxis {

    /** The tropical fish's pattern sheet, falling back to the row's own baked default ({@code KOB}). */
    PATTERN("pattern") {
        @Override
        public @NotNull Optional<String> resolve(@NotNull AppearanceOptions appearance,
            @NotNull String texturePrefix, @NotNull Optional<String> rowTexture) {
            return appearance.getPattern().map(TropicalFishPattern::overlayTexture).or(() -> rowTexture);
        }
    },

    /** The iron golem's crack sheet; empty at {@link IronGolemCrackiness#NONE}, so the pass is skipped. */
    CRACKINESS("crackiness") {
        @Override
        public @NotNull Optional<String> resolve(@NotNull AppearanceOptions appearance,
            @NotNull String texturePrefix, @NotNull Optional<String> rowTexture) {
            return appearance.getCrackiness().overlayTexture().or(() -> rowTexture);
        }
    },

    /**
     * The horse coat marking - the adult or baby half of the sheet pair vanilla binds each
     * {@link HorseMarking} to, picked on the render state's own {@code isBaby}. The one axis
     * answering off the age as well as off the selection, and safe here where the villager robe's
     * directory swap is not: a baby draws the baby overlay list and an adult the adult one, both
     * forked on this same flag, so the sheet and the mesh cannot disagree. Empty at the
     * {@link HorseMarking#NONE} default, so the pass is skipped and an unmarked horse draws nothing.
     */
    MARKINGS("markings") {
        @Override
        public @NotNull Optional<String> resolve(@NotNull AppearanceOptions appearance,
            @NotNull String texturePrefix, @NotNull Optional<String> rowTexture) {
            return appearance.isBaby()
                ? appearance.getMarkings().babyOverlayTexture()
                : appearance.getMarkings().overlayTexture();
        }
    },

    /** The copper golem's eye sheet, which every {@link CopperWeathering weathering} state answers. */
    WEATHERING("weathering") {
        @Override
        public @NotNull Optional<String> resolve(@NotNull AppearanceOptions appearance,
            @NotNull String texturePrefix, @NotNull Optional<String> rowTexture) {
            return Optional.of(appearance.getWeathering().eyeTexture());
        }
    },

    /**
     * The villager biome robe, under the directory the pass' own baked ref names: the baby overlay
     * list bakes {@code <prefix>/baby/<biome>} and the adult one {@code <prefix>/type/<biome>}, so
     * the swap is keyed on the row rather than on the appearance's age and the robe's UV layout can
     * never bind over the wrong mesh. A pass whose baby form probed no texture of its own inherits
     * the adult ref and so keeps the adult directory, which is what the jar actually ships.
     */
    TYPE("type") {
        @Override
        public @NotNull Optional<String> resolve(@NotNull AppearanceOptions appearance,
            @NotNull String texturePrefix, @NotNull Optional<String> rowTexture) {
            Villager.Type type = appearance.getVillagerType();
            boolean babyRobe = rowTexture.filter(ref -> ref.contains(BABY_ROBE_SEGMENT)).isPresent();
            return Optional.of(texturePrefix + "/" + (babyRobe ? type.babyOverlaySubPath() : type.overlaySubPath()));
        }
    },

    /** The villager's job clothes; empty at {@link Villager.Profession#NONE}, so the pass is skipped. */
    PROFESSION("profession") {
        @Override
        public @NotNull Optional<String> resolve(@NotNull AppearanceOptions appearance,
            @NotNull String texturePrefix, @NotNull Optional<String> rowTexture) {
            return appearance.getVillagerProfession().textureRef(texturePrefix);
        }
    },

    /**
     * The villager's trade badge; empty for a profession that
     * {@link Villager.Profession#drawsBadge() draws none}. An unnamed tier resolves to
     * {@link Villager.Level#minimum() the first} rather than to nothing, which is what vanilla
     * clamps an unspecified level up to - it has no badge-less job villager.
     */
    PROFESSION_LEVEL("profession_level") {
        @Override
        public @NotNull Optional<String> resolve(@NotNull AppearanceOptions appearance,
            @NotNull String texturePrefix, @NotNull Optional<String> rowTexture) {
            return appearance.getVillagerProfession().drawsBadge()
                ? Optional.of(texturePrefix + "/"
                    + appearance.getVillagerLevel().orElseGet(Villager.Level::minimum).overlaySubPath())
                : Optional.empty();
        }
    };

    /** The path segment marking a baby robe directory, which a {@link #TYPE} pass' baked ref carries. */
    private static final @NotNull String BABY_ROBE_SEGMENT = "/baby/";

    /** The {@code texture_by} token this axis is named by in the model form (e.g. {@code "pattern"}). */
    @KeyField
    private final @NotNull String token;

    /**
     * The texture ref a pass on this axis draws for a selection, or empty when the selection draws
     * nothing so the pass is skipped.
     *
     * @param appearance the axis selections to resolve against
     * @param texturePrefix the entity texture prefix ({@code villager} / {@code zombie_villager})
     *     the villager axes' prefix-relative sub-paths are qualified with
     * @param rowTexture the row's own baked texture ref, which an axis with a baked default falls
     *     back to and the {@link #TYPE} robe directory is read from
     * @return the effective texture ref, or empty when the selection resolves to nothing
     */
    public abstract @NotNull Optional<String> resolve(@NotNull AppearanceOptions appearance,
        @NotNull String texturePrefix, @NotNull Optional<String> rowTexture);

}
