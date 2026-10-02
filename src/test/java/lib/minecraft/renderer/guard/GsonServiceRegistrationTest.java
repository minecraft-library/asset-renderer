package lib.minecraft.renderer.guard;

import com.google.gson.Gson;
import dev.simplified.gson.GsonContributor;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.content.json.RendererGsonContributor;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * The service file that registers the renderer's Gson adapters, held against the class it names.
 *
 * <p>Nothing else checks it. The file is plain text naming a class by its binary name, so a move or a
 * rename of the contributor compiles clean, and {@link java.util.ServiceLoader} answers a line it
 * cannot load by building the shared settings without the renderer's adapters - every asset JSON then
 * reads through Gson's reflective default, and nothing throws at build time or at load time.
 */
@DisplayName("The Gson service file registers the renderer's contributor")
class GsonServiceRegistrationTest {

    /** The service file, named by the contributor interface's own binary name as the loader looks it up. */
    private static final Path SERVICE_FILE =
        Path.of("src/main/resources/META-INF/services", GsonContributor.class.getName());

    @Test
    @DisplayName("the service file names the contributor that exists")
    void theServiceFileNamesTheContributorThatExists() throws IOException, ClassNotFoundException {
        String declared = Files.readString(SERVICE_FILE).strip();

        assertThat("the line the loader reads, against a class literal the compiler checks",
            declared, is(equalTo(RendererGsonContributor.class.getName())));
        assertThat("the name loads, rather than merely spelling the class",
            Class.forName(declared), is(equalTo(RendererGsonContributor.class)));
    }

    @Test
    @DisplayName("the shared settings read the renderer's value types through its adapters")
    void theSharedSettingsCarryTheAdapters() {
        // The two checks above pass with the file right and the registration list inside the
        // contributor wrong. Reading a value only an adapter can read is what fails for that reason:
        // a vector is a JSON array and an id a bare string, neither of which the reflective default
        // turns into a record.
        Gson gson = GsonSettings.defaults().create();

        assertThat(gson.fromJson("[1.0, 2.0, 3.0]", Vector3f.class), is(equalTo(new Vector3f(1f, 2f, 3f))));
        assertThat(gson.fromJson("\"minecraft:stone\"", ResourceId.class), is(equalTo(ResourceId.parse("minecraft:stone"))));
    }

}
