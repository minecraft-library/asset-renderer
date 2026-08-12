package lib.minecraft.renderer.tooling.colormap;

import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;

/**
 * The colormap flow's complete policy roster - one constant per vanilla
 * biome colormap (the fixed 3-PNG list). Each constant declares the jar-resource path (a
 * convention, not bytecode) and answers it as the {@link Navigation.AtResource} coordinate the
 * walk reads the pixels at. The colormap type the renderer indexes a map under is that path's
 * file stem uppercased, so it is the walk's arithmetic and the renderer's enum stays out of this
 * build. Never fetches ({@code PolicyPurityTest}): the walk reads the bytes.
 */
enum ColorMapPolicies implements NavigationPolicy {

    /** The grass biome colormap. */
    GRASS("grass.png",
        "vanilla asset layout, not bytecode; the grass.png -> GRASS mapping is convention"),

    /** The foliage biome colormap. */
    FOLIAGE("foliage.png",
        "vanilla asset layout, not bytecode; the foliage.png -> FOLIAGE mapping is convention"),

    /** The dry-foliage biome colormap. */
    DRY_FOLIAGE("dry_foliage.png",
        "vanilla asset layout, not bytecode; the dry_foliage.png -> DRY_FOLIAGE mapping is convention");

    private final @NotNull String entryPath;
    private final @NotNull String provenance;

    ColorMapPolicies(@NotNull String fileName, @NotNull String provenance) {
        this.entryPath = VanillaSourceClasses.Paths.COLORMAP_DIR + fileName;
        this.provenance = provenance;
    }

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        return new Navigation.AtResource(this.entryPath);
    }

}
