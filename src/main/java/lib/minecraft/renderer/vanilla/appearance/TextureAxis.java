package lib.minecraft.renderer.vanilla.appearance;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.request.AppearanceOptions;
import lib.minecraft.renderer.vanilla.appearance.villager.VillagerLevel;
import org.jetbrains.annotations.NotNull;

/**
 * A texture axis - one independent dimension along which an overlay pass selects the sheet it draws,
 * resolved at render by {@link AppearanceOptions#texture}. Each axis owns the
 * {@code texture_by} token an overlay names in the model form ({@code entity_models.json}) and the
 * mapping from a selection to the texture ref that pass binds, mirroring vanilla's per-layer texture
 * lookups (tropical fish {@code TropicalFishPatternLayer}, the villager clothing trio, the horse
 * marking pair).
 *
 * <p>The appearance answers the mapping because it holds every selection the mapping reads, and that
 * is what keeps the renderer free of a per-token branch - an overlay holds a {@code TextureAxis}
 * already and hands it over. A new texture-driven dimension is one enum constant here, its arm in
 * that switch, and its {@code texture_by} emission in the tooling.
 */
@EnumLookup
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor
public enum TextureAxis {

    /** The tropical fish's pattern sheet, falling back to the row's own baked default ({@code KOB}). */
    PATTERN("pattern"),

    /** The iron golem's crack sheet; empty at {@link IronGolemCrackiness#NONE}, so the pass is skipped. */
    CRACKINESS("crackiness"),

    /**
     * The horse coat marking - the adult or baby half of the sheet pair vanilla binds each
     * {@link HorseMarking} to, picked on the render state's own {@code isBaby}. The one axis
     * answering off the age as well as off the selection, and safe here where the villager robe's
     * directory swap is not: a baby draws the baby overlay list and an adult the adult one, both
     * forked on this same flag, so the sheet and the mesh cannot disagree. Empty at the
     * {@link HorseMarking#NONE} default, so the pass is skipped and an unmarked horse draws nothing.
     */
    MARKINGS("markings"),

    /** The copper golem's eye sheet, which every {@link CopperWeathering weathering} state answers. */
    WEATHERING("weathering"),

    /**
     * The villager biome robe, under the directory the pass' own baked ref names: the baby overlay
     * list bakes {@code <prefix>/baby/<biome>} and the adult one {@code <prefix>/type/<biome>}, so
     * the swap is keyed on the row rather than on the appearance's age and the robe's UV layout can
     * never bind over the wrong mesh. A pass whose baby form probed no texture of its own inherits
     * the adult ref and so keeps the adult directory, which is what the jar actually ships.
     */
    TYPE("type"),

    /** The villager's job clothes; empty at {@link VillagerProfession#NONE}, so the pass is skipped. */
    PROFESSION("profession"),

    /**
     * The villager's trade badge; empty for a profession that
     * {@link VillagerProfession#drawsBadge() draws none}. An unnamed tier resolves to
     * {@link VillagerLevel#minimum() the first} rather than to nothing, which is what vanilla
     * clamps an unspecified level up to - it has no badge-less job villager.
     */
    PROFESSION_LEVEL("profession_level");

    /** The {@code texture_by} token this axis is named by in the model form (e.g. {@code "pattern"}). */
    @KeyField
    private final @NotNull String token;

}
