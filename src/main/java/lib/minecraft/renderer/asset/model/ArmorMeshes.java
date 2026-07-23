package lib.minecraft.renderer.asset.model;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * The worn-armor meshes, indexed by the name a wearer's renderer holds its armor set under.
 *
 * @param byName the mesh each named armor set resolves to
 * @param shared the mesh a wearer whose renderer names no set is dressed in - vanilla's generic
 *     humanoid set, which is the common case rather than a fallback for a gap
 */
public record ArmorMeshes(
    @NotNull Map<String, ArmorMeshData> byName, @NotNull Optional<ArmorMeshData> shared) {

    /** The empty index - what a missing or unreadable armor resource resolves to. */
    public static final @NotNull ArmorMeshes NONE = new ArmorMeshes(Map.of(), Optional.empty());

    /**
     * The mesh a wearer is dressed in: the one it names, or the shared mesh when it names none or
     * names one this index does not carry.
     *
     * @param name the armor-set name the wearer's renderer holds, or empty
     * @return the resolved mesh, or empty when even the shared mesh is absent
     */
    public @NotNull Optional<ArmorMeshData> resolve(@NotNull Optional<String> name) {
        return name.flatMap(named -> Optional.ofNullable(this.byName.get(named))).or(() -> this.shared);
    }

}
