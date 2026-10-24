# Debugging a mismatch

Load when a parity row disagrees with its reference and the cause is not yet known. Indexed from
[`CLAUDE.md`](../../CLAUDE.md).

1. `git log --oneline master..HEAD` for branch state.
2. Look at the subject's panel: `cache/visual/<sweep>-parity-vanilla/<subject>/diff_panel.png`.
   An animated row's panel is its worst frame alone; for every frame, run
   `python parity/scripts/parity panel frames --source <that sweep dir> --subject <subject>`.
3. Re-run the one subject: `./gradlew entityParityVanilla -PentityId=minecraft:X -q`.
4. Go to pixels with `-Dasset.entity.pixel.dump=x0,y0,x1,y1` and `-Dasset.entity.bounds.dump=true`,
   then walk back from the write log to the texel, shade and blend that produced the mismatch. Every
   custom flag lives under `asset.*` and auto-forwards to each JavaExec and Test fork.
   `python parity/scripts/parity panel peek --source <sweep dir> --subject <subject>` names the
   worst pixels, says whether each is a vanilla colour in the wrong place or one vanilla never drew,
   and prints the dump region in java's own frame.
5. For a vanilla source lookup, `javap -c -p` the class under `cache/dragon-extract/`. A bridge
   method usually just calls the narrowed one.

The probe-reading traps, the worked examples and the two version-scoped rosters are in the parity
gate's [`references/diagnostics.md`](../../.claude/skills/parity-gate/references/diagnostics.md).
