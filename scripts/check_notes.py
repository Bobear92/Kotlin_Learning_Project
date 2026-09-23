"""Stop hook: every source file must have study notes that are at least as new as the code.

The code carries only short KDoc and one-line `// Why:` / `// Contract:` comments. The teaching
lives in docs/learning/notes/, one Markdown file per source file (see the README there for the
mapping). This hook keeps the two from drifting apart.

Claude Code runs it when Claude is about to end its turn (see .claude/settings.json). For each
source file it compares "when did this last change" for the file and for its notes:

  - a file with uncommitted changes (modified, staged or untracked): its modification time
  - a committed, unchanged file: the time of the last commit that touched it

If the code is newer than its notes, or the notes are missing, the script prints what's stale to
stderr and exits 2. Claude Code then keeps Claude working with that message as feedback, and
Claude runs /sync-docs. It also reports notes whose source file no longer exists.

To avoid an endless loop, it never blocks twice in a row: when Claude Code says a Stop hook
already sent Claude back to work (`stop_hook_active`), the script lets the turn end.

Can also be run by hand, for example as a pre-ship check:  python scripts/check_notes.py
(exit 1 when something is stale).
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NOTES = Path("docs/learning/notes")
PACKAGE = "com/robertwoolbright/inventory/"

# (source prefix, notes prefix, which files under the prefix count as source)
MAPPINGS = [
    (f"src/main/kotlin/{PACKAGE}", "main/", lambda p: p.endswith(".kt")),
    (f"src/test/kotlin/{PACKAGE}", "test/", lambda p: p.endswith(".kt")),
    ("src/main/resources/", "main/resources/",
     lambda p: (p.count("/") == 0 and p.startswith("application") and p.endswith((".yml", ".yaml")))
     or (p.startswith("db/migration/") and p.endswith(".sql"))),
    ("", "", lambda p: "/" not in p and (p.endswith(".gradle.kts") or p == "compose.yaml")),
]


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", check=False
    ).stdout


def notes_path(source: str) -> str | None:
    """Map a repo-relative source path to its notes path, or None if it needs no notes."""
    for src_prefix, notes_prefix, is_source in MAPPINGS:
        if source.startswith(src_prefix):
            rest = source[len(src_prefix):]
            if is_source(rest):
                return (NOTES / f"{notes_prefix}{rest}.md").as_posix()
            return None
    return None


COMMENT_PREFIX = {".kt": "//", ".kts": "//", ".yml": "#", ".yaml": "#", ".sql": "--"}


def header_line(source: str) -> str:
    """The link to its notes that every source file carries in its first lines."""
    prefix = COMMENT_PREFIX[Path(source).suffix]
    return f"{prefix} Study notes: {notes_path(source)}"


def has_header(source: str) -> bool:
    head = (ROOT / source).read_text(encoding="utf-8").splitlines()[:3]
    return header_line(source) in (line.strip() for line in head)


def dirty_paths() -> set[str]:
    """Repo-relative paths with uncommitted changes, including untracked files."""
    paths = set()
    for line in git("status", "--porcelain", "-uall").splitlines():
        path = line[3:]
        if " -> " in path:  # rename: "old -> new"
            path = path.split(" -> ", 1)[1]
        paths.add(path.strip('"'))
    return paths


def last_change(path: str, dirty: set[str]) -> float | None:
    """Seconds since the epoch when `path` last changed, or None if it doesn't exist."""
    full = ROOT / path
    if not full.is_file():
        return None
    if path in dirty:
        return full.stat().st_mtime
    committed = git("log", "-1", "--format=%ct", "--", path).strip()
    return float(committed) if committed else full.stat().st_mtime


def find_problems() -> list[str]:
    dirty = dirty_paths()
    tracked = set(git("ls-files").splitlines())
    sources = sorted(p for p in tracked | dirty if notes_path(p) and (ROOT / p).is_file())

    problems = []
    expected_notes = set()
    for source in sources:
        notes = notes_path(source)
        expected_notes.add(notes)
        source_time = last_change(source, dirty)
        notes_time = last_change(notes, dirty)
        if not has_header(source):
            problems.append(f"missing link:  {source} should start with '{header_line(source)}'")
        if notes_time is None:
            problems.append(f"missing notes: {source} -> create {notes}")
        elif source_time is not None and source_time > notes_time:
            problems.append(f"stale notes:   {source} changed after {notes}")

    notes_dir = ROOT / NOTES
    if notes_dir.is_dir():
        for md in sorted(notes_dir.rglob("*.md")):
            rel = md.relative_to(ROOT).as_posix()
            if md.name != "README.md" and rel not in expected_notes:
                problems.append(f"orphan notes:  {rel} (its source file is gone; delete or move it)")
    return problems


def main() -> int:
    hook_mode = not sys.stdin.isatty()
    if hook_mode:
        try:
            payload = json.load(sys.stdin)
        except json.JSONDecodeError:
            payload = {}
        if payload.get("stop_hook_active"):
            return 0

    problems = find_problems()
    if not problems:
        return 0
    print(
        "Study notes are out of date (see docs/learning/notes/README.md). Run /sync-docs to update "
        "them. If a change really needs no new explanation, bump the notes' 'Last synced' date.\n"
        + "\n".join(problems),
        file=sys.stderr,
    )
    return 2 if hook_mode else 1


if __name__ == "__main__":
    sys.exit(main())
