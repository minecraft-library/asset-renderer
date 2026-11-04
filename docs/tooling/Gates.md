# Tooling gates

Load when a pin that holds a table to the jar fails after a version bump. The suite's shape, and
diffing a flow's bytes member by member, are in [`tooling/CLAUDE.md`](../../tooling/CLAUDE.md).

**Two of the eight jar walks [tooling/CLAUDE.md](../../tooling/CLAUDE.md#gates) lists are the only pins holding the geometry table to the jar.**
`PosePartIndexTest` and `PoseWalkTest` hold class rosters the table's coordinates feed. Both run in
`test`, which `check` and the renderer's `check` schedule, rather than in a suite keyed on a tag
nothing schedules, where a rename compiles clean and fails at runtime with nothing to say so.
`GeometryParserTest` and `GeometryBakeOrderTest` hold the walk that writes the table rather than
the table itself. The first holds where a bone a factory adds off a part's return value hangs: under
the part a chained return came from, and at the root after a stored one. The second holds the part
tree a walk replays to the orders vanilla's own factories bake on the JDK 25 they run on, at the
levels vanilla fills with a copy - the scaled roots, the scaled armour shells, the replaced parts and
the retain surgeries - so a version bump that moves one fails there rather than shipping a draw order
nothing measured.
No test holds the table's rest degrees to the radians each geometry's factory spells. The
[renderer/Geometry.md](../renderer/Geometry.md) bullet "Do not carry a bone's rotation in radians"
names the rests the degrees lose by one ULP, and a version bump owes that set its re-measurement.
`GeometryFlowPartRestsTest` value-pins four shipped floats besides - the x of each armour stand's
arm pivots - reading the table alone rather than holding it to the jar.
