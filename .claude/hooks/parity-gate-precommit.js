#!/usr/bin/env node
/**
 * PreToolUse hook: notice a commit that no parity gate has covered.
 *
 * It is a DETECTOR, not a gatekeeper. Its only two outputs are silence and one `ask`, because the
 * user commits locally and pushes themselves - there is no downstream consumer to protect, so there
 * is no case where a hard block is justified. A user who approves past it has committed ungated,
 * which is a choice they made explicitly rather than by omission, and that distinction is the whole
 * reason for `ask` over `deny`.
 *
 * Three properties make it affordable to be wrong:
 *
 *   1. It never denies.
 *   2. It is silent unless all three activation conditions hold - the next act is a commit, the tree
 *      touches a path some blindness rule triggers on, and no compare verdict already covers this
 *      exact tree state. That last one is what stops it firing again on a tree already gated.
 *   3. It FAILS OPEN. A missing interpreter, a crash, a timeout, an unparseable payload - every one
 *      of them exits 0 in silence. A broken hook must never block work, so there is exactly one
 *      path in this file that writes to stdout and every other path returns quietly.
 *
 * It runs no measurement. It shells to `python scripts/parity plan --gate-exit`, which reads
 * `blindness.json`, git's changed set and `_run/last-verdict.json`, and answers in its exit code:
 * 0 nothing sees the change, 10 seen and ungated, 20 already gated for this tree. That is the same
 * reach resolution the skill and a human get, so there is one implementation of it and this file
 * contains none of it.
 */

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

/** Exit codes `plan --gate-exit` answers with. Anything else is unrecognised and fails open. */
const SEEN_UNGATED = 10;
const ALREADY_GATED = 20;

/** Hard ceiling on the child, below the 20 s the hook is registered with. */
const TIMEOUT_MS = 15000;

/** The prompt is a summary, never the plan. The full plan is on disk for the skill to read. */
const REASON_CAP = 300;

/** The repo this hook belongs to: two levels above `.claude/hooks/`. */
const REPO = path.resolve(__dirname, '..', '..');

/** Where `parityPlan` leaves the plan it just resolved. */
const PLAN_FILE = path.join(REPO, 'cache', 'parity', 'current', '_run', 'plan.json');

/** Git's own options that consume the NEXT token as their value, so it is not the subcommand. */
const GIT_VALUE_OPTIONS = new Set([
  '-C', '-c', '--git-dir', '--work-tree', '--namespace', '--exec-path', '--super-prefix',
]);

/**
 * Whether a shell command line runs `git commit` as a command rather than mentioning it.
 *
 * Split on the shell's own separators so a chained `git add -A && git commit` is seen, then decide
 * each segment by TOKEN rather than by a wider regex: the first token must be git, and the first
 * token after it that is neither an option nor an option's value must be `commit`.
 *
 * Tokenizing is what makes `git -C . commit` match while `git log commit` does not - `-C` consumes
 * the `.`, so the subcommand really is the next token. A regex that skipped `-\S+` alone missed the
 * separated value and read `.` as the subcommand.
 *
 * Erring toward firing is cheap here and erring against it is not: the output is one `ask`.
 *
 * @param {string} command the command line
 * @returns {boolean} whether a commit is about to run
 */
function runsGitCommit(command) {
  return command.split(/&&|\|\||[;|\n]/).some((segment) => {
    const tokens = segment.trim().split(/\s+/).filter(Boolean);
    if (tokens.length === 0 || !/(^|[\\/])git(\.exe)?$/i.test(tokens[0])) return false;
    for (let index = 1; index < tokens.length; index += 1) {
      const token = tokens[index];
      if (GIT_VALUE_OPTIONS.has(token)) {
        index += 1;
        continue;
      }
      if (token.startsWith('-')) continue;
      return token === 'commit';
    }
    return false;
  });
}

/**
 * Resolves a Python interpreter the way the Gradle build does.
 *
 * Windows puts a Microsoft Store app-execution alias at `WindowsApps\python.exe` and it usually
 * comes first on PATH. It is a reparse stub a shell can launch and a spawned process cannot, so it
 * is skipped rather than tried - which is the difference between this hook working and it silently
 * failing open on every commit.
 *
 * @returns {string|null} the interpreter, or null when none was found
 */
function findPython() {
  if (process.env.PARITY_PYTHON) return process.env.PARITY_PYTHON;
  const windows = process.platform === 'win32';
  const names = windows ? ['python.exe', 'python3.exe'] : ['python3', 'python'];
  for (const dir of (process.env.PATH || '').split(path.delimiter)) {
    if (!dir || dir.replace(/\\/g, '/').includes('/WindowsApps')) continue;
    for (const name of names) {
      const candidate = path.join(dir, name);
      try {
        if (fs.statSync(candidate).isFile()) return candidate;
      } catch {
        // Not there; keep walking. An unreadable PATH entry is not an error worth reporting.
      }
    }
  }
  return null;
}

/**
 * Builds the one-line reason from the plan the child just wrote.
 *
 * Read from disk rather than recomputed, so the artifact names in the prompt are the same names the
 * skill will print. A missing or unreadable plan degrades to a prompt with no counts rather than to
 * no prompt: the exit code already said the gate has not run, and that is the part worth saying.
 *
 * @returns {string} the permission-decision reason
 */
function buildReason() {
  let sees = [];
  let blind = 0;
  try {
    const plan = JSON.parse(fs.readFileSync(PLAN_FILE, 'utf8'));
    if (Array.isArray(plan.sees)) sees = plan.sees;
    if (Array.isArray(plan.blind)) blind = plan.blind.length;
  } catch {
    // Fall through to the countless form.
  }
  const head = 'parity-gate has not run for this tree.';
  if (sees.length === 0) return `${head} Run the parity-gate skill, or approve to commit ungated.`;
  const named = sees.join(', ');
  const body = `${sees.length} artifact${sees.length === 1 ? '' : 's'} can see these changes `
    + `(${named}); ${blind} structurally blind.`;
  const full = `${head} ${body} Run the parity-gate skill, or approve to commit ungated.`;
  return full.length <= REASON_CAP ? full : `${full.slice(0, REASON_CAP - 1)}…`;
}

/**
 * Reads the whole of stdin.
 *
 * @returns {string} the payload, or an empty string when there is none
 */
function readStdin() {
  try {
    return fs.readFileSync(0, 'utf8');
  } catch {
    return '';
  }
}

function main() {
  let payload;
  try {
    payload = JSON.parse(readStdin());
  } catch {
    return; // Not a payload we understand.
  }

  const command = payload?.tool_input?.command;
  if (typeof command !== 'string' || !runsGitCommit(command)) return;

  // Self-scope: registered in this repo's settings, but a cwd elsewhere means the commit is not
  // this repo's and this repo's store has nothing to say about it.
  const cwd = payload.cwd ? path.resolve(payload.cwd) : REPO;
  if (cwd !== REPO && !cwd.startsWith(REPO + path.sep)) return;

  const python = findPython();
  if (!python) return;

  const done = spawnSync(
    python,
    ['scripts/parity', 'plan', '--changed-from-git', '--gate-exit', '--quiet'],
    { cwd: REPO, timeout: TIMEOUT_MS, encoding: 'utf8', env: { ...process.env, PYTHONUTF8: '1' } },
  );

  // Every one of these is a reason to stay silent: a spawn failure, a timeout, or any status the
  // contract does not name. Only the one code means "seen and not gated".
  if (done.error || done.status === null) return;
  if (done.status === ALREADY_GATED || done.status !== SEEN_UNGATED) return;

  process.stdout.write(JSON.stringify({
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'ask',
      permissionDecisionReason: buildReason(),
    },
  }));
}

try {
  main();
} catch {
  // The last fail-open: nothing this hook can do is worth failing a commit over.
}
process.exit(0);
