package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.TextureSize;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.engine.light.Lighting;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Pins {@link BlockGeometryKit#buildFromElements} triangle generation from resolved
 * {@link ModelElement} boxes: 12 triangles (2 per face) for a full cube with tint and texture
 * threaded through, per-direction skipping of absent or unresolvable faces, 0-16 &rarr; 0-1 UV
 * normalization, and the vanilla 0-16 &rarr; engine {@code [-0.5, +0.5]} bounds mapping. Face
 * fixtures set private {@link ModelFace} / {@link ModelElement} fields by reflection since those
 * types are parser-populated and expose no test constructor.
 */
class BlockGeometryKitTest {

    /** ARGB tint threaded through every build so assertions can confirm it lands on each triangle. */
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
            BlockGeometryKit.buildFromElements(one(element), faceTextures, TINT_ARGB);

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
            BlockGeometryKit.buildFromElements(one(element), faceTextures, TINT_ARGB);

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
            BlockGeometryKit.buildFromElements(one(element), faceTextures, TINT_ARGB);

        assertThat(triangles.size(), equalTo(2));
        VisibleTriangle firstHalf = triangles.getFirst();
        // The authored 0-16 rect [0, 0, 8, 8] normalizes to [0, 0, 0.5, 0.5] in 0-1 UV space.
        // Triangle 0 samples (TL, BL, BR): uv0 is TL (0, 0), uv2 is BR (0.5, 0.5).
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
            BlockGeometryKit.buildFromElements(one(element), faceTextures, TINT_ARGB);

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
            BlockGeometryKit.buildFromElements(one(element), faceTextures, TINT_ARGB);

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

    @Test
    @DisplayName("buildFromBones: full-block cube bone yields 12 triangles in [-0.5,0.5], all six faces, tinted")
    void buildFromBones_fullBlockCube() {
        // A cube spanning bone-local [-8,+8] under a bone pivot of (8,8,8) composes to entity-pixel
        // [0,16], which the /16 - 0.5 block-frame normalization maps to the [-0.5,+0.5] unit cube.
        ConcurrentList<VisibleTriangle> triangles =
            BlockGeometryKit.buildFromBones(fullBlockCubeModel(), solid(16, 16), TINT_ARGB);

        assertThat(triangles.size(), equalTo(12));
        Set<String> faces = new HashSet<>();
        for (VisibleTriangle t : triangles) {
            assertThat(t.tintArgb(), equalTo(TINT_ARGB));
            for (Vector3f p : new Vector3f[]{ t.position0(), t.position1(), t.position2() }) {
                assertThat(p.x(), both(greaterThanOrEqualTo(-0.501f)).and(lessThanOrEqualTo(0.501f)));
                assertThat(p.y(), both(greaterThanOrEqualTo(-0.501f)).and(lessThanOrEqualTo(0.501f)));
                assertThat(p.z(), both(greaterThanOrEqualTo(-0.501f)).and(lessThanOrEqualTo(0.501f)));
            }
            faces.add(cardinal(t.normal()));
        }
        assertThat("all six cardinal faces present", faces.size(), equalTo(6));
    }

    @Test
    @DisplayName("light_emission 15 (shade:true) bakes a full-bright 1.0 floor, NOT the black-rendering DISABLED sentinel")
    void lightEmission15_bakesFullBrightFloor() throws ReflectiveOperationException {
        ConcurrentList<VisibleTriangle> triangles = cubeWithEmission(true, 15);
        for (VisibleTriangle t : triangles) {
            assertThat("not the DISABLED sentinel (renders black on un-relit Held3D)", t.shading(), not(equalTo(Shading.DISABLED)));
            assertThat("full-bright floor", t.shading(), equalTo(Math.max(Lighting.inventory(t.normal()), 1f)));
        }
    }

    @Test
    @DisplayName("intermediate light_emission raises the shade floor to emission/15")
    void lightEmissionIntermediate_raisesShadeFloor() throws ReflectiveOperationException {
        // 12/15 = 0.8 exceeds the darker cardinal shades (down 0.5, sides 0.6/0.8) but not the top's
        // 1.0, so max(inventory, 0.8) is the assertion on every face.
        ConcurrentList<VisibleTriangle> triangles = cubeWithEmission(true, 12);
        for (VisibleTriangle t : triangles)
            assertThat(t.shading(), equalTo(Math.max(Lighting.inventory(t.normal()), 12f / 15f)));
    }

    @Test
    @DisplayName("light_emission 0 leaves the inventory cardinal shade untouched (byte-neutral baseline)")
    void lightEmissionZero_isBaseline() throws ReflectiveOperationException {
        ConcurrentList<VisibleTriangle> triangles = cubeWithEmission(true, 0);
        for (VisibleTriangle t : triangles)
            assertThat(t.shading(), equalTo(Lighting.inventory(t.normal())));
    }

    @Test
    @DisplayName("a shade:false element stays full-bright regardless of light_emission")
    void shadeFalse_staysDisabled() throws ReflectiveOperationException {
        ConcurrentList<VisibleTriangle> triangles = cubeWithEmission(false, 0);
        for (VisibleTriangle t : triangles)
            assertThat(t.shading(), equalTo(Shading.DISABLED));
    }

    /** Builds a full unit cube with a given {@code shade} flag and {@code light_emission} level. */
    private static ConcurrentList<VisibleTriangle> cubeWithEmission(boolean shade, int emission) throws ReflectiveOperationException {
        ModelElement element = new ModelElement();
        setField(element, "shade", shade);
        setField(element, "lightEmission", emission);
        for (String dir : new String[]{ "down", "up", "north", "south", "west", "east" })
            element.getFaces().put(dir, face("#all"));
        ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
        faceTextures.put("#all", texture1x1());
        return BlockGeometryKit.buildFromElements(one(element), faceTextures, TINT_ARGB);
    }

    // --- fixtures ---

    /** A single opaque-white texel, sufficient since these tests assert geometry, not sampling. */
    private static PixelBuffer texture1x1() {
        return PixelBuffer.of(new int[]{ 0xFFFFFFFF }, 1, 1);
    }

    /** An opaque-white {@code w x h} texture so cube-face UV sampling never drops texels. */
    private static PixelBuffer solid(int w, int h) {
        int[] px = new int[w * h];
        Arrays.fill(px, 0xFFFFFFFF);
        return PixelBuffer.of(px, w, h);
    }

    /**
     * A one-bone model whose single cube spans a full block: bone-local origin {@code [-8,-8,-8]},
     * size {@code [16,16,16]}, under bone pivot {@code (8,8,8)} so the composed cube fills the
     * {@code [0,16]} entity-pixel block, on a {@code 16x16} atlas.
     */
    private static EntityModelData fullBlockCubeModel() {
        EntityModelData.Cube cube = new EntityModelData.Cube(
            new Vector3f(-8f, -8f, -8f), new Vector3f(16f, 16f, 16f), Vector2f.ZERO,
            Vector3f.ZERO, false, Vector3f.ZERO, EulerRotation.NONE, Concurrent.newMap());
        ConcurrentList<EntityModelData.Cube> cubes = Concurrent.newList();
        cubes.add(cube);
        EntityModelData.Bone bone = new EntityModelData.Bone(
            new Vector3f(8f, 8f, 8f), EulerRotation.NONE, EulerRotation.NONE, 1f, cubes, null);
        ConcurrentLinkedMap<String, EntityModelData.Bone> bones = Concurrent.newLinkedMap();
        bones.put("body", bone);
        return new EntityModelData(new TextureSize(16, 16), 0f, bones, false);
    }

    /** Maps a (mostly-)axis-aligned normal to its cardinal-direction label. */
    private static String cardinal(Vector3f n) {
        float ax = Math.abs(n.x()), ay = Math.abs(n.y()), az = Math.abs(n.z());
        if (ax >= ay && ax >= az) return n.x() > 0 ? "+x" : "-x";
        if (ay >= az) return n.y() > 0 ? "+y" : "-y";
        return n.z() > 0 ? "+z" : "-z";
    }

    /** Builds a {@link ModelFace} bound to the given texture key with no explicit UV or rotation. */
    private static ModelFace face(String texture) {
        return face(texture, null, 0);
    }

    /**
     * Builds a {@link ModelFace} with an optional 0-16 UV rectangle and face rotation, setting the
     * parser-populated private fields by reflection. A {@code null} uv leaves the face to derive
     * its default UV from element bounds; a {@code 0} rotation is left unset.
     */
    private static ModelFace face(String texture, float[] uv, int rotation) {
        try {
            ModelFace face = new ModelFace();
            setField(face, "texture", texture);
            if (uv != null)
                setField(face, "uv", Optional.of(new Vector4f(uv[0], uv[1], uv[2], uv[3])));
            if (rotation != 0)
                setField(face, "rotation", rotation);
            return face;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to build test ModelFace", ex);
        }
    }

    /**
     * Builds a {@link ModelElement} with the given 0-16 {@code from}/{@code to} bounds, set by
     * reflection. Faces are added by the caller afterward.
     */
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

    /** Wraps a single element in the {@link ConcurrentList} the kit's build API expects. */
    private static ConcurrentList<ModelElement> one(ModelElement element) {
        ConcurrentList<ModelElement> list = Concurrent.newList();
        list.add(element);
        return list;
    }

    /** Sets a private declared field by reflection, used to populate parser-only model fields. */
    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

}
