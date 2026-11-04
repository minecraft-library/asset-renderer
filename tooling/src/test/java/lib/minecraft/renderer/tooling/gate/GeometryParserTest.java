package lib.minecraft.renderer.tooling.gate;

import com.google.gson.JsonObject;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.geometry.GeometryParser;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Where the tooling {@link GeometryParser} hangs a bone a factory adds off a part's return value: a
 * chained return keeps the bone under the part that returned it, and a stored return leaves the next
 * bone the factory adds to the mesh root at the root.
 *
 * <p>The parse runs against the cached client jar, ASM-opened here, and assumes away where
 * nothing has cached one.
 */
@DisplayName("tooling GeometryParser bone parents across chained and stored returns")
class GeometryParserTest {

    private static ClassNodeCache cache;

    @BeforeAll
    static void open() {
        // Gated rather than acquired: this suite is the fast one, so a jar nothing has cached yet
        // abandons the class instead of opening a socket. ClientJarGuardTest is what says so loudly.
        Path jar = ClientOptions.defaults().vanillaRoot().resolve("client.jar");
        assumeTrue(Files.isRegularFile(jar), () -> "no cached client jar at '" + jar
            + "' - run './gradlew generateTables' or any parity capture to cache one");
        cache = ClassNodeCache.open(jar);
    }

    @AfterAll
    static void close() {
        cache.close();
    }

    @Test
    @DisplayName("chained return: SkeletonModel#createSingleModelDualBodyLayer hangs its hat from the head")
    void parchedHatHangsFromTheHead() {
        // the factory adds "hat" straight onto the PartDefinition the head's addOrReplaceChild
        // returned, never storing it - HumanoidModel binds hat as head.getChild("hat"), so the
        // hat exists only under the head. The factory declares its own 64x64.
        GeometryRequest request = GeometryRequest.body(
            "net/minecraft/client/model/monster/skeleton/SkeletonModel", "createSingleModelDualBodyLayer",
            "minecraft:parched", null, null, null, 1f);
        JsonTree parsedNode = GeometryParser.parse(cache, request,
            Diagnostics.root("geometryParserTest", Diagnostics.Output.NONE, null));
        assertNotNull(parsedNode, request.subjectId() + " parse returned null");
        JsonObject hat = parsedNode.toGson().getAsJsonObject().getAsJsonObject("bones").getAsJsonObject("hat");
        assertNotNull(hat, "the factory declares a hat");
        assertEquals("head", optString(hat, "parent"), "hat.parent");
    }

    @Test
    @DisplayName("stored return: BatModel#createBodyLayer stores its body, so the head it adds to the root stays at the root")
    void batHeadStaysAtTheRoot() {
        // the factory stores the body's PartDefinition in a local, then loads the mesh root - a
        // slot bound to no bone - to add the head, so the store is the only thing that ends the
        // body's return before the head's create()
        GeometryRequest request = GeometryRequest.body(
            "net/minecraft/client/model/ambient/BatModel", "createBodyLayer",
            "minecraft:bat", null, null, null, 1f);
        JsonTree parsedNode = GeometryParser.parse(cache, request,
            Diagnostics.root("geometryParserTest", Diagnostics.Output.NONE, null));
        assertNotNull(parsedNode, request.subjectId() + " parse returned null");
        JsonObject head = parsedNode.toGson().getAsJsonObject().getAsJsonObject("bones").getAsJsonObject("head");
        assertNotNull(head, "the factory declares a head");
        assertNull(optString(head, "parent"), "head.parent");
    }

    private static @Nullable String optString(@NotNull JsonObject node, @NotNull String key) {
        return node.has(key) ? node.get(key).getAsString() : null;
    }

}
