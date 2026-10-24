# Declaring reach

Load when writing or moving a `@Parity` declaration, editing a rule's `authored_paths`, or cutting a
seam with `ignored`. Indexed from [`parity/CLAUDE.md`](../../parity/CLAUDE.md).

**A rule's trigger list is two halves and only one of them is authored.** A rule carrying a
`claim_key` derives its triggers from the `@Parity` declarations naming that slug, and `trigger_paths`
is the sorted union of that with `authored_paths` - so moving a claiming package moves the rule, and
hand-editing a generated array states a reach no declaration makes. `python parity/scripts/parity
triggers` regenerates; `--check` answers what would move. The authored half is what no annotation can
reach: a `.kts`, a `.py`, a resource file, a path in a build with no Java at all.

A declaration is read from **source, never bytecode** - retention is `SOURCE`, and javac still writes
a synthetic `package-info.class` for an annotated package with the annotation dropped, so a bytecode
reader would find zero annotations and conclude the package declares nothing. Five source roots are
scanned: the renderer's, this build's, both of `tooling`'s and the harness's client root. Client
acquisition is under the renderer's, so its claim derives a trigger there like any other package.
**`Scope.PACKAGE` is legal on the renderer's library root alone** and refused everywhere else - a leaf
package answers for its tree, so a package added below one inherits what its parent claims.

## Cutting a seam

**A wiring seam is cut by what it is.** `@Parity(ignored = true)` stops reach composing THROUGH a
type, and an INTERFACE is cut by its declaration alone - its members' descriptors name every type
they mention whether or not anything calls them, which is the collapse - while what its DEFAULT
BODIES call is kept, those having no implementor to carry a change. A CLASS is cut whole: every
reference it holds is one it makes. Measured - cutting the concrete assembled context by declaration
instead takes the tree from 29 engine-wide types to 151.

**A library type that reaches nothing declares what it reaches.** Two different things answer the
empty set - a renderer this store holds no artifact for, and a type reached across a seam or built by
a service loader out of a file no constant pool mentions - and `reach check` refuses one that says
neither. Each such type carries `@Parity(subject = {...})` with no claim, which is its own
declaration shape: a subject written beside a claim decorates that claim, so only the claimless form
answers for a type, and reading a claim's decoration as one would explain an orphan nobody had looked
at.
