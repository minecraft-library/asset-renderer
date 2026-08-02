# Determinism

What reproduces, over how many runs, and what never can. Loaded on refusal R5 and before a first
promotion.

**Per-artifact floors and the counts actually recorded are in `artifacts.md`**, which is generated
from the roster and the store. They are not repeated here, because a second copy of a number is a
second thing to go stale - this file holds the reasoning, that file holds the values.

## Why determinism is a precondition rather than a result

A digest comparison says two runs produced the same bytes. That is only evidence about a *change* if
the producer would have produced the same bytes anyway. Until a producer is proven to repeat itself,
a clean compare and a broken producer are indistinguishable - so the floor is checked before a first
promotion is allowed, and `--bootstrap` does not lower it.

## Why two runs is not evidence, and where five is the floor

Two runs catch a producer that is *reliably* non-deterministic. They do not catch one that is
**intermittently** so, and this repo has that case: `Map.copyOf` and `Set.copyOf` apply a per-JVM
class-initialization salt, so an iteration order can agree twice and differ on the third run.

Every artifact exposed to that salt carries a floor of five. Everything else carries two, which is
the cheapest proof rather than a token one - the sweeps have agreed row-for-row across four fresh JVM
forks, so a moved row is a real change and never run noise.

A digest of a shipped file is a pure function of that file, so one run is the whole of the proof;
those artifacts carry a floor of one.

## What is reproducible

- **All six parity sweeps.** Exactly reproducible: four fresh JVM forks, 0 of 401 entity rows
  different, sum spread `0.0000`.
- **All eight tooling flows.** Every shipped table reproduces its own bytes, which is what makes the
  regen A/B admissible at all.
- **`fluidRenderer` and `portalRenderer`.** 12 files each, byte-for-byte across two `--rerun-tasks`
  runs. This is what converts them from a look-at-it task into a real gate.
- **The player contact sheets.** 104 files across the ten offline sheet groups.
- **The raw player and armour renders.** 18 files. The *rescaled* pair those sweeps also write is
  not in that artifact: it is an AWT resample, and digesting it would fold a JDK-owned computation
  into a value that is supposed to name this renderer.
- **The reference tree**, given one client boot. A partial refresh is the failure mode, not a
  cheaper version of the same thing.

## What can never be hashed

- **`atlas.png` and everything under `build/atlas/`.** `AtlasRenderer` dispatches its tiles on
  `parallelStream` by design, so two runs place the same sprites at different offsets. It is
  registered as no artifact and suppressed outright in the reach map. A must-not-crash smoke check.
- **Anything rendered from a live account's skin.** The `account` sheet group depends on a skin
  service, so its digest is a fact about the network on the day it ran. It is excluded from the
  contact-sheet artifact by name.
- **A scoped run of any sweep.** `-PentityId` writes one row into a report the store would read as a
  whole-corpus regression. Every scoped property suppresses its artifact's capture for that reason:
  a scoped run is a hole, not a sample.

## Two failure shapes to recognise

**A tree that hashes cleanly minus the missing files.** A partial producer run leaves fewer files,
and a manifest built over what is there is internally consistent and wrong. Every declared manifest
member is therefore required to exist: an absent member is a failure, never a smaller manifest.

**A capture reported UP-TO-DATE.** Gradle cannot see that a producer writes into a root the capture
just erased. Every capture task is marked never-up-to-date, and a self-capturing suite is
additionally forced to re-run while a capture is in flight - because otherwise the erase deletes the
only copy and the capture step fails on a file nothing rewrote.
