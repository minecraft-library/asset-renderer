package lib.minecraft.renderer.tooling.index;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The suffixes a texture stem carries that do not name a base texture, derived once per run from
 * the live entity-texture universe rather than listed.
 *
 * <p>A state overlay ({@code _eyes}, {@code _exposed}) recurs across texture directories where a
 * data-variant name ({@code _lucy}) appears once, which is what tells the two apart without a
 * denylist anybody has to keep.
 */
@UtilityClass
public final class NonBaseSuffixIndex {

    /**
     * Derives the non-base-suffix set from the live entity-texture universe, once per
     * run: a suffix {@code _X} qualifies when a {@code <prefix>.png} and
     * {@code <prefix>_X.png} sibling pair co-exists in at least {@code minRecurrence} texture
     * directories - state overlays ({@code _eyes},
     * {@code _exposed}) recur; data-variant names ({@code _lucy}) fall out. The INFO line
     * is the version-bump drift surface.
     *
     * @param session the live run
     * @param minRecurrence the fewest texture directories a base and suffixed sibling pair has to
     *     co-exist in for its suffix to qualify, supplied by the flow that declares the threshold
     * @return the derived suffix set
     */
    public static @NotNull Set<String> deriveNonBaseSuffixes(@NotNull ToolingRun session, int minRecurrence) {
        String prefix = SourceClasses.Paths.ASSETS_ROOT + SourceClasses.Paths.TEXTURES_ENTITY;
        Set<String> stems = session.cache().list(prefix, ".png")
            .stream()
            .map(entryPath -> entryPath.substring(prefix.length(), entryPath.length() - ".png".length()))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Integer> suffixCount = new HashMap<>();
        for (String stem : stems) {
            int slash = stem.lastIndexOf('/');
            String dir = slash >= 0 ? stem.substring(0, slash + 1) : "";
            String local = slash >= 0 ? stem.substring(slash + 1) : stem;
            int underscore = local.indexOf('_');
            while (underscore > 0) {
                String prefixLocal = local.substring(0, underscore);
                String suffix = local.substring(underscore);
                if (stems.contains(dir + prefixLocal)) suffixCount.merge(suffix, 1, Integer::sum);
                underscore = local.indexOf('_', underscore + 1);
            }
        }
        Set<String> out = suffixCount.entrySet()
            .stream()
            .filter(entry -> entry.getValue() >= minRecurrence)
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        session.diagnostics().child("textures").info("derived %d non-base texture suffixes: %s", out.size(), out);
        return out;
    }

}
