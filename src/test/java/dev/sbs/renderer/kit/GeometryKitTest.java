package dev.sbs.renderer.kit;

import dev.sbs.renderer.geometry.VisibleTriangle;
import dev.sbs.renderer.asset.model.ModelElement;
import dev.sbs.renderer.asset.model.ModelFace;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

class GeometryKitTest {

    private static final int TINT_ARGB = 0xFFAABBCC;

    @Test
    @DisplayName("full unit cube element produces 12 triangles with correct tint and texture")
    void unitCubeElement_produces12Triangles() {
        ModelElement element = new ModelElement();
        element.getFaces().put("down", face("#all"));
        element.getFaces().put("up", face("#all"));
        element.getFaces().put("north", face("#all"));
        element.getFaces().put("south", face("#all"));
        element.getFaces().put("west", face("#all"));
        element.getFaces().put("east", face("#all"));

        PixelBuffer texture = texture1x1();
        ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
        faceTextures.put("#all", texture);

        ConcurrentList<VisibleTriangle> triangles =
            GeometryKit.buildFromElements(one(element), faceTextures, TINT_ARGB);

        assertThat(triangles.size(), equalTo(12));
        for (VisibleTriangle triangle : triangles) {
            assertThat(triangle.texture(), sameInstance(texture));
            assertThat(triangle.tintArgb(), equalTo(TINT_ARGB));
        }
    }

    @Test
    @DisplayName("partial faces produce only the triangles for present directions")
    void partialFaces_skipsMissingDirections() {
        ModelElement element = new ModelElement();
        element.getFaces().put("up", face("#all"));
        element.getFaces().put("down", face("#all"));

        ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
        faceTextures.put("#all", texture1x1());

        ConcurrentList<VisibleTriangle> triangles =
            GeometryKit.buildFromElements(one(element), faceTextures, TINT_ARGB);

        assertThat(triangles.size(), equalTo(4));
    }

    @Test
    @DisplayName("explicit face UV rectangle is normalized from 0-16 to 0-1")
    void customFaceUv_normalizesCorrectly() {
        ModelElement element = new ModelElement();
        element.getFaces().put("up", face("#all", new float[]{ 0f, 0f, 8f, 8f }, 0));

        ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
        faceTextures.put("#all", texture1x1());

        ConcurrentList<VisibleTriangle> triangles =
            GeometryKit.buildFromElements(one(element), faceTextures, TINT_ARGB);

        assertThat(triangles.size(), equalTo(2));
        VisibleTriangle firstHalf = triangles.getFirst();
        // Triangle 0 samples (TL, BL, BR): uv0 is TL, uv2 is BR.
        assertThat(firstHalf.uv0().x(), equalTo(0f));
        assertThat(firstHalf.uv0().y(), equalTo(0f));
        assertThat(firstHalf.uv2().x(), equalTo(0.5f));
        assertThat(firstHalf.uv2().y(), equalTo(0.5f));
    }

    @Test
    @DisplayName("missing face texture in the map causes that face to be silently skipped")
    void missingFaceTexture_skipsFaceSilently() {
        ModelElement element = new ModelElement();
        element.getFaces().put("up", face("#all"));
        element.getFaces().put("down", face("#unknown"));

        ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
        faceTextures.put("#all", texture1x1());

        ConcurrentList<VisibleTriangle> triangles =
            GeometryKit.buildFromElements(one(element), faceTextures, TINT_ARGB);

        assertThat(triangles.size(), equalTo(2));
    }

    @Test
    @DisplayName("smaller element bounds map into the normalized -0.5 to 0.5 cube space")
    void smallerElement_mapsToNormalizedBounds() {
        ModelElement element = element(new float[]{ 4f, 0f, 4f }, new float[]{ 12f, 16f, 12f });
        element.getFaces().put("up", face("#all"));

        ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
        faceTextures.put("#all", texture1x1());

        ConcurrentList<VisibleTriangle> triangles =
            GeometryKit.buildFromElements(one(element), faceTextures, TINT_ARGB);

        assertThat(triangles.size(), equalTo(2));
        VisibleTriangle firstHalf = triangles.getFirst();
        // Up-face TL = vertex 3 = (x0, y1, z0) and BR = vertex 6 = (x1, y1, z1).
        assertThat(firstHalf.position0().x(), equalTo(-0.25f));
        assertThat(firstHalf.position0().y(), equalTo(0.5f));
        assertThat(firstHalf.position0().z(), equalTo(-0.25f));
        assertThat(firstHalf.position2().x(), equalTo(0.25f));
        assertThat(firstHalf.position2().y(), equalTo(0.5f));
        assertThat(firstHalf.position2().z(), equalTo(0.25f));
    }

    // --- fixtures ---

    private static PixelBuffer texture1x1() {
        return PixelBuffer.of(new int[]{ 0xFFFFFFFF }, 1, 1);
    }

    private static ModelFace face(String texture) {
        return face(texture, null, 0);
    }

    private static ModelFace face(String texture, float[] uv, int rotation) {
        try {
            ModelFace face = new ModelFace();
            setField(face, "texture", texture);
            if (uv != null)
                setField(face, "uv", Optional.of(uv));
            if (rotation != 0)
                setField(face, "rotation", rotation);
            return face;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to build test ModelFace", ex);
        }
    }

    private static ModelElement element(float[] from, float[] to) {
        try {
            ModelElement element = new ModelElement();
            setField(element, "from", from);
            setField(element, "to", to);
            return element;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to build test ModelElement", ex);
        }
    }

    private static ConcurrentList<ModelElement> one(ModelElement element) {
        ConcurrentList<ModelElement> list = Concurrent.newList();
        list.add(element);
        return list;
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

}
