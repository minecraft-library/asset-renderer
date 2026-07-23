package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryParser;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.vanilla.ArmorMeshIndex;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The worn-armor pipeline: parses each armor mesh the index resolved and emits the paired armor
 * file - the meshes themselves keyed by their factory coordinate, plus the wearer-facing name each
 * renderer holds them under.
 *
 * <p>A mesh is parsed twice, once at each of its set's two deformations, because that is what
 * vanilla builds: the outer grow dresses helmet, chestplate and boots, the inner one dresses
 * leggings, and a part that carries its own {@code CubeDeformation.extend} (the shared mesh's legs)
 * differs between the two by more than a constant. Both trees arrive already grown, so the kit
 * reads their boxes without further arithmetic.
 *
 * <p>Slot pruning stays with the kit rather than travelling here. Vanilla prunes by
 * {@code HumanoidModel.ADULT_ARMOR_PARTS_PER_SLOT}, and the same four part sets pick the bones out
 * of these meshes - splitting each mesh four ways on disk would emit the same boxes four times over.
 */
public final class EntityArmorFlow {

    /** The atlas every armor mesh is baked against, from the {@code LayerDefinition.create} wrap. */
    private static final int ARMOR_TEXTURE_WIDTH = 64;

    /** The armor atlas height - half the entity default, matching the player skin's base layer. */
    private static final int ARMOR_TEXTURE_HEIGHT = 32;

    private EntityArmorFlow() {
    }

    /**
     * Parses every indexed armor mesh and writes the armor file.
     *
     * @param session the live session
     * @param index the armor-set index built from {@code LayerDefinitions.createRoots}
     * @param out the output path ({@code entity_armor.json})
     */
    public static void emit(
        @NotNull ToolingSession session, @NotNull ArmorMeshIndex index, @NotNull Path out) {
        Diagnostics diagnostics = session.diagnostics().child("armor");
        JsonTree root = session.envelope("LayerDefinitions.createRoots armor-set registration order");
        JsonTree meshes = root.child("meshes");
        JsonTree sets = root.child("sets");

        Set<String> emitted = new LinkedHashSet<>();
        ArmorMeshIndex.Set shared = index.shared();
        if (shared != null) {
            emitMesh(session, meshes, shared, emitted, diagnostics);
            root.put("shared", shared.key());
        }
        for (Map.Entry<String, ArmorMeshIndex.Set> entry : index.sets().entrySet()) {
            ArmorMeshIndex.Set set = entry.getValue();
            emitMesh(session, meshes, set, emitted, diagnostics);
            sets.put(entry.getKey(), set.key());
        }

        root.write(out);
        diagnostics.info("wrote %s", out.toAbsolutePath());
    }

    /**
     * Parses one armor mesh at both of its deformations and appends it under its key, unless an
     * earlier set already emitted the same mesh. A failed parse is a Diagnostics ERROR and a
     * skipped entry - never a fallback mesh.
     */
    private static void emitMesh(
        @NotNull ToolingSession session,
        @NotNull JsonTree meshes,
        @NotNull ArmorMeshIndex.Set set,
        @NotNull Set<String> emitted,
        @NotNull Diagnostics diagnostics
    ) {
        String key = set.key();
        if (!emitted.add(key)) return;

        Diagnostics scope = diagnostics.child(key);
        JsonTree inner = parseAt(session, set, set.innerGrow(), key, scope);
        JsonTree outer = parseAt(session, set, set.outerGrow(), key, scope);
        if (inner == null || outer == null) {
            scope.error("armor mesh '%s' did not parse at both deformations - entry skipped", key);
            return;
        }

        JsonTree node = JsonTree.object()
            .put("source", JsonTree.object()
                .put("class", set.meshClass())
                .put("method", set.meshMethod()))
            .put("inner", inner)
            .put("outer", outer);
        meshes.put(key, node);
    }

    /** One mesh tree at one deformation: the atlas the armor unwrap is authored on, plus the bones. */
    private static JsonTree parseAt(
        @NotNull ToolingSession session,
        @NotNull ArmorMeshIndex.Set set,
        float @NotNull [] grow,
        @NotNull String subjectId,
        @NotNull Diagnostics scope
    ) {
        GeometryRequest request = GeometryRequest.overlay(set.meshClass(), set.meshMethod(), subjectId,
            ARMOR_TEXTURE_WIDTH, ARMOR_TEXTURE_HEIGHT, grow);
        JsonTree parsed = GeometryParser.parse(session.cache(), request, scope);
        if (parsed == null) return null;                        // ERROR already recorded by the parser
        JsonTree node = JsonTree.object();
        node.putInts("texture_size", ARMOR_TEXTURE_WIDTH, ARMOR_TEXTURE_HEIGHT);
        node.putIf("bones", parsed.find("bones"));
        return node;
    }

}
