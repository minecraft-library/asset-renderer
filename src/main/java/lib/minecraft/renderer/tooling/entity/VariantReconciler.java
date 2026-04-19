package lib.minecraft.renderer.tooling.entity;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classifies each Bedrock geometry id against the authoritative mob list from
 * {@link MobRegistryDiscovery}.
 *
 * <p>The reconciler answers one question: <i>is this Bedrock geometry a renderable mob or a
 * known biome/state/pose variant of one?</i> The bundled {@code entity_models.json} retains
 * matches, drops everything else (projectiles, held-item animations, particle effects,
 * Bedrock-only experimental geometries).
 *
 * <p>Classification rules, in order:
 * <ol>
 *   <li>Hard drop patterns match → skip. Covers projectiles, held-item animation frames, and
 *       particle effects that never belong in a mob atlas regardless of naming overlap (e.g.
 *       {@code bow_pulling_0}, {@code breeze_wind_5}, {@code evocation_fang}, {@code wind_charge}).</li>
 *   <li>Direct mob id match → keep as <b>base</b> (e.g. {@code "zombie"} matches
 *       {@link MobRegistryDiscovery} id {@code zombie}).</li>
 *   <li>Variant-suffix strip match → keep as <b>variant</b>. Each known suffix is tried;
 *       a successful strip whose residue is a mob id keeps the entry and records the parent
 *       mob id (e.g. {@code "cow_cold"} → strip {@code _cold} → {@code "cow"} is a mob → keep
 *       as variant of {@code cow}).</li>
 *   <li>Otherwise → drop.</li>
 * </ol>
 *
 * <p>The variant suffix table covers the four Bedrock-only split patterns used in vanilla MC:
 * biome variants ({@code _cold}, {@code _warm}), state flags ({@code _charged}, {@code _sheared}),
 * pose/activity variants ({@code _flower}, {@code _running}, {@code _sitting}, {@code _star}),
 * and attachment overlays ({@code _ropes}). Any geometry that matches a suffix but whose base
 * name isn't in the Java-derived mob list falls through to "drop" - a stale variant from a
 * Bedrock-only experimental entity.
 */
@UtilityClass
public final class VariantReconciler {

    /**
     * Variant suffixes stripped to find the base mob. Order matters: longer/more specific
     * suffixes are tried first so {@code _sitting} can't accidentally match a trailing
     * {@code _g} residue.
     */
    private static final @NotNull List<String> VARIANT_SUFFIXES = List.of(
        "_cold", "_warm", "_hot",
        "_charged", "_sheared",
        "_running", "_sitting", "_flower", "_star",
        "_ropes"
    );

    /**
     * Regex drop-list for Bedrock geometries that are never mobs regardless of name overlap.
     * Covers projectiles, held-item animation poses, particle / breath effects, Bedrock-only
     * dev models, and attachment helpers that aren't first-class mob icons.
     */
    private static final @NotNull List<Pattern> HARD_DROP_PATTERNS = List.of(
        Pattern.compile("^bow(_pulling_\\d+|_standby)$"),
        Pattern.compile("^crossbow(_pulling_\\d+|_standby|_arrow|_rocket)$"),
        Pattern.compile("^breeze_wind_.*$|^breeze_eyes$"),
        Pattern.compile("^(evocation_fang|spear|wind_charge|llamaspit)$"),
        Pattern.compile("^(humanoid_custom|npc|harness|minecart)$")
    );

    /**
     * Residual hand-aliases for Bedrock identifiers that still don't match a Java
     * {@code EntityType} registry id after the {@code entity/*.entity.json} manifest resolves
     * the geometry-to-identifier mapping. Every other historical alias is now handled by the
     * Bedrock pack itself via {@link BedrockEntityManifest} - this list only covers the handful
     * of identifiers Mojang renamed on the Java side without updating the Bedrock client-entity
     * definition:
     *
     * <ul>
     *   <li>{@code zombie_pigman} (Bedrock, pre-Java-renaming) -&gt; {@code zombified_piglin}</li>
     *   <li>{@code evocation_illager} (Bedrock legacy) -&gt; {@code evoker}</li>
     * </ul>
     *
     * <p>Aliases are applied in {@link #classify} <i>after</i> the hard-drop filter so a
     * legitimately-dropped id never sneaks through via an alias.
     */
    private static final @NotNull Map<String, String> BEDROCK_NAME_ALIASES = Map.ofEntries(
        Map.entry("zombie_pigman", "zombified_piglin"),
        Map.entry("evocation_illager", "evoker")
    );

    /**
     * Classification result. {@link #canonicalId} is the Java-canonical entity id after alias
     * resolution - this is what downstream code stores the entry under, regardless of what the
     * Bedrock geometry was originally named. {@link #baseMobId} is non-null only for variants
     * (identifying the parent mob); base mobs have a null {@code baseMobId}.
     *
     * @param isMob {@code true} when the geometry should be retained in the output JSON
     * @param canonicalId the Java-canonical entity id under which to store the entry, or
     *     {@code null} when {@link #isMob} is {@code false}
     * @param baseMobId the parent mob's id when the geometry is a variant, or {@code null} when
     *     the geometry is the base mob itself or not a mob at all
     */
    public record Classification(
        boolean isMob,
        @Nullable String canonicalId,
        @Nullable String baseMobId
    ) {
        /** Convenience: a drop decision. */
        public static @NotNull Classification drop() {
            return new Classification(false, null, null);
        }

        /** Convenience: a base-mob keep decision. */
        public static @NotNull Classification base(@NotNull String canonicalId) {
            return new Classification(true, canonicalId, null);
        }

        /** Convenience: a variant keep decision tied to a parent mob. */
        public static @NotNull Classification variant(@NotNull String canonicalId, @NotNull String baseMobId) {
            return new Classification(true, canonicalId, baseMobId);
        }
    }

    /**
     * Classifies one Bedrock-derived entity id against the known mob set.
     *
     * @param name the namespaced-less Bedrock entity name, e.g. {@code "cow"}, {@code "cow_cold"},
     *     or {@code "witherBoss"}
     * @param knownMobIds the set of entity ids emitted by {@link MobRegistryDiscovery}
     * @return the classification decision, with {@link Classification#canonicalId canonicalId}
     *     set to the Java-canonical id when the entry is kept
     */
    public static @NotNull Classification classify(@NotNull String name, @NotNull Set<String> knownMobIds) {
        for (Pattern drop : HARD_DROP_PATTERNS) {
            if (drop.matcher(name).matches())
                return Classification.drop();
        }

        String canonical = BEDROCK_NAME_ALIASES.getOrDefault(name, name);

        if (knownMobIds.contains(canonical))
            return Classification.base(canonical);

        for (String suffix : VARIANT_SUFFIXES) {
            if (!canonical.endsWith(suffix)) continue;
            String base = canonical.substring(0, canonical.length() - suffix.length());
            if (knownMobIds.contains(base))
                return Classification.variant(canonical, base);
        }

        return Classification.drop();
    }

}
