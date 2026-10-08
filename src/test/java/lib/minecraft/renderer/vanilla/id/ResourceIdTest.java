package lib.minecraft.renderer.vanilla.id;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of the {@link ResourceId} factories: {@link ResourceId#parse(String)} and
 * {@link ResourceId#ofModelId(String)} each read a bare id, and one whose namespace is empty, in
 * {@code minecraft:} as vanilla's identifier parse does. Every leading {@code :} is trimmed before the
 * namespace is read, so a run of them reads as one does, where vanilla refuses the {@code :} it leaves
 * in the path. {@link ResourceId#vanillaPath(String)} reads through the same parse and answers a name
 * only in the default namespace.
 */
@DisplayName("ResourceId parsing")
class ResourceIdTest {

    @Test
    @DisplayName("parse splits a namespaced id and reads a bare one in the default namespace")
    void parseNamespacedAndBare() {
        assertThat(ResourceId.parse("minecraft:block/stone"), is(equalTo(new ResourceId("minecraft", "block/stone"))));
        assertThat(ResourceId.parse("hplus:skyblock/x"), is(equalTo(new ResourceId("hplus", "skyblock/x"))));
        assertThat(ResourceId.parse("block/stone"), is(equalTo(new ResourceId("minecraft", "block/stone"))));
    }

    @Test
    @DisplayName("parse trims every leading colon, so an empty namespace reads as minecraft:")
    void parseTrimsLeadingColons() {
        assertThat(ResourceId.parse(":block/x"), is(equalTo(new ResourceId("minecraft", "block/x"))));
        assertThat(ResourceId.parse(":block/x").id(), is("minecraft:block/x"));
        assertThat("every leading colon goes, not only the first",
            ResourceId.parse("::block/x"), is(equalTo(new ResourceId("minecraft", "block/x"))));
        assertThat("a colon after the namespace is the separator, never trimmed",
            ResourceId.parse("hplus::x"), is(equalTo(new ResourceId("hplus", ":x"))));
    }

    @Test
    @DisplayName("parse reads the empty id, and a lone colon, as the empty name in the default namespace")
    void parseEmpty() {
        assertThat(ResourceId.parse(""), is(equalTo(new ResourceId("minecraft", ""))));
        assertThat(ResourceId.parse(":"), is(equalTo(new ResourceId("minecraft", ""))));
    }

    @Test
    @DisplayName("ofModelId keeps the namespace and the trailing path segment")
    void ofModelIdNamespacedAndBare() {
        assertThat(ResourceId.ofModelId("minecraft:block/grass_block"), is(equalTo(new ResourceId("minecraft", "grass_block"))));
        assertThat(ResourceId.ofModelId("testns:item/gadget"), is(equalTo(new ResourceId("testns", "gadget"))));
        assertThat(ResourceId.ofModelId("block/grass_block"), is(equalTo(new ResourceId("minecraft", "grass_block"))));
    }

    @Test
    @DisplayName("ofModelId trims every leading colon before reading the namespace")
    void ofModelIdTrimsLeadingColons() {
        assertThat(ResourceId.ofModelId(":block/grass_block"), is(equalTo(new ResourceId("minecraft", "grass_block"))));
        assertThat(ResourceId.ofModelId(":block/grass_block").id(), is("minecraft:grass_block"));
        assertThat(ResourceId.ofModelId("::block/grass_block"), is(equalTo(new ResourceId("minecraft", "grass_block"))));
        assertThat("a name with no path segment keeps its whole name",
            ResourceId.ofModelId(":grass_block"), is(equalTo(new ResourceId("minecraft", "grass_block"))));
    }

    @Test
    @DisplayName("vanillaPath answers the name of a bare, empty-namespace or minecraft: id, and nothing for another namespace")
    void vanillaPathIsNamespaceExact() {
        assertThat(ResourceId.vanillaPath("using_item"), is(Optional.of("using_item")));
        assertThat(ResourceId.vanillaPath(":using_item"), is(Optional.of("using_item")));
        assertThat(ResourceId.vanillaPath("minecraft:using_item"), is(Optional.of("using_item")));
        assertThat(ResourceId.vanillaPath("hplus:using_item"), is(Optional.empty()));
        assertThat("a second namespace stays in the name",
            ResourceId.vanillaPath("minecraft:minecraft:time"), is(Optional.of("minecraft:time")));
    }

}
