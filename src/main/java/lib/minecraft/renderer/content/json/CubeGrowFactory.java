package lib.minecraft.renderer.content.json;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * A Gson factory binding an {@link EntityMesh.Cube}'s {@code grow} member through
 * {@link CubeGrowAdapter}, and every other member of the cube through the reflective binding.
 * <p>
 * A primitive {@code grow} is rewritten, on a copy of the cube's object, into the three-component
 * array {@link CubeGrowAdapter} reads it as, so the member then binds through the registered
 * {@link Vector3f} adapter. The copy is required: a tree reader hands out the live element, and the
 * document it belongs to is read again. An array or a {@code null} {@code grow} is left as authored,
 * and an absent one leaves the cube's {@link Vector3f#ZERO} default.
 * <p>
 * The cube binds through a tree reader at Gson's strict default rather than the lenient stream the
 * document is read through, so a {@code NaN} or infinite member of a cube - a scalar {@code grow}
 * included - is refused.
 * <p>
 * Writing mirrors the read: the cube's {@code grow} member is written through
 * {@link CubeGrowAdapter}, which spells a uniform grow as its scalar. Every type other than the cube
 * is answered with {@code null}, which is how a Gson factory declines one.
 */
@Parity(subject = Subject.ENGINE)
public final class CubeGrowFactory implements TypeAdapterFactory {

    /** The cube member this factory binds. */
    private static final @NotNull String GROW = "grow";

    /** {@inheritDoc} */
    @Override
    public <T> @Nullable TypeAdapter<T> create(@NotNull Gson gson, @NotNull TypeToken<T> type) {
        if (type.getRawType() != EntityMesh.Cube.class) return null;
        TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
        TypeAdapter<JsonElement> elements = gson.getAdapter(JsonElement.class);
        TypeAdapter<Vector3f> vectors = gson.getAdapter(Vector3f.class);
        CubeGrowAdapter grow = new CubeGrowAdapter();
        return new TypeAdapter<>() {

            /** {@inheritDoc} */
            @Override
            public void write(@NotNull JsonWriter out, @Nullable T value) throws IOException {
                JsonElement tree = delegate.toJsonTree(value);
                if (tree instanceof JsonObject cube && cube.has(GROW))
                    cube.add(GROW, grow.toJsonTree(((EntityMesh.Cube) value).getGrow()));
                elements.write(out, tree);
            }

            /** {@inheritDoc} */
            @Override
            public @Nullable T read(@NotNull JsonReader in) throws IOException {
                JsonElement tree = elements.read(in);
                if (tree instanceof JsonObject cube && cube.get(GROW) instanceof JsonPrimitive scalar) {
                    JsonObject copy = cube.deepCopy();
                    copy.add(GROW, vectors.toJsonTree(grow.fromJsonTree(scalar)));
                    tree = copy;
                }
                return delegate.fromJsonTree(tree);
            }

        };
    }

}
