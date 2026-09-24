"""Hook script that keeps Claude from writing Robert's production code.

Robert writes everything under src/main/ himself; Claude writes tests, coaches and reviews
(see "Who writes what" in CLAUDE.md). Wired up in .claude/settings.json as a PreToolUse hook
(python scripts/code_guard.py).

It never silently allows or silently blocks. Instead it answers "ask", so Claude Code shows
Robert a permission prompt with the reason, and the edit happens only if he approves it:

  - Edit/Write/MultiEdit/NotebookEdit of a file under src/main/
  - a Bash or PowerShell command that mentions src/main/ and looks like it writes, moves or
    deletes files (redirects, sed -i, rm/mv/cp, Set-Content, git checkout/restore, scripts...)

Read-only commands (cat, grep, ls, git diff/log/status, sed -n, ...) pass through. The shell
check is a best-effort pattern match, not a sandbox; the rule itself lives in CLAUDE.md.

Claude Code passes the hook payload as JSON on stdin. The script replies with
JSON on stdout (see https://docs.claude.com/en/docs/claude-code/hooks).
"""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

PROTECTED_DIR = "src/main/"
FILE_TOOLS = {"Edit", "Write", "MultiEdit", "NotebookEdit"}
SHELL_TOOLS = {"Bash", "PowerShell"}

WRITES = re.compile(
    r">"                                                    # any redirect
    r"|\bsed\s+(?:-\w*\s+)*-i"                              # sed -i
    r"|\b(?:rm|rmdir|mv|cp|touch|tee|mkdir|ln|truncate|patch)\b"
    r"|\b(?:Set-Content|Add-Content|Out-File|Clear-Content|New-Item|Remove-Item|Move-Item|Copy-Item|Rename-Item)\b"
    r"|\bgit\s+(?:-C\s+\S+\s+)?(?:checkout|restore|rm|mv|stash|reset|apply|clean)\b"
    r"|\b(?:python3?|py|node|perl|ruby)\b",                 # scripts can write anything
    re.IGNORECASE,
)

REASON = (
    "CODE GUARD: Robert writes the production code under src/main/ himself; Claude writes tests "
    "and coaches (CLAUDE.md, 'Who writes what'). Approve only if Robert explicitly asked Claude "
    "to make this specific change."
)


def normalize(text: str) -> str:
    return text.replace("\\", "/")


def touches_src_main(path: str, repo: Path) -> bool:
    try:
        rel = Path(path).resolve().relative_to(repo.resolve()).as_posix()
    except ValueError:
        return False
    return rel.startswith(PROTECTED_DIR)


def ask() -> int:
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "ask",
            "permissionDecisionReason": REASON,
        }
    }))
    return 0


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except json.JSONDecodeError:
        return 0
    repo = Path(os.environ.get("CLAUDE_PROJECT_DIR") or payload.get("cwd") or Path(__file__).resolve().parent.parent)
    tool = payload.get("tool_name", "")
    tool_input = payload.get("tool_input") or {}

    if tool in FILE_TOOLS:
        path = tool_input.get("file_path") or tool_input.get("notebook_path") or ""
        if path and touches_src_main(path, repo):
            return ask()
    if tool in SHELL_TOOLS:
        command = normalize(tool_input.get("command", ""))
        if PROTECTED_DIR in command and WRITES.search(command):
            return ask()
    return 0


if __name__ == "__main__":
    sys.exit(main())
