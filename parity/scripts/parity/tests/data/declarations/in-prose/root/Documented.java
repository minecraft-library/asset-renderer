package root;

/**
 * A type whose javadoc quotes {@code @Parity} without declaring one, which is what the first
 * person to document the annotation will write.
 */
public final class Documented {

    /** The token as a value, which is the other place the lexer has to not count one. */
    static final String TOKEN = "@Parity(claim = \"a-claim\")";
}
