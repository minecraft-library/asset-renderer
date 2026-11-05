"""The schedule check a capture boots the client on, and the plan's answer that schedules it.

Two halves decide one boot, so both are held here: :func:`schedule.refresh`, which the plan reads the
selecting rules through, and the capture's holds - the plan held to the tree as it stands, the
harness inputs held to the record of the last whole render, and :func:`schedule.check`, which
projects the regenerated roster. The fixture is a version root in miniature - a roster, the two
sidecars and a frozen ``entities/`` listing - shaped the way the asset side and the harness write
them, and each refusal is measured as a perturbation of it that has to stop agreeing. The holds that
read git are measured in a repository of their own, planned and then edited.
"""

from __future__ import annotations

import contextlib
import io
import json
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from parity import blindness, cli, provenance, reach, schedule, store
from parity.norm import read_json, read_text, write_json, write_text

#: The repository root, where the shipped map, the graph and the build scripts live.
_REPO = store.repo_root()

#: The build script that wires the check into a capture.
_PARITY_SCRIPT = _REPO / "gradle" / "parity.gradle.kts"


def _roster() -> dict:
    """A roster naming a plain entity, a baby, a traited entity and one no sweep draws."""
    still = {"frames": 1, "ticks_per_frame": 3, "walk_speed": 0.0, "walk_rate": 0.0}
    return {
        "format": 1,
        "source_version": "26.1",
        "subjects": {
            "minecraft:cow": {
                "adult": {"idle": {"frames": 40, "ticks_per_frame": 3, "walk_speed": 0.0,
                                   "walk_rate": 0.0},
                          "walk": {"frames": 40, "ticks_per_frame": 1, "walk_speed": 0.4444446,
                                   "walk_rate": 0.4444446}},
                "baby": {"idle": {"frames": 20, "ticks_per_frame": 3, "walk_speed": 0.0,
                                  "walk_rate": 0.0},
                         "walk": {"frames": 20, "ticks_per_frame": 1, "walk_speed": 0.4444446,
                                  "walk_rate": 0.8888892}},
            },
            "minecraft:enderman": {
                "adult": {gait: {"frames": 30, "ticks_per_frame": 3, "walk_speed": 0.0,
                                 "walk_rate": 0.0, "clocks": {"tilt": [0.0] * 30},
                                 "traits": {"carried=minecraft__grass_block": {
                                     "frames": 30, "ticks_per_frame": 3, "walk_speed": 0.0,
                                     "walk_rate": 0.0}}}
                          for gait in schedule.GAITS},
            },
            "minecraft:marker": {"adult": {gait: dict(still) for gait in schedule.GAITS}},
        },
    }


def _sidecar(roster: dict, gait: str) -> dict:
    """What the harness records after rendering every drawn subject of a roster at one gait."""
    stems = {"minecraft__cow_temperate": ("minecraft:cow", "adult", None),
             "minecraft__cow_temperate~age=baby": ("minecraft:cow", "baby", None),
             "minecraft__enderman": ("minecraft:enderman", "adult", None),
             "minecraft__enderman~carried=minecraft__grass_block":
                 ("minecraft:enderman", "adult", "carried=minecraft__grass_block")}
    subjects = {}
    for stem, (entity, age, trait) in stems.items():
        entry = roster["subjects"][entity][age][gait]
        if trait:
            entry = entry["traits"][trait]
        recorded = {"entity": entity, "age": age}
        recorded.update({name: entry[name] for name in schedule.FIELDS})
        if entry.get("clocks"):
            recorded["clocks"] = {name: list(values) for name, values in entry["clocks"].items()}
        subjects[stem] = recorded
    return {"format": 1, "subjects": subjects}


#: The frozen entity sub-tree's references of the drawn subjects; the marker draws in none.
_ENTITY_FILES = ("minecraft__cow_temperate.png", "minecraft__cow_cold.png",
                 "minecraft__cow_temperate~age=baby.png", "minecraft__enderman.png")


class Fixture(unittest.TestCase):
    """A version root whose roster, sidecars and frozen sub-tree agree until a test perturbs one."""

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.repo, True)
        self.roster = _roster()
        self.sidecars = {gait: _sidecar(self.roster, gait) for gait in schedule.GAITS}
        self.entities = list(_ENTITY_FILES)

    def verdict(self, client_version: str = "26.1.2") -> dict:
        version = self.repo / "cache" / "26.1"
        if version.exists():
            shutil.rmtree(version)
        self._write(version / "entity-schedule" / "roster.json", self.roster)
        for gait, sidecar in self.sidecars.items():
            self._write(version / "references" / gait / "schedule.json", sidecar)
        if self.entities is not None:
            (version / "references" / "entities").mkdir(parents=True)
            for name in self.entities:
                (version / "references" / "entities" / name).write_bytes(b"")
        return schedule.check(self.repo, "cache/26.1/references", client_version)

    @staticmethod
    def _write(path: Path, payload) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(json.dumps(payload).encode("utf-8"))


class AnAgreeingTree(Fixture):

    def test_agrees_and_excuses_only_what_no_sweep_draws(self):
        verdict = self.verdict()
        self.assertTrue(verdict["agrees"], verdict["disagreements"])
        self.assertEqual(verdict["excused"],
                         ["minecraft:marker adult idle", "minecraft:marker adult walk"])
        self.assertEqual(verdict["stems"], {"idle": 4, "walk": 4})

    def test_the_verdict_names_where_it_read(self):
        verdict = self.verdict()
        self.assertEqual(verdict["references"], "cache/26.1/references")
        self.assertEqual(verdict["roster"], "cache/26.1/entity-schedule/roster.json")
        self.assertEqual(verdict["source_version"], "26.1")

    def test_a_patch_of_the_same_release_line_agrees(self):
        """The harness compares by major.minor: the asset side reads the release line's jar."""
        self.assertTrue(self.verdict("26.1")["agrees"])


class EachMoveTheHarnessWouldRenderDisagrees(Fixture):

    def assertDisagrees(self, fragment: str) -> None:
        verdict = self.verdict()
        self.assertFalse(verdict["agrees"])
        self.assertEqual(verdict["count"], len(verdict["disagreements"]))
        self.assertTrue(any(fragment in line for line in verdict["disagreements"]),
                        verdict["disagreements"])

    def test_a_moved_walk_speed(self):
        self.roster["subjects"]["minecraft:cow"]["adult"]["walk"]["walk_speed"] = 0.5
        self.assertDisagrees("walk: minecraft__cow_temperate was rendered at")

    def test_a_moved_frame_count(self):
        self.roster["subjects"]["minecraft:cow"]["baby"]["idle"]["frames"] = 21
        self.assertDisagrees("idle: minecraft__cow_temperate~age=baby")

    def test_a_moved_clock_value(self):
        self.roster["subjects"]["minecraft:enderman"]["adult"]["idle"]["clocks"]["tilt"][3] = 0.25
        self.assertDisagrees("idle: minecraft__enderman was rendered at")

    def test_a_clock_the_roster_adds(self):
        self.roster["subjects"]["minecraft:cow"]["adult"]["idle"]["clocks"] = {"tilt": [0.0] * 40}
        self.assertDisagrees("idle: minecraft__cow_temperate was rendered at")

    def test_a_trait_the_roster_adds(self):
        """A new posed trait is a new subject the harness renders, so no strip of it is on disk."""
        entry = self.roster["subjects"]["minecraft:enderman"]["adult"]["walk"]
        entry["traits"]["carried=minecraft__stone"] = dict(entry["traits"]["carried=minecraft__grass_block"])
        self.assertDisagrees("walk: the roster names minecraft:enderman adult carried=minecraft__stone")

    def test_a_trait_the_roster_drops(self):
        del self.roster["subjects"]["minecraft:enderman"]["adult"]["idle"]["traits"]
        self.assertDisagrees("rendered and the roster names no schedule for it")

    def test_a_roster_entry_whose_strip_is_missing_where_the_client_draws_it(self):
        del self.sidecars["walk"]["subjects"]["minecraft__cow_temperate~age=baby"]
        self.assertDisagrees("walk: the frozen entities/ shows the client draws minecraft:cow baby")

    def test_coats_are_refused_whatever_they_say(self):
        """The canvas is measured across every coat, and the sidecar records the drawn one alone."""
        entry = self.roster["subjects"]["minecraft:cow"]["adult"]["idle"]
        entry["coats"] = {"cold": {name: entry[name] for name in schedule.FIELDS}}
        self.assertDisagrees("carries coats")

    def test_a_trait_nesting_another(self):
        trait = self.roster["subjects"]["minecraft:enderman"]["adult"]["idle"]["traits"]
        trait["carried=minecraft__grass_block"]["traits"] = {}
        self.assertDisagrees("nests coats or traits")

    def test_a_roster_resolved_for_another_client(self):
        self.roster["source_version"] = "26.2"
        self.assertDisagrees("resolved for '26.2'")

    def test_a_roster_of_another_format(self):
        self.roster["format"] = 2
        self.assertDisagrees("format '2'")

    def test_a_sidecar_of_another_format(self):
        self.sidecars["idle"]["format"] = 2
        self.assertDisagrees("idle/schedule.json is not format 1")

    def test_no_roster(self):
        self.roster = None
        verdict = schedule.check(self.repo, "cache/26.1/references", "26.1.2")
        self.assertFalse(verdict["agrees"])
        self.assertIn("no entity-schedule/roster.json beside the tree", verdict["disagreements"])

    def test_no_frozen_entity_sub_tree_to_excuse_anything(self):
        self.entities = None
        self.assertDisagrees("holds no entities/ sub-tree")

    def test_a_stem_naming_two_subjects(self):
        sidecar = self.sidecars["idle"]["subjects"]
        sidecar["minecraft__enderman~carried=a~carried=b"] = dict(sidecar["minecraft__enderman"])
        self.assertDisagrees("names no one subject")

    def test_two_stems_recording_one_subject(self):
        """A stem of an older spelling beside the live one: which is compared is not sort order's."""
        sidecar = self.sidecars["walk"]["subjects"]
        sidecar["minecraft__cow_old_spelling"] = dict(sidecar["minecraft__cow_temperate"])
        self.assertDisagrees("both record minecraft:cow adult")

    def test_refusals_found_outside_the_roster_are_listed_first(self):
        self.verdict()
        verdict = schedule.check(self.repo, "cache/26.1/references", "26.1.2", ["outside"])
        self.assertFalse(verdict["agrees"])
        self.assertEqual(verdict["disagreements"], ["outside"])


class ValuesAreComparedAsTheirNumbers(Fixture):

    def test_another_spelling_of_the_same_float_refuses_rather_than_passes(self):
        """Refusing a roster the harness reads the same costs a boot; passing one it reads
        differently would cost the gate."""
        self.roster["subjects"]["minecraft:cow"]["adult"]["walk"]["walk_speed"] = 0.44444461
        self.assertFalse(self.verdict()["agrees"])

    def test_an_integer_and_a_float_of_one_value_are_one_value(self):
        self.roster["subjects"]["minecraft:cow"]["adult"]["idle"]["walk_speed"] = 0
        self.assertTrue(self.verdict()["agrees"])


class TheWitness(unittest.TestCase):

    def test_a_coat_and_an_appearance_token_belong_to_the_entity(self):
        names = ["minecraft__cow_cold.png", "minecraft__wither~charged=true.png"]
        self.assertTrue(schedule._witnessed(names, "minecraft:cow", "adult"))
        self.assertTrue(schedule._witnessed(names, "minecraft:wither", "adult"))

    def test_the_age_is_read_off_the_stem(self):
        names = ["minecraft__cow_temperate.png"]
        self.assertFalse(schedule._witnessed(names, "minecraft:cow", "baby"))
        self.assertTrue(schedule._witnessed(["minecraft__cow_temperate~age=baby.png"],
                                            "minecraft:cow", "baby"))

    def test_another_entity_sharing_no_boundary_is_not_one(self):
        self.assertFalse(schedule._witnessed(["minecraft__cowboy.png"], "minecraft:cow", "adult"))


class ThePlanHeldToTheTree(unittest.TestCase):

    ROSTER_B47 = {"refresh": schedule.ROSTER, "selected_by": ["B47"]}

    def test_the_same_roster_answer_agrees(self):
        self.assertEqual(schedule.held_to_plan(self.ROSTER_B47, dict(self.ROSTER_B47)), [])

    def test_no_plan_refuses(self):
        self.assertIn("holds no plan", schedule.held_to_plan(None, self.ROSTER_B47)[0])

    def test_a_tree_that_boots_refuses(self):
        live = {"refresh": schedule.BOOT, "selected_by": ["B29", "B47"]}
        self.assertIn("through B29, B47, which boot", schedule.held_to_plan(self.ROSTER_B47, live)[0])

    def test_other_roster_rules_refuse(self):
        """Still a roster answer, but not the change set the plan was priced for."""
        live = {"refresh": schedule.ROSTER, "selected_by": ["B47", "B65"]}
        self.assertIn("made of another tree", schedule.held_to_plan(self.ROSTER_B47, live)[0])

    def test_a_tree_planning_no_row_refuses(self):
        self.assertIn("plans no manifest.references", schedule.held_to_plan(self.ROSTER_B47, None)[0])


def _rule(rule_id: str, triggers: list[str], sees: tuple[str, ...] = (schedule.REFERENCES,),
          derived: bool = False) -> blindness.Rule:
    """One rule of a map, as much of it as the record reads."""
    return blindness.Rule(id=rule_id, claim="c", trigger_paths=tuple(triggers),
                          sees=() if derived else sees, blind=(), reason="r", mode="select",
                          probe="p", source="s", derived=derived)


class TheRenderRecord(unittest.TestCase):

    RULES = [_rule("B29", ["harness/**"]), _rule("B67", ["roster/tooltip.json"]),
             _rule("B47", ["build.gradle.kts"]), _rule("B25", ["src/**"], derived=True),
             _rule("B90", ["harness/docs/**"], sees=("sweep.entity",))]

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.repo, True)
        self.files = ["harness/Sweep.java", "roster/tooltip.json", "build.gradle.kts", "src/A.java"]
        for name in self.files:
            write_text(self.repo / name, name)
        self.tree = self.repo / "cache" / "references"

    def current(self) -> dict:
        return schedule.inputs(self.repo, self.files, self.RULES)

    def seal(self) -> None:
        write_json(self.tree.parent / schedule.RECORD,
                   schedule.record("cache/references", self.current()))

    def test_it_covers_what_boots_the_tree_and_nothing_the_roster_or_the_graph_answers(self):
        current = self.current()
        self.assertEqual(current["rules"], ["B29", "B67"])
        self.assertEqual(current["files"], 2)

    def test_a_sealed_record_of_the_current_inputs_agrees(self):
        self.seal()
        self.assertEqual(schedule.held_to_record(self.tree, self.current()), [])

    def test_no_record_refuses(self):
        """A narrow run, a failed boot, or a tree no whole render has written since."""
        self.assertIn(f"no {schedule.RECORD}", schedule.held_to_record(self.tree, self.current())[0])

    def test_a_pending_record_alone_refuses(self):
        """Written before the boot, so it says nothing about how the boot ended."""
        write_json(self.tree.parent / schedule.PENDING,
                   schedule.record("cache/references", self.current()))
        self.assertTrue(schedule.held_to_record(self.tree, self.current()))

    def test_an_input_edited_since_the_render_refuses(self):
        self.seal()
        write_text(self.repo / "harness/Sweep.java", "edited")
        self.assertIn("other inputs", schedule.held_to_record(self.tree, self.current())[0])

    def test_an_input_added_since_the_render_refuses(self):
        self.seal()
        write_text(self.repo / "harness/New.java", "new")
        self.files.append("harness/New.java")
        self.assertTrue(schedule.held_to_record(self.tree, self.current()))

    def test_an_edit_the_roster_answers_for_moves_nothing_here(self):
        self.seal()
        write_text(self.repo / "build.gradle.kts", "bumped")
        write_text(self.repo / "src/A.java", "edited")
        self.assertEqual(schedule.held_to_record(self.tree, self.current()), [])

    def test_unlisted_inputs_refuse(self):
        self.seal()
        self.assertTrue(schedule.held_to_record(self.tree, None))


def _git(repo: Path, *argv: str) -> None:
    subprocess.run(["git", *argv], cwd=repo, check=True, capture_output=True)


class TheCheckResolvesTheTreeAgain(Fixture):
    """A plan says roster, and the tree is edited after it: the capture's check must not skip.

    A real repository, planned through the command the build runs and then checked through the
    command the build runs, because what is held is that the check reads the tree as it stands
    rather than the plan file the capture was handed.
    """

    MAP = {"artifact": "roster.blindness-rules", "format": 1, "key": "id",
           "kind": "blindness-roster", "no_reach": [],
           "rules": [{"id": "B47", "claim": "c", "mode": "select", "probe": "p", "reason": "r",
                      "source": "s", "sees": [schedule.REFERENCES], "blind": [],
                      "trigger_paths": ["build.gradle.kts"]},
                     {"id": "B29", "claim": "c", "mode": "select", "probe": "p", "reason": "r",
                      "source": "s", "sees": [schedule.REFERENCES], "blind": [],
                      "trigger_paths": ["harness/**"]}]}

    ROOT = "cache/parity/current"

    def setUp(self):
        super().setUp()
        write_json(self.repo / "store" / "blindness.json", self.MAP)
        write_text(self.repo / "build.gradle.kts", "pins\n")
        write_text(self.repo / "harness" / "Sweep.java", "class Sweep {}\n")
        # Ignored as in the real repo, so neither the working root nor the tree is a changed path.
        write_text(self.repo / ".gitignore", "cache/\nstore/\n")
        _git(self.repo, "init", "-q")
        _git(self.repo, "add", "-A")
        _git(self.repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "seed")
        self.verdict()
        self._toolkit("render-record", "--references", "cache/26.1/references")
        pending = self.repo / "cache" / "26.1" / schedule.PENDING
        pending.replace(pending.with_name(schedule.RECORD))

    def _toolkit(self, *argv: str) -> None:
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = cli.main(["--repo-root", str(self.repo), "--root", self.ROOT,
                             "--store", str(self.repo / "store"), "--quiet", *argv])
        self.assertEqual(code, cli.OK, out.getvalue())

    def _checked(self) -> dict:
        self._toolkit("schedule-check", "--references", "cache/26.1/references",
                      "--client-version", "26.1.2")
        return read_json(self.repo / self.ROOT / store.RUN_DIR / schedule.VERDICT)

    def test_a_pin_bump_planned_and_checked_on_one_tree_agrees(self):
        write_text(self.repo / "build.gradle.kts", "bumped\n")
        self._toolkit("plan")
        verdict = self._checked()
        self.assertTrue(verdict["agrees"], verdict["disagreements"])
        self.assertEqual(verdict["refresh"]["tree"], {"refresh": schedule.ROSTER, "selected_by": ["B47"]})

    def test_a_harness_edit_after_a_roster_plan_disagrees(self):
        write_text(self.repo / "build.gradle.kts", "bumped\n")
        self._toolkit("plan")
        write_text(self.repo / "harness" / "Sweep.java", "class Sweep { int edited; }\n")
        verdict = self._checked()
        self.assertFalse(verdict["agrees"])
        self.assertTrue(any("B29" in line and "boot" in line for line in verdict["disagreements"]),
                        verdict["disagreements"])

    def test_a_harness_file_added_after_a_roster_plan_disagrees(self):
        """Untracked and unignored, so a commit can carry it and the client compiles it."""
        write_text(self.repo / "build.gradle.kts", "bumped\n")
        self._toolkit("plan")
        write_text(self.repo / "harness" / "Added.java", "class Added {}\n")
        self.assertFalse(self._checked()["agrees"])

    def test_a_narrow_run_since_the_whole_one_disagrees(self):
        write_text(self.repo / "build.gradle.kts", "bumped\n")
        self._toolkit("plan")
        (self.repo / "cache" / "26.1" / schedule.RECORD).unlink()
        self.assertFalse(self._checked()["agrees"])

    def test_no_plan_disagrees(self):
        write_text(self.repo / "build.gradle.kts", "bumped\n")
        self.assertFalse(self._checked()["agrees"])

    def _gate(self, **captured) -> int:
        """The hook's answer over a verdict covering this tree, beside a capture of the tree's row."""
        root = self.repo / self.ROOT
        write_json(root / store.path_of(schedule.REFERENCES),
                   {"artifact": schedule.REFERENCES, "provenance": captured})
        write_json(root / store.RUN_DIR / "last-verdict.json",
                   {"artifacts": [schedule.REFERENCES],
                    "asset_dirty_digest": provenance.dirty_digest(self.repo),
                    "asset_sha": provenance.asset_state(self.repo)["asset_sha"]})
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return cli.main(["--repo-root", str(self.repo), "--root", self.ROOT,
                             "--store", str(self.repo / "store"), "--quiet", "plan", "--gate-exit"])

    def test_the_hook_refuses_a_hashed_tree_as_the_gate_of_a_change_that_boots_it(self):
        write_text(self.repo / "harness" / "Sweep.java", "class Sweep { int edited; }\n")
        self.assertEqual(self._gate(tree_refresh=schedule.HASHED), cli.GATE_SEES_UNGATED)
        self.assertEqual(self._gate(mode="EVERY,ANIMATION,WALK"), cli.GATE_ALREADY_GATED)

    def test_the_hook_takes_a_hashed_tree_as_the_gate_of_a_change_the_roster_answers(self):
        write_text(self.repo / "build.gradle.kts", "bumped\n")
        self.assertEqual(self._gate(tree_refresh=schedule.HASHED), cli.GATE_ALREADY_GATED)


class TheHookReadsTheHashedTree(unittest.TestCase):
    """A capture that hashed the tree is no gate of a change that boots it."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)

    def _captured(self, **provenance) -> None:
        write_json(self.root / store.path_of(schedule.REFERENCES),
                   {"artifact": schedule.REFERENCES, "provenance": provenance})

    def test_a_hashed_capture_reads_as_hashed(self):
        self._captured(mode=None, tree_refresh=schedule.HASHED)
        self.assertTrue(cli._hashed_references(self.root))

    def test_a_rendered_capture_does_not(self):
        self._captured(mode="EVERY,ANIMATION,WALK")
        self.assertFalse(cli._hashed_references(self.root))

    def test_no_capture_does_not(self):
        self.assertFalse(cli._hashed_references(self.root))


class TheRefresh(unittest.TestCase):

    def test_the_roster_rules_and_derived_reach_hash_the_tree(self):
        self.assertEqual(schedule.refresh(["B47"], set()), schedule.ROSTER)
        self.assertEqual(schedule.refresh(["B47", "B65", "B25"], {"B25"}), schedule.ROSTER)

    def test_any_other_rule_boots(self):
        self.assertEqual(schedule.refresh(["B47", "B67"], set()), schedule.BOOT)
        self.assertEqual(schedule.refresh(["B29"], {"B25"}), schedule.BOOT)

    def test_no_selecting_rule_boots(self):
        self.assertEqual(schedule.refresh([], {"B25"}), schedule.BOOT)


class TheShippedMap(unittest.TestCase):
    """The plan's answer over the real map, which is what a gate reads."""

    def setUp(self):
        self.rules, self.no_reach = blindness.load(_REPO / store.PRODUCTION)
        self.graph = json.loads(read_text(_REPO / "parity" / reach.STORED))

    def _answer(self, changed: list[str]) -> dict | None:
        resolved = blindness.resolve(changed, self.rules, self.no_reach,
                                     lambda path: reach.answered_by(self.graph, path))
        return cli._references(resolved.sees, resolved, self.rules)

    def test_a_version_pin_hashes_the_tree(self):
        answer = self._answer(["build.gradle.kts"])
        self.assertEqual(answer, {"refresh": schedule.ROSTER, "selected_by": ["B47"]})

    def test_the_roster_inputs_hash_the_tree(self):
        answer = self._answer(["src/main/resources/lib/minecraft/renderer/entity_models.json"])
        self.assertEqual(answer["refresh"], schedule.ROSTER)
        self.assertIn("B65", answer["selected_by"])

    def test_a_type_the_graph_routes_to_the_tree_hashes_it(self):
        """Read off the committed graph rather than named, so a type leaving the graph moves nothing
        here: any type answered with the reference row is one the roster's root reaches."""
        types = self.graph.get("types") or {}
        routed = sorted(binary for binary, row in types.items()
                        if schedule.REFERENCES in row.get("artifacts", ())
                        and (_REPO / "src/main/java" / f"{binary}.java").is_file())
        self.assertTrue(routed, "the graph routes no main type to the reference row")
        answer = self._answer([f"src/main/java/{routed[0]}.java"])
        self.assertEqual(answer["refresh"], schedule.ROSTER, answer)

    def test_the_tooltip_roster_boots(self):
        """Read by the harness by path, so the schedule roster says nothing about it."""
        answer = self._answer(["src/visual/resources/lib/minecraft/renderer/sweep/tooltip-roster.json"])
        self.assertEqual(answer, {"refresh": schedule.BOOT, "selected_by": ["B67"]})

    def test_the_book_roster_boots(self):
        """Read by the harness by path, as the tooltip roster is, so the schedule roster says nothing
        about it."""
        answer = self._answer(["src/visual/resources/lib/minecraft/renderer/sweep/book-roster.json"])
        self.assertEqual(answer, {"refresh": schedule.BOOT, "selected_by": ["B73"]})

    def test_a_harness_edit_boots(self):
        answer = self._answer(["harness/build.gradle.kts"])
        self.assertEqual(answer["refresh"], schedule.BOOT)
        self.assertIn("B29", answer["selected_by"])

    def test_a_pin_beside_a_harness_sweep_boots(self):
        answer = self._answer(["build.gradle.kts",
                               "harness/src/client/java/lib/minecraft/refharness/sweep/MenuSweep.java"])
        self.assertEqual(answer["refresh"], schedule.BOOT)

    def test_a_plan_without_the_row_answers_nothing(self):
        self.assertIsNone(self._answer(["src/main/resources/lib/minecraft/renderer/potion_colors.json"]))

    def test_each_roster_rule_selects_the_row_from_outside_the_harness(self):
        """A rule listed here skips the boot, so it has to be one whose triggers the harness reads only
        through the roster: authored, selecting the row, and triggering on no harness path."""
        by_id = {rule.id: rule for rule in self.rules}
        for rule_id in schedule.ROSTER_RULES:
            rule = by_id[rule_id]
            self.assertFalse(rule.derived, rule_id)
            self.assertIn(schedule.REFERENCES, rule.sees, rule_id)
            self.assertFalse([path for path in rule.trigger_paths if path.startswith("harness/")], rule_id)

    def test_every_other_rule_selecting_the_row_boots(self):
        others = sorted(rule.id for rule in self.rules
                        if schedule.REFERENCES in rule.sees and rule.id not in schedule.ROSTER_RULES)
        self.assertIn("B67", others)
        self.assertIn("B29", others)
        for rule_id in others:
            self.assertEqual(schedule.refresh([rule_id], set()), schedule.BOOT, rule_id)


class TheBuildReadsWhatThisWrites(unittest.TestCase):
    """The build decides the boot off two spellings this module owns, so the two are held together."""

    def setUp(self):
        self.build = read_text(_PARITY_SCRIPT)

    def test_the_build_reads_the_verdict_where_the_check_writes_it(self):
        self.assertIn(f'"$parityWorkingRoot/_run/{schedule.VERDICT}"', self.build)

    def test_the_build_skips_on_the_plans_roster_answer(self):
        self.assertIn(f'parityPlannedReferencesRefresh() == "{schedule.ROSTER}"', self.build)

    def test_the_build_reads_the_plan_member_the_plan_writes(self):
        self.assertIn('plan["references"] as Map<*, *>?)?.get("refresh")', self.build)

    def test_the_build_hands_the_check_the_tree_and_the_client(self):
        argv = re.search(r'add\("schedule-check"\)(.*?)\}\)', self.build, re.S)
        self.assertIsNotNone(argv, "the build runs no schedule-check")
        self.assertIn('add("--references"); add(parityReferenceRoot)', argv.group(1))
        self.assertIn('add("--client-version"); add(minecraftVersion)', argv.group(1))
        self.assertIn('add("--store"); add(parityProductionStore)', argv.group(1))

    def test_the_build_seals_the_record_this_writes_where_the_check_reads_it(self):
        self.assertIn(f'"/{schedule.RECORD}"', self.build)
        self.assertIn(f'"/{schedule.PENDING}"', self.build)
        self.assertIn('add("render-record")', self.build)

    def test_the_build_records_a_hashed_tree_in_the_spelling_the_hook_reads(self):
        self.assertIn(f'args("--tree-refresh", "{schedule.HASHED}")', self.build)


if __name__ == "__main__":
    unittest.main()
