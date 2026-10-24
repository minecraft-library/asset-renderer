# JMH

Load when running or adding a benchmark. Indexed from [`CLAUDE.md`](../../CLAUDE.md).

`./gradlew jmh` with `-PjmhWarmup` (3), `-PjmhIters` (5), `-PjmhForks` (2), `-PjmhInclude=<regex>`
and `-PjmhProfilers=gc,stack`. Forks get `-Xmx2g` plus the Vector module. Benches live in
`src/jmh/java/lib/minecraft/renderer/bench/`.
