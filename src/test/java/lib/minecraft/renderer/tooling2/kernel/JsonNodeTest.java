package lib.minecraft.renderer.tooling2.kernel;

import com.google.gson.Gson;
import com.google.gson.JsonPrimitive;
import dev.simplified.gson.GsonSettings;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins for the tooling2 {@link JsonNode} contracts (SPINE 5.3): insertion-order preservation,
 * the Float-vs-Double serialisation difference motivating the float-only rule (a permanent
 * dependency-bump tripwire - doc-12 K7), the envelope shape, and the {@code writeResource}
 * newline contract.
 */
@DisplayName("tooling2 JsonNode build/read/io contracts")
class JsonNodeTest {

    private static final @NotNull Gson COMPACT = GsonSettings.defaults().create();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("member order is insertion order - the byte-stability contract")
    void insertionOrder() {
        JsonNode node = JsonNode.object()
            .put("zebra", "z")
            .putInt("alpha", 1)
            .put("mid", true)
            .put("nested", JsonNode.object().put("b", "1").put("a", "2"));
        assertEquals("{\"zebra\":\"z\",\"alpha\":1,\"mid\":true,\"nested\":{\"b\":\"1\",\"a\":\"2\"}}",
            COMPACT.toJson(node.toGson()));
    }

    @Test
    @DisplayName("Gson canary: Float and Double serialise DIFFERENTLY - the float-only motivation")
    void floatVsDoubleCanary() {
        // The executable fact behind the float-only rule (doc-12 K7: permanent tripwire). A
        // Gson major bump changing Number.toString routing surfaces HERE, not as a mystery
        // BridgeParityTest failure.
        assertEquals("0.1", COMPACT.toJson(new JsonPrimitive(0.1f)));
        assertEquals("0.10000000149011612", COMPACT.toJson(new JsonPrimitive((double) 0.1f)));
        assertEquals("0.001", COMPACT.toJson(new JsonPrimitive(0.001f)));
        // and the JsonNode float channel routes through the Float path
        assertEquals("{\"v\":0.1}", COMPACT.toJson(JsonNode.object().put("v", 0.1f).toGson()));
    }

    @Test
    @DisplayName("conditional puts follow the empty-vs-absent rule; putUnless omits at default")
    void conditionalPuts() {
        JsonNode node = JsonNode.object()
            .putIf("absent", (String) null)
            .putIf("present", "x")
            .putIf("absentNode", (JsonNode) null)
            .putUnless("atDefault", 1.0f, 1.0f)
            .putUnless("offDefault", 0.5f, 1.0f)
            .putHex("color", 0xFFE6E6E6);
        assertEquals("{\"present\":\"x\",\"offDefault\":0.5,\"color\":\"0xFFE6E6E6\"}",
            COMPACT.toJson(node.toGson()));
    }

    @Test
    @DisplayName("child/childArray open-or-create; arrays append in order")
    void childrenAndArrays() {
        JsonNode root = JsonNode.object();
        root.child("families").put("a", "1");
        root.child("families").put("b", "2");                 // reuses, never clobbers
        root.childArray("list").add("x").add(2.5f).add(JsonNode.object().putInt("i", 3));
        assertEquals("{\"families\":{\"a\":\"1\",\"b\":\"2\"},\"list\":[\"x\",2.5,{\"i\":3}]}",
            COMPACT.toJson(root.toGson()));
        assertEquals("{\"f\":[1.0,2.5],\"i\":[1,2],\"s\":[\"a\",\"b\"]}",
            COMPACT.toJson(JsonNode.object()
                .putFloats("f", 1.0f, 2.5f).putInts("i", 1, 2).putStrings("s", "a", "b").toGson()));
    }

    @Test
    @DisplayName("read side is null-safe with defaults; members walk in order")
    void readSide() {
        JsonNode node = JsonNode.parse("{\"s\":\"v\",\"f\":1.5,\"i\":7,\"b\":true,\"o\":{\"k\":\"w\"},\"a\":[\"x\",\"y\"]}"
            .getBytes(StandardCharsets.UTF_8));
        assertEquals("v", node.getString("s"));
        assertNull(node.getString("missing"));
        assertEquals("dflt", node.getString("missing", "dflt"));
        assertEquals(1.5f, node.getFloat("f", 0f));
        assertEquals(0.25f, node.getFloat("missing", 0.25f));
        assertEquals(7, node.getInt("i", 0));
        assertTrue(node.getBool("b", false));
        assertNull(node.get("missing"));
        assertEquals("w", node.get("o").getString("k"));
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, JsonNode> member : node.members()) keys.add(member.getKey());
        assertEquals(List.of("s", "f", "i", "b", "o", "a"), keys);
        List<String> values = new ArrayList<>();
        for (JsonNode element : node.get("a").elements()) values.add(element.toGson().getAsString());
        assertEquals(List.of("x", "y"), values);
    }

    @Test
    @DisplayName("at() bounds-checks arrays; floatValue() defaults on any non-number")
    void arrayIndexAndFloatValue() {
        JsonNode node = JsonNode.parse("{\"a\":[30,45.5,\"roll\"],\"s\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        JsonNode array = node.get("a");
        assertEquals(30f, array.at(0).floatValue(0f));
        assertEquals(45.5f, array.at(1).floatValue(0f));
        assertEquals(9f, array.at(2).floatValue(9f));          // string primitive -> default, never a parse throw
        assertNull(array.at(3));
        assertNull(array.at(-1));
        assertNull(node.get("s").at(0));                       // non-array -> null
        assertEquals(9f, node.get("s").floatValue(9f));
        assertEquals(9f, node.get("a").floatValue(9f));        // non-primitive -> default
    }

    @Test
    @DisplayName("writeResource emits pretty JSON terminated by exactly one platform newline")
    void writeResourceNewlineContract() throws IOException {
        Path out = tempDir.resolve("nested/out.json");
        JsonNode.object().put("k", "v").writeResource(out, Diagnostics.root("test", Diagnostics.Output.NONE, null));
        String written = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(written.endsWith(System.lineSeparator()), "platform newline at EOF");
        assertTrue(written.contains("\"k\": \"v\""), "pretty-printed");
        assertEquals(written.length() - System.lineSeparator().length(),
            written.lastIndexOf(System.lineSeparator()), "exactly one trailing newline");
    }

}
