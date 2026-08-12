package lib.minecraft.renderer.tooling.colormap;

import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;

/**
 * The colormap flow's complete policy roster - one constant per vanilla
 * biome colormap (the fixed 3-PNG list). Each constant declares the jar-resource path (a
 * convention, not bytecode) and the colormap type name the renderer indexes it under (the
 * genuinely undetectable part), carried as the value it is written to JSON as rather than as the
 * renderer's enum - this build does not resolve against the renderer. Never fetches
 * ({@code PolicyPurityTest}): the walk reads the bytes.
 */
enum ColorMapPolicies implements NavigationPolicy {

    /** The grass biome colormap. */
    GRASS("grass.png", "GRASS",
        "vanilla asset layout, not bytecode; the grass.png -> GRASS mapping is convention"),

    /** The foliage biome colormap. */
    FOLIAGE("foliage.png", "FOLIAGE",
        "vanilla asset layout, not bytecode; the foliage.png -> FOLIAGE mapping is convention"),

    /** The dry-foliage biome colormap. */
    DRY_FOLIAGE("dry_foliage.png", "DRY_FOLIAGE",
        "vanilla asset layout, not bytecode; the dry_foliage.png -> DRY_FOLIAGE mapping is convention");

    private final @NotNull String entryPath;
    private final @NotNull String type;
    private final @NotNull String provenance;

    ColorMapPolicies(@NotNull String fileName, @NotNull String type, @NotNull String provenance) {
        this.entryPath = VanillaSourceClasses.Paths.COLORMAP_DIR + fileName;
        this.type = type;
        this.provenance = provenance;
    }

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        return new Navigation.AtResource(this.entryPath);
    }

    /** The jar entry path this colormap reads (missing tolerated, decode failure fatal). */
    @NotNull String entryPath() {
        return this.entryPath;
    }

    /** The colormap type name this colormap declares, as written to JSON. */
    @NotNull String type() {
        return this.type;
    }

}
