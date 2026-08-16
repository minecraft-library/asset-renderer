/**
 * The vocabulary a package or a type declares its own parity reach in - the annotation, the
 * resolution mode, the depth a package declaration reaches, and the renderer roster a claim names.
 *
 * <p><b>Parity.</b> Nothing here is read at run time. The declarations are source retained, so no
 * compiled artifact carries one and no renderer classpath is widened by them; what a declaration
 * states is resolved from source by the gate that plans a change.
 *
 * @see lib.minecraft.renderer.parity.Parity
 * @see lib.minecraft.renderer.parity.Subject
 */
@Parity(claim = "parity-vocabulary")
package lib.minecraft.renderer.parity;
