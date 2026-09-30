"""Audit who wrote the production code: list every change Claude made under src/main/.

The project rule is that Robert writes all production code (src/main/) and Claude coaches (see
CLAUDE.md, "Who writes what"). Git can't show that on its own: Claude ran most `git commit`s and
added its Co-Authored-By trailer to them. The session transcripts can, because every file Claude
edited appears in them as a tool call. This script reads the committed transcripts
(docs/ai-sessions/*/raw/*.jsonl) and prints every Claude change to src/main/, with:

  - when it happened and in which session;
  - the file;
  - whether it changed code or only comments/KDoc (each edit is widened to whole lines using the
    file as committed just before it, comments are stripped, and the rest is compared);
  - the last thing Robert typed before it, so the reader can see whether he asked for it.

Shell commands that might write under src/main/ (redirects, sed -i, rm, ...) are listed too, for a
manual look. Edits Robert made in his editor never appear in the transcripts: anything under
src/main/ that isn't listed here, he typed.

    python scripts/authorship_audit.py
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TRANSCRIPTS = ROOT / "docs" / "ai-sessions"
FILE_TOOLS = {"Edit", "Write", "MultiEdit", "NotebookEdit"}
SHELL_TOOLS = {"Bash", "PowerShell"}
SHELL_WRITE = re.compile(
    r">>?\s*\S*src/main/"
    r"|sed\s+-i[^|;&]*src/main/"
    r"|\b(Set-Content|Add-Content|Out-File|Remove-Item|Move-Item|Copy-Item|WriteAllText|rm|mv|cp|touch)\b[^|;&]*src/main/"
    r"|open\([^)]*src/main/[^)]*,\s*['\"][wa]"
)
# Messages in the transcript that Robert didn't type: agent reports, hook feedback, notifications.
NOT_ROBERT = ("Another Claude session sent a message", "Stop hook feedback", "[SYSTEM NOTIFICATION")
# A slash command arrives as the skill's text; its heading holds what Robert typed ("# /feature: ...").
SKILL_HEADER = re.compile(r"^# (/[\w-]+.*)$", re.M)


@dataclass
class Change:
    when: str
    session: str
    target: str
    kind: str
    request: str


def strip_comments(text: str, suffix: str) -> str:
    """Remove comments and blank lines, so two versions can be compared as code."""
    if suffix in (".kt", ".kts", ".java"):
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        text = re.sub(r"//[^\n]*", "", text)
        lines = [line for line in text.splitlines() if not line.strip().startswith("*")]
    elif suffix in (".yml", ".yaml"):
        lines = re.sub(r"#[^\n]*", "", text).splitlines()
    elif suffix == ".sql":
        lines = re.sub(r"--[^\n]*", "", text).splitlines()
    else:
        lines = text.splitlines()
    return "\n".join(line.rstrip() for line in lines if line.strip())


def file_before(relative: str, when: str) -> str | None:
    """The file as last committed before `when`, or None if git has no version of it yet."""
    sha = subprocess.run(
        ["git", "log", "-1", "--format=%H", f"--before={when}", "--", relative],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
    ).stdout.strip()
    if not sha:
        return None
    shown = subprocess.run(
        ["git", "show", f"{sha}:{relative}"], cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
    )
    return shown.stdout if shown.returncode == 0 else None


def whole_lines(old: str, new: str, source: str | None) -> tuple[str, str]:
    """Widen an edit to the full lines it touches, so a comment fragment keeps its `//` or `*`.

    An Edit's old_string can start or end mid-line. The file as committed just before the edit
    supplies the rest of those lines; if the fragment isn't in it, the fragment is used as is.
    """
    if not source or not old or old not in source:
        return old, new
    start = source.index(old)
    line_start = source.rfind("\n", 0, start) + 1
    end = start + len(old)
    line_end = source.find("\n", end)
    line_end = len(source) if line_end == -1 else line_end
    before, after = source[line_start:start], source[end:line_end]
    return before + old + after, before + new + after


def classify(tool: str, args: dict, relative: str, when: str) -> str:
    if tool == "Write":
        return "new file / full rewrite"
    suffix = Path(relative).suffix
    source = file_before(relative, when)
    for edit in args.get("edits") or [args]:
        old, new = whole_lines(edit.get("old_string", ""), edit.get("new_string", ""), source)
        if strip_comments(old, suffix) != strip_comments(new, suffix):
            return "code"
    return "comments/KDoc only"


def user_text(entry: dict) -> str | None:
    """What Robert typed, or None for tool results and messages he didn't write."""
    content = (entry.get("message") or {}).get("content")
    if isinstance(content, str):
        text = content
    elif isinstance(content, list):
        texts = [c.get("text", "") for c in content if c.get("type") == "text"]
        if not texts:
            return None
        text = texts[0]
    else:
        return None
    text = text.strip()
    if not text or text.startswith("<") or text.startswith(NOT_ROBERT):
        return None
    if text.startswith("Base directory for this skill"):
        skill = SKILL_HEADER.search(text)
        return skill.group(1).strip() if skill else None
    return " ".join(text.split())


def scan(path: Path) -> tuple[list[Change], list[Change]]:
    edits, shell = [], []
    last_request = "(none yet)"
    session = path.stem
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            entry = json.loads(line)
        except json.JSONDecodeError:
            continue
        if entry.get("type") == "user":
            text = user_text(entry)
            if text:
                last_request = text
            continue
        if entry.get("type") != "assistant":
            continue
        timestamp = entry.get("timestamp") or ""
        when = timestamp[:16].replace("T", " ")
        for block in (entry.get("message") or {}).get("content") or []:
            if not isinstance(block, dict) or block.get("type") != "tool_use":
                continue
            tool, args = block.get("name"), block.get("input") or {}
            if tool in FILE_TOOLS:
                target = str(args.get("file_path") or args.get("notebook_path") or "").replace("\\", "/")
                if "/src/main/" in target or target.startswith("src/main/"):
                    relative = "src/main/" + target.split("src/main/", 1)[1]
                    kind = classify(tool, args, relative, timestamp)
                    edits.append(Change(when, session, relative, kind, last_request))
            elif tool in SHELL_TOOLS:
                command = str(args.get("command", "")).replace("\\", "/")
                if SHELL_WRITE.search(command):
                    shell.append(Change(when, session, command.splitlines()[0][:120], "shell", last_request))
    return edits, shell


def cell(text: str, limit: int = 140) -> str:
    text = text if len(text) <= limit else text[: limit - 1] + "…"
    return text.replace("|", "\\|")


def main() -> int:
    files = sorted(TRANSCRIPTS.glob("*/raw/*.jsonl"))
    edits, shell = [], []
    for path in files:
        e, s = scan(path)
        edits += e
        shell += s

    code = [c for c in edits if c.kind != "comments/KDoc only"]
    print("# Authorship audit: Claude's changes under src/main/\n")
    print(f"Scanned {len(files)} session transcripts in `docs/ai-sessions/`.\n")
    print(f"- **{len(edits)}** file edits by Claude under `src/main/`: "
          f"{len(code)} changed code, {len(edits) - len(code)} changed only comments/KDoc.")
    print(f"- **{len(shell)}** shell commands that might write under `src/main/` (check by hand).")
    print("- Anything else under `src/main/` was typed by Robert in his editor.\n")

    if edits:
        print("| When (UTC) | Session | File | Change | Robert's last message before it |")
        print("| --- | --- | --- | --- | --- |")
        for c in edits:
            print(f"| {c.when} | {c.session} | `{c.target}` | {c.kind} | {cell(c.request)} |")
        print()
    if shell:
        print("## Shell commands to check\n")
        print("| When (UTC) | Session | Command | Robert's last message before it |")
        print("| --- | --- | --- | --- |")
        for c in shell:
            print(f"| {c.when} | {c.session} | `{cell(c.target, 100)}` | {cell(c.request)} |")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
