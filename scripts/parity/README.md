# parity

The gate toolkit. One writer, one store root, one id grammar - so a sum, a join and a tree hash each
have exactly one implementation instead of the 67 hand-run `awk` sums, 36 hand-run joins and 23
hand-run tree hashes the corpus accumulated.

## Invocation

```
python scripts/parity <command> [options]
```

One form for Gradle, for the skill and for a human. No shebang, no `./`, no `PYTHONPATH`, no
`pip install -e`, no wrapper, no `.bat` and no `.ps1`: `__main__.py` puts its own parent on
`sys.path` and imports the package absolutely, so nothing has to be configured. `--repo-root`
defaults to the repo containing this directory, so the working directory never matters.

`python -m parity` happens to resolve for anyone who already has `scripts/` on `PYTHONPATH`, but it
is **not** a supported spelling and appears in no task, skill or procedure. One form means one
string to grep for.

Interpreter floor **3.11**, checked in `__init__.py` so a too-old interpreter fails with a sentence
naming its own version rather than a SyntaxError several frames deep.

## Global options

Accepted by every command, on either side of the subcommand.

```
--repo-root DIR       the repo containing scripts/parity  (default: derived)
--root DIR            the WORKING root                    (default: cache/parity/current)
--store DIR           the production store                (default: src/test/resources/lib/minecraft/renderer/parity)
--format {text,json}  stdout form                         (default: text)
--out FILE            write the primary artifact here instead of stdout
-q, --quiet           suppress progress on stderr; never affects stdout
--version
```

There is **no `--slot`**. The working root is a path, and an A/B before-side is a redirected root
(`--root cache/parity/base`) rather than a second name the package knows. The root must be
**relative and under `cache/`**, which is what makes a long-lived temp capture unrepresentable
rather than merely discouraged.

**stdout carries the answer and nothing else.** Progress, warnings and diagnostics go to stderr
always, so `python scripts/parity <cmd> --format json | <consumer>` is safe. No command reads stdin:
every operand is a path, because a piped operand cannot be named in a provenance record.

**No command writes production.** `store.production()` returns a view whose `write()` raises; only
`promote-apply` constructs the writable one, and only after a plan validates.

## Exit codes

Six codes, one meaning each, identical across every command. `cli.main` is the only translator - no
module calls `sys.exit` - so the table is enforceable rather than conventional.

| Code | Name | Meaning |
|---|---|---|
| 0 | OK | The command ran and found nothing wrong. |
| 1 | DIFFERENCES | The comparison succeeded and the answer is that they differ. **The gate signal.** |
| 2 | USAGE | Bad arguments or an unknown command. Argparse's own code, so a typo is never read as a real failure. |
| 3 | MISSING INPUT | A named path or artifact is absent. The comparison could not be attempted. |
| 4 | MISSING DEPENDENCY | An optional import is absent and the command needs it. Names the install line. |
| 5 | REFUSED | A precondition failed and the command declined on purpose. |

The separation of **1** from **3** and **5** is the point: a comparison that could not be attempted
never returns 1.

## Layout

| Module | Role |
|---|---|
| `norm.py` | **the sole writer** - LF, UTF-8 no BOM, one trailing newline, canonical JSON, digests. Also the typed errors, because it is the one module that imports nothing from the package. |
| `store.py` | the two roots, the artifact-id to path map, the read-only guard on production |
| `capture.py` | a capture's boundaries - the single-slot erase, the two `_run/` markers and the index that closes a capture - plus the TSV-to-canonical-JSON normalize, which stamps a row rather than bounding one. It calls `provenance.gather`, so a captured payload's `provenance` object is written here, all but one: `manifest build` is the one artifact write that lands in a capture without passing through here, carrying the counts-and-root object `manifest.to_artifact` builds and `cli` writes, which is why `store.artifact_files` reads a `provenance` member as evidence of a capture step rather than proof of one. `promote.py` reopens that object on each artifact it writes, to add the promotion's own keys - `reason` and `parity_class` always, plus `allow_partial`, `allow_dirty` or `population_changed` where that waiver was given. The `provenance` subcommand gathers a record only to print it, never onto a payload. A capture is what the root holds outside `_run/`; the plan, the expected-diff manifest, the compare and promote reports and the verdict are `cli`'s, each written into `_run/`, which both the capture index and the promotion enumeration skip |
| `ids.py` | the five subject-id spellings, the appearance-key grammar, the armour stem |
| `cli.py` | the argparse tree and the one place an exception becomes an exit code |
| `tests/` | `unittest`, run by `python scripts/parity selftest`; fast and offline |

`norm` being the sole writer is enforced by a test that reads the package's own source, so a module
written next year is covered without being registered anywhere.

## Optional dependencies

The gate path is **stdlib only**. Pillow and numpy are admitted for pixel work alone, are optional
at runtime, and are unreachable from any command that produces or verifies a stored artifact - a
test asserts they appear in `pixels.py` and nowhere else. `python scripts/parity doctor` reports
what is importable.
