package lib.minecraft.renderer.tooling2.colormap;

import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.tooling2.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling2.policy.AsmContext;
import lib.minecraft.renderer.tooling2.policy.Navigation;
import lib.minecraft.renderer.tooling2.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;

/**
 * The colormap flow's complete policy roster (SPINE 2.1: P47a) - one constant per vanilla
 * biome colormap (the fixed 3-PNG list). Each constant declares the jar-resource path (a
 * convention, not bytecode) and the {@code ColorMap.Type} the name maps to (the genuinely
 * undetectable part - 09 SS3.2 rows 41-42). Never fetches ({@code PolicyPurityTest}): the walk
 * reads the bytes.
 */
enum ColorMapPolicies implements NavigationPolicy {

    /** The grass biome colormap. */
    GRASS("grass.png", ColorMap.Type.GRASS,
        "vanilla asset layout, not bytecode; the grass.png -> ColorMap.Type.GRASS mapping is"
            + " convention (ToolingColorMaps.java:117-121)"),

    /** The foliage biome colormap. */
    FOLIAGE("foliage.png", ColorMap.Type.FOLIAGE,
        "vanilla asset layout, not bytecode; the foliage.png -> ColorMap.Type.FOLIAGE mapping is"
            + " convention (ToolingColorMaps.java:117-121)"),

    /** The dry-foliage biome colormap. */
    DRY_FOLIAGE("dry_foliage.png", ColorMap.Type.DRY_FOLIAGE,
        "vanilla asset layout, not bytecode; the dry_foliage.png -> ColorMap.Type.DRY_FOLIAGE"
            + " mapping is convention (ToolingColorMaps.java:117-121)");

    private final @NotNull String entryPath;
    private final @NotNull ColorMap.Type type;
    private final @NotNull String provenance;

    ColorMapPolicies(@NotNull String fileName, @NotNull ColorMap.Type type, @NotNull String provenance) {
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

    /** The {@code ColorMap.Type} this colormap declares. */
    @NotNull ColorMap.Type type() {
        return this.type;
    }

}
