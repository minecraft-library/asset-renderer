package lib.minecraft.renderer.tensor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@link Box}'s AABB factories, its extent accessor and the operand order a grown cube is formed in:
 * the direct min/max copies ({@link Box#of(Vector3f, Vector3f)}, {@link Box#of(float[], float[])}), the
 * tight-enclosing point-cloud factories ({@link Box#of(float[][])}, {@link Box#of(Vector3f[])}) that
 * reduce a set of points to their bounding box, {@link Box#maxExtent()} returning the largest per-axis
 * span, and {@link Box#grown(Vector3f, Vector3f, Vector3f)}, the one place a cube box is formed and the
 * one case here whose arithmetic is not re-associable.
 * <p>
 * It also pins the two composition primitives: {@link Box#union(Box)}, which the armour path folds each
 * equipped slot's screen bounds through, and {@link Box#expand(float)}, the one inflate every clearance
 * constant rides. Both are bare per-corner arithmetic with no well-formedness check, and the negative
 * case below records what that actually produces rather than assuming a clamp.
 */
@DisplayName("Box factories, extent, grown operand order, union and expand")
class BoxTest {

    @Test
    @DisplayName("of(Vector3f, Vector3f) copies the min/max corners")
    void ofVectors() {
        Box box = Box.of(new Vector3f(1, 2, 3), new Vector3f(4, 5, 6));
        assertThat(box, equalTo(new Box(1, 2, 3, 4, 5, 6)));
    }

    @Test
    @DisplayName("of(float[], float[]) reads [x,y,z] arrays")
    void ofArrays() {
        Box box = Box.of(new float[]{ 0, -1, 2 }, new float[]{ 3, 4, 5 });
        assertThat(box, equalTo(new Box(0, -1, 2, 3, 4, 5)));
    }

    @Test
    @DisplayName("of(points) computes the tight enclosing AABB from [x,y,z] rows")
    void ofPointRows() {
        Box box = Box.of(new float[][]{
            { 1, 1, 1 }, { -2, 3, 0 }, { 5, -4, 2 }
        });
        assertThat(box, equalTo(new Box(-2, -4, 0, 5, 3, 2)));
    }

    @Test
    @DisplayName("of(Vector3f[]) computes the tight enclosing AABB")
    void ofVectorPoints() {
        Box box = Box.of(new Vector3f[]{
            new Vector3f(1, 1, 1), new Vector3f(-2, 3, 0), new Vector3f(5, -4, 2)
        });
        assertThat(box, equalTo(new Box(-2, -4, 0, 5, 3, 2)));
    }

    @Test
    @DisplayName("maxExtent returns the largest per-axis span")
    void maxExtent() {
        assertThat(new Box(0, 0, 0, 2, 5, 1).maxExtent(), equalTo(5f));
        assertThat(new Box(-1, -1, -1, 1, 1, 1).maxExtent(), equalTo(2f));
    }

    @Test
    @DisplayName("grown forms each corner in vanilla's operand order, which is not re-associable")
    void grownKeepsVanillaOperandOrder() {
        // Vanilla's ModelPart$Cube computes each upper corner as (origin + size) + grow from the
        // UN-grown origin, and each lower corner as origin - grow. These operands are a real armour
        // magnitude: the head cube's own -4 origin, a one-unit axis, and the piglin baby shell's
        // uniform 0.7 growth.
        float origin = -4f;
        float size = 1f;
        float grow = 0.7f;

        Box box = Box.grown(
            new Vector3f(origin, origin, origin),
            new Vector3f(size, size, size),
            new Vector3f(grow, grow, grow));

        assertThat(box.maxX(), equalTo((origin + size) + grow));
        assertThat(box.minX(), equalTo(origin - grow));

        // The pin has teeth: growing the lower corner first and adding the growth back twice is
        // algebraically the same corner and one ULP away in binary32, so a re-association here would
        // be silent without this line.
        float reassociated = ((origin - grow) + size) + grow + grow;
        assertThat(Float.floatToRawIntBits(box.maxX()), equalTo(0xC0133333));
        assertThat(Float.floatToRawIntBits(reassociated), equalTo(0xC0133332));
    }

    @Test
    @DisplayName("union of two disjoint boxes spans both, in either order")
    void unionOfDisjointBoxes() {
        Box left = new Box(0, 0, 0, 1, 1, 1);
        Box right = new Box(5, 4, 3, 6, 6, 6);

        assertThat(left.union(right), equalTo(new Box(0, 0, 0, 6, 6, 6)));
        assertThat(right.union(left), equalTo(new Box(0, 0, 0, 6, 6, 6)));
    }

    @Test
    @DisplayName("union with a fully contained box answers the container, in either order")
    void unionOfContainedBox() {
        Box outer = new Box(-8, -8, -8, 8, 8, 8);
        Box inner = new Box(-1, 0, 2, 3, 4, 5);

        assertThat(outer.union(inner), equalTo(outer));
        assertThat(inner.union(outer), equalTo(outer));
    }

    @Test
    @DisplayName("union with itself is the identity")
    void unionWithSelfIsIdentity() {
        Box box = new Box(-3.5f, 0, 1.25f, 4, 12, 16);
        assertThat(box.union(box), equalTo(box));
    }

    @Test
    @DisplayName("expand moves each minimum down and each maximum up, adding twice the amount to every span")
    void expandGrowsEverySideByTheSameAmount() {
        Box box = new Box(0, 0, 0, 2, 5, 1);
        Box grown = box.expand(0.5f);

        assertThat(grown, equalTo(new Box(-0.5f, -0.5f, -0.5f, 2.5f, 5.5f, 1.5f)));
        assertThat(grown.maxExtent(), equalTo(box.maxExtent() + 1f));
    }

    @Test
    @DisplayName("expand by zero returns an equal box")
    void expandByZeroIsIdentity() {
        Box box = new Box(1, 2, 3, 4, 5, 6);
        assertThat(box.expand(0f), equalTo(box));
    }

    @Test
    @DisplayName("expand by a negative amount shrinks and is not clamped - past the half-span the corners invert")
    void expandByANegativeAmountShrinksAndIsNotClamped() {
        Box box = new Box(0, 0, 0, 4, 4, 4);
        assertThat(box.expand(-1f), equalTo(new Box(1, 1, 1, 3, 3, 3)));

        // Observed, not assumed: expand is six independent adds with nothing asserting min <= max,
        // so shrinking by more than half the span leaves every minimum ABOVE its own maximum rather
        // than collapsing the box to a point. maxExtent then reports a negative span.
        Box inverted = box.expand(-3f);
        assertThat(inverted, equalTo(new Box(3, 3, 3, 1, 1, 1)));
        assertThat(inverted.maxExtent(), equalTo(-2f));
    }

}
