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
 *   2. It is silent unless the next act is a commit AND the toolkit came back with something worth
 *      saying: artifacts see this tree and no compare verdict covers this exact tree state, or the
 *      map could not answer at all. A tree nothing sees and a tree already gated are both silence,
 *      and the second is what stops it firing on a tree somebody has already gated.
 *   3. It FAILS OPEN. A missing interpreter, a crash, a timeout, an unparseable payload - every one
 *      of them exits 0 in silence. A broken hook must never block work, so there is exactly one
 *      path in this file that writes to stdout and every other path returns quietly.
 *
 * It runs no measurement. It shells to `python scripts/parity plan --gate-exit`, which reads
 * `blindness.json`, git's changed set and the newest `_run/last-verdict.json` under
 * `cache/parity/`, and answers in its exit code:
 * 0 nothing sees the change, 10 seen and ungated, 20 already gated for this tree, 5 a changed path
 * that no blindness rule and no `no_reach` glob covers. That is the same reach resolution the skill
 * and a human get, so there is one implementation of it and this file contains none of it.
 *
 * The refusal is prompted on rather than folded into the fail-open silence, because it is the map
 * ADMITTING it cannot answer for a path family, and reporting that as the same silence a fully
 * gated tree reports is the one indistinguishable pair here. The noise it makes is self-limiting:
 * writing the rule or the `no_reach` glob is what stops it.
 */

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

/**
 * The two statuses this hook speaks on: seen and ungated, and the map refusing to answer.
 *
 * They are the whole of what it reads. `plan --gate-exit` also answers 0 for a tree nothing sees
 * and 20 for one a compare verdict already covers, and both of those are silence here - as is any
 * status the contract does not name.
 */
const SEEN_UNGATED = 10;
const REFUSED = 5;

/**
 * The tools whose payloads this hook decides, read off the payload rather than taken on trust.
 *
 * The matcher in `settings.json` is one string, the body reads `tool_input.command` and `cwd`, and
 * every shell tool supplies both - so which tool a payload came from was decided entirely by that
 * matcher, and a commit issued through the other shell was indistinguishable from nothing seeing
 * the change. A payload naming no tool at all - which a hand-written fixture may - is decided on
 * its command alone.
 */
const SHELL_TOOLS = new Set(['Bash', 'PowerShell']);

/** Hard ceiling on the child, below the 20 s the hook is registered with. */
const TIMEOUT_MS = 15000;

/**
 * Ceiling on the WHOLE prompt, which is the only length anything downstream sees.
 *
 * What each branch may spend on its variable half is derived from it rather than typed beside it:
 * a second number is a second budget, and the two branches here already carry different fixed text.
 * Capping the assembled sentence instead dropped the call to action first, because it is what the
 * sentence ends with - at a reach of eleven artifacts, which one shipped rule answers with, the
 * prompt ended mid-list and said nothing about what to do.
 */
const REASON_CAP = 300;

/** How many artifacts the prompt spells before it counts the rest. */
const NAMED_ARTIFACTS = 3;

/** The repo this hook belongs to: two levels above `.claude/hooks/`. */
const REPO = path.resolve(__dirname, '..', '..');

/**
 * The working root every child runs in, which is the hook's own and no other.
 *
 * `plan` rewrites `<root>/_run/plan.json` before it answers and `parityCapture` reads the plan in
 * the DEFAULT root to decide what to capture, so a detector sharing that slot rewrote the plan a
 * following bare capture would have run - to the reach of the tree at commit time, which for a
 * clean tree is nothing at all. Relative and under `cache/`, which is what the toolkit requires of
 * any working root. Every root directly under `cache/parity/` is searched for a verdict, this one
 * among them, so nothing is lost by moving out of the default one.
 */
const HOOK_ROOT = 'cache/parity/hook';

/** Where the child leaves the plan it just resolved, which is under the hook's own root. */
const PLAN_FILE = path.join(REPO, ...HOOK_ROOT.split('/'), '_run', 'plan.json');

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
 * Assembles a prompt out of its three parts, spending what is left of the budget on the middle one.
 *
 * The head and the call to action are fixed text and are measured, so the room the middle may take
 * is whatever they and the two joining spaces leave - which is why neither branch can overrun the
 * cap and why neither has to know the other's fixed text. The middle carries its own terminating
 * punctuation, so a truncation replaces that with the ellipsis rather than printing both.
 *
 * @param {string} head what the hook is saying
 * @param {string} detail the half that grows with the answer, its own sentence
 * @param {string} act what to do about it, which every prompt ends with
 * @returns {string} the assembled reason, at most `REASON_CAP` characters
 */
function prompt(head, detail, act) {
  const room = REASON_CAP - head.length - act.length - 2;
  const middle = detail.length <= room ? detail : `${detail.slice(0, room - 1)}…`;
  return `${head} ${middle} ${act}`;
}

/**
 * Builds the one-line reason from the plan the child just wrote.
 *
 * Read from disk rather than recomputed, so the artifact names in the prompt are the same names the
 * skill will print. A missing or unreadable plan degrades to a prompt with no counts rather than to
 * no prompt: the exit code already said the gate has not run, and that is the part worth saying.
 *
 * The list is what gets truncated when the answer is wide - the first few names and a count of the
 * rest - because the names are a summary of a plan that is on disk in full, and the sentence after
 * them is the only thing here telling a reader what to do.
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
  const act = 'Run the parity-gate skill, or approve to commit ungated.';
  if (sees.length === 0) return `${head} ${act}`;
  const named = sees.length <= NAMED_ARTIFACTS ? sees.join(', ')
    : `${sees.slice(0, NAMED_ARTIFACTS).join(', ')}, +${sees.length - NAMED_ARTIFACTS} more`;
  const body = `${sees.length} artifact${sees.length === 1 ? '' : 's'} can see these changes `
    + `(${named}); ${blind} structurally blind.`;
  return prompt(head, body, act);
}

/**
 * Builds the reason for a refusal out of the message the child wrote to stderr.
 *
 * Quoted rather than re-derived: that message names the uncovered paths, and reach is resolved in
 * the toolkit and nowhere else. The action it asks for is the one that makes this prompt stop.
 *
 * @param {string} stderr the child's error stream
 * @returns {string} the permission-decision reason
 */
function refusalReason(stderr) {
  const message = String(stderr || '').split(/\r?\n/).map((line) => line.trim()).find(Boolean);
  const head = 'parity-gate cannot resolve the reach of this tree.';
  const act = 'Add it to blindness.json, or approve to commit ungated.';
  const body = (message || 'a changed path is covered by no blindness rule').replace(/\.+$/, '');
  return prompt(head, `${body}.`, act);
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

  const tool = payload?.tool_name;
  if (typeof tool === 'string' && !SHELL_TOOLS.has(tool)) return;

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
    ['scripts/parity', '--root', HOOK_ROOT, 'plan', '--changed-from-git', '--gate-exit', '--quiet'],
    { cwd: REPO, timeout: TIMEOUT_MS, encoding: 'utf8', env: { ...process.env, PYTHONUTF8: '1' } },
  );

  // Every one of these is a reason to stay silent: a spawn failure, a timeout, or any status the
  // contract does not name. Two codes speak: seen and not gated, and the map refusing to answer.
  if (done.error || done.status === null) return;
  if (done.status !== SEEN_UNGATED && done.status !== REFUSED) return;

  process.stdout.write(JSON.stringify({
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'ask',
      permissionDecisionReason:
        done.status === REFUSED ? refusalReason(done.stderr) : buildReason(),
    },
  }));
}

try {
  main();
} catch {
  // The last fail-open: nothing this hook can do is worth failing a commit over.
}
process.exit(0);
