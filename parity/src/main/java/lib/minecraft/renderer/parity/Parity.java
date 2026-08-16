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

    /** Renderers the claim is about, empty where no single one owns it. */
    Subject[] subject() default {};

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
