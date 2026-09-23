"""Hook script that keeps all work off `main`.

Wired up in .claude/settings.json for two hook events:

  SessionStart  (python scripts/branch_guard.py session-start)
      If the repo is on `main`, tells Claude to ask Robert what this session's work
      is and create a branch before changing anything.

  PreToolUse    (python scripts/branch_guard.py pre-tool-use)
      While on `main`, denies:
        - Edit/Write/MultiEdit/NotebookEdit of files inside the repo
        - `git commit` and `git push` from Bash or PowerShell
      On any branch, denies:
        - pushing directly to main
        - squash or rebase merges (the assessment asks for full, unsquashed history)

Claude Code passes the hook payload as JSON on stdin. The script replies with
JSON on stdout (see https://docs.claude.com/en/docs/claude-code/hooks).
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

PROTECTED = "main"
FILE_TOOLS = {"Edit", "Write", "MultiEdit", "NotebookEdit"}
SHELL_TOOLS = {"Bash", "PowerShell"}

# Match the git subcommand itself (optionally after `-C <dir>`), not words inside a commit message.
GIT_SUBCOMMAND = r"\bgit\s+(?:-C\s+\S+\s+)?"
GIT_COMMIT_OR_PUSH = re.compile(GIT_SUBCOMMAND + r"(commit|push)\b")
PUSH_TO_MAIN = re.compile(GIT_SUBCOMMAND + r"push\b[^;&|\n]*(?:\s|:)main\b")
SQUASH_OR_REBASE = re.compile(r"\bgh\s+pr\s+merge\b[^;&|\n]*--(squash|rebase)\b|\bgh\s+pr\s+merge\b[^;&|\n]*\s-[sr]\b")

BRANCH_HELP = (
    "Never work on main. Ask Robert what this session's work is, then create a branch "
    "from an up-to-date main: `git switch main && git pull --ff-only && git switch -c <type>/<slug>` "
    "where <type> is feature, fix, chore, docs or test (e.g. feature/get-inventory). "
    "Ship it later with /ship."
)


def current_branch(repo: Path) -> str | None:
    try:
        out = subprocess.run(
            ["git", "-C", str(repo), "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True, text=True, timeout=5,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    return out.stdout.strip() if out.returncode == 0 else None


def is_inside(path: str, repo: Path) -> bool:
    try:
        Path(path).resolve().relative_to(repo.resolve())
        return True
    except ValueError:
        return False


def deny(reason: str) -> int:
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }
    }))
    return 0


def session_start(repo: Path) -> int:
    branch = current_branch(repo)
    if branch == PROTECTED:
        message = f"BRANCH GUARD: the repo is on `{PROTECTED}`. {BRANCH_HELP}"
    elif branch:
        message = f"Branch guard: working on branch `{branch}`. Ship it with /ship when done."
    else:
        return 0
    print(json.dumps({
        "hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": message}
    }))
    return 0


def pre_tool_use(repo: Path, payload: dict) -> int:
    tool = payload.get("tool_name", "")
    tool_input = payload.get("tool_input") or {}
    command = tool_input.get("command", "") if tool in SHELL_TOOLS else ""

    # Rules that apply on every branch.
    if command and PUSH_TO_MAIN.search(command):
        return deny(f"Direct pushes to `{PROTECTED}` are not allowed. Use /ship, which merges through a pull request.")
    if command and SQUASH_OR_REBASE.search(command):
        return deny("Squash and rebase merges are not allowed: the assessment asks for full, unsquashed history. Use `gh pr merge --merge`.")

    if current_branch(repo) != PROTECTED:
        return 0

    # Rules that apply only while on main.
    if tool in FILE_TOOLS:
        path = tool_input.get("file_path") or tool_input.get("notebook_path") or ""
        if path and is_inside(path, repo):
            return deny(f"You are on `{PROTECTED}`. {BRANCH_HELP}")
    if command and GIT_COMMIT_OR_PUSH.search(command):
        return deny(f"Committing or pushing on `{PROTECTED}` is not allowed. {BRANCH_HELP}")
    return 0


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    try:
        payload = json.load(sys.stdin)
    except json.JSONDecodeError:
        payload = {}
    repo = Path(os.environ.get("CLAUDE_PROJECT_DIR") or payload.get("cwd") or Path(__file__).resolve().parent.parent)
    if mode == "session-start":
        return session_start(repo)
    if mode == "pre-tool-use":
        return pre_tool_use(repo, payload)
    print(f"usage: {sys.argv[0]} session-start|pre-tool-use", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
