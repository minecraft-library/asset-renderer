package lib.minecraft.renderer.parity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One declaration that a package or a type takes part in a named parity claim - the statement of
 * which captured values a change to it can move and which it leaves standing.
 * <p>
 * A declaration on a package is carried by every compilation unit in that package, and by every
 * package below it as far as its {@link Scope} reaches. A declaration on a type adds a claim to that
 * one file beside whatever its packages already carry, so a type reaching less than its neighbours
 * says so with {@link Mode#DEMOTE} rather than by declaring a smaller set.
 * <p>
 * A declaration names its claim and states how this file stands in it. What the claim asserts, what
 * measured it and which stored values it reaches are the claim's own and are held once, so two
 * declarations of one claim cannot disagree about the answer.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.PACKAGE, ElementType.TYPE})
@Repeatable(Parity.Claims.class)
public @interface Parity {

    /**
     * Slug of the claim this declaration names, lower case and hyphenated. Empty on a declaration
     * that reaches its claim through {@link #as}.
     */
    String claim() default "";

    /**
     * Type whose own declaration names the claim this one joins. {@code Parity} itself on a
     * declaration that names its claim in {@link #claim}.
     */
    Class<?> as() default Parity.class;

    /** How the claim resolves against everything else reaching the same path. */
    Mode mode() default Mode.SELECT;

    /** How far below a package the claim reaches, read on a package declaration alone. */
    Scope scope() default Scope.SUBTREE;

    /**
     * Renderers this reaches, empty where no single one owns it.
     * <p>
     * Beside a claim it says which renderers that claim is about. Alone on a TYPE it is the whole
     * declaration and says what the type reaches, written where the reference graph cannot derive
     * it: a type reached only across a seam, or one a service file registers, is reachable from no
     * producer root and answers nothing at all - as does a renderer this store holds no artifact
     * for. Those two look identical from outside and only one is correct, so {@code reach check}
     * refuses a library type that reaches nothing and says nothing.
     */
    Subject[] subject() default {};

    /**
     * Whether this type is wiring rather than behaviour, so reach does not compose THROUGH it.
     * <p>
     * A context interface declares what its implementors can be asked for, and a context class
     * constructs what the pipeline might need. Neither is evidence that a caller asks. Read as a
     * graph that makes them the seam every subject reaches every other across: a menu sweep reaches
     * the whole entity surface because {@link Subject#ENTITY the entity lookup} is DECLARED on the
     * context it holds, and every producer reaches every loader because one class constructs them
     * all. Type-level reach cannot tell a declared capability from an exercised one, and this is
     * where that is said.
     * <p>
     * What makes it safe rather than a hole is that an abstract member cannot change alone: every
     * implementor is forced to move with it, and an implementor is a concrete class whose own reach
     * is exact, in the same commit. It cuts OUTGOING edges only, so a change to the type itself is
     * still seen by everything that reaches it - which is what keeps a defaulted member honest,
     * those being the members no implementor is forced to carry.
     * <p>
     * It is a flag rather than a subject because it answers a different question. A subject says
     * which pipelines a type reaches; this says not to ask. Spelling it as one more constant would
     * put two questions in one member and leave a pair like {@code {ENTITY, IGNORED}} meaning
     * nothing.
     */
    boolean ignored() default false;

    /**
     * The holder a package or a type carries more than one claim in.
     */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.PACKAGE, ElementType.TYPE})
    @interface Claims {

        /** The declarations, in the order they are written. */
        Parity[] value();
    }

}
