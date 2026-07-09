package lib.minecraft.renderer.tooling2.entity;

import lib.minecraft.renderer.tooling2.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Node {@code armor_type} - classifies the family's armor rendering off the addLayer roster
 * (SPINE 3.1 row 3): {@code humanoid} iff the roster contains a {@code HumanoidArmorLayer},
 * else {@code none}. Always present - the flattener hard-requires it (SPINE 8 q9).
 *
 * <p>The layer identity is a class name from {@code VanillaSourceClasses.Types}, exact-matched
 * (vanilla constructs the layer class directly at every {@code addLayer} site) - the legacy
 * bare-suffix {@code endsWith} stray dies.
 */
final class EntityArmorTypeResolver {

    private final @NotNull List<EntityRendererResolver.LayerSite> layerRoster;

    EntityArmorTypeResolver(@NotNull List<EntityRendererResolver.LayerSite> layerRoster) {
        this.layerRoster = layerRoster;
    }

    /**
     * The armor classification, always non-null.
     *
     * @return {@code humanoid} or {@code none}
     */
    @NotNull String resolve() {
        for (EntityRendererResolver.LayerSite site : this.layerRoster)
            if (VanillaSourceClasses.Types.HUMANOID_ARMOR_LAYER.equals(site.layerClass())) return "humanoid";
        return "none";
    }

}
