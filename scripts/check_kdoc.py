"""PostToolUse hook: flag non-private Kotlin declarations that have no KDoc.

Claude Code runs this after every Edit/Write (see .claude/settings.json) and pipes
the tool call as JSON on stdin. If the edited file is a .kt file and a class,
interface, object, typealias or function that is not private has no KDoc, the
script prints the gaps to stderr and exits 2. Claude Code then passes that output
back to Claude, which fixes it right away.

This is a line-based heuristic, not a Kotlin parser. It skips declarations inside
function bodies (local functions and classes) and `override` members, since those
inherit their docs. In src/test only classes are checked, because backtick test
names already describe themselves.

Can also be run by hand:  python scripts/check_kdoc.py path/to/File.kt
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

MODIFIERS = (
    r"(?:(?:public|internal|protected|open|abstract|final|data|sealed|enum|annotation|"
    r"inner|value|inline|suspend|operator|infix|tailrec|external|const|lateinit|expect|actual|"
    r"private|override)\s+)*"
)
DECL_RE = re.compile(
    r"^\s*" + MODIFIERS + r"(?:fun\s+interface|class|interface|object|fun|typealias)\b"
)
FUN_RE = re.compile(r"^\s*" + MODIFIERS + r"fun\b(?!\s+interface)")
TYPE_RE = re.compile(r"^\s*" + MODIFIERS + r"(?:fun\s+interface|class|interface|object|typealias)\b")
# Only the modifiers in front of the keyword count: `class Foo(private val x: Int)` is public.
SKIP_RE = re.compile(
    r"^\s*(?:\w+\s+)*?(?:private|override)\s+" + MODIFIERS
    + r"(?:fun\s+interface|class|interface|object|fun|typealias)\b"
)
STRING_RE = re.compile(r'"(?:\\.|[^"\\])*"')
ANNOTATION_RE = re.compile(r"^\s*@")
# `) = expr` or `): Type = expr` ends a signature with an expression body (no braces).
EXPR_BODY_RE = re.compile(r"\)\s*(?::[^={]*)?=(?!=)")


def strip_code(line: str) -> str:
    """Remove string literals and // comments so braces inside them are ignored."""
    line = STRING_RE.sub('""', line)
    return line.split("//", 1)[0]


def has_kdoc(lines: list[str], index: int) -> bool:
    """True if a KDoc block ends right above lines[index], allowing annotations in between."""
    i = index - 1
    while i >= 0 and (ANNOTATION_RE.match(lines[i]) or not lines[i].strip()):
        if not lines[i].strip():
            return False  # a blank line breaks the KDoc attachment
        i -= 1
    if i < 0 or not lines[i].rstrip().endswith("*/"):
        return False
    # Walk up to the start of the block and confirm it opens with /** (KDoc), not /*.
    while i >= 0 and "/*" not in lines[i]:
        i -= 1
    return i >= 0 and "/**" in lines[i]


def find_gaps(path: Path) -> list[str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    is_test = "src/test" in path.as_posix()
    gaps: list[str] = []
    stack: list[str] = []  # one entry per open brace: "fun" or "block"
    pending_fun = False  # saw a fun signature whose body brace hasn't opened yet
    in_block_comment = False

    for n, raw in enumerate(lines):
        if in_block_comment:
            if "*/" in raw:
                in_block_comment = False
                raw = raw.split("*/", 1)[1]
            else:
                continue
        code = strip_code(raw)
        if "/*" in code:
            before, after = code.split("/*", 1)
            if "*/" not in after:
                in_block_comment = True
            code = before + (after.split("*/", 1)[1] if "*/" in after else "")

        inside_fun = "fun" in stack
        is_fun_decl = bool(FUN_RE.match(code))

        if not inside_fun and DECL_RE.match(code) and not SKIP_RE.match(code):
            if (not is_test or TYPE_RE.match(code)) and not has_kdoc(lines, n):
                gaps.append(f"{path}:{n + 1}: missing KDoc -> {raw.strip()}")

        if is_fun_decl and not inside_fun:
            pending_fun = True
        for ch in code:
            if ch == "{":
                stack.append("fun" if pending_fun or inside_fun else "block")
                pending_fun = False
            elif ch == "}" and stack:
                stack.pop()
        # Expression-bodied functions (`fun x() = ...`) never open a brace.
        if pending_fun and EXPR_BODY_RE.search(code):
            pending_fun = False

    return gaps


def main() -> int:
    if len(sys.argv) > 1:
        files = [Path(p) for p in sys.argv[1:]]
    else:
        try:
            payload = json.load(sys.stdin)
        except json.JSONDecodeError:
            return 0
        file_path = (payload.get("tool_input") or {}).get("file_path")
        if not file_path:
            return 0
        files = [Path(file_path)]

    gaps = [g for f in files if f.suffix == ".kt" and f.is_file() for g in find_gaps(f)]
    if not gaps:
        return 0
    print(
        "KDoc required on every non-private declaration (see CLAUDE.md, "
        "'Commenting and documentation standard'). Add KDoc for:\n" + "\n".join(gaps),
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    sys.exit(main())
