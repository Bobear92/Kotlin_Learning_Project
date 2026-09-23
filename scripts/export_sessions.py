"""Export Claude Code session transcripts for this project into docs/ai-sessions/.

Claude Code stores each session as JSONL under ~/.claude/projects/<project-slug>/.
This script copies them into the repo so the full AI-assisted history is committed:

  docs/ai-sessions/<date>/<date>_<nn>.md           human-readable transcript
  docs/ai-sessions/<date>/raw/<date>_<nn>.jsonl    verbatim log (emails redacted)
  docs/ai-sessions/README.md                       index of all sessions
  docs/ai-sessions/sessions.json                   session-id -> name mapping

<date> is the local date the session started and <nn> counts up from 01 each
day in start order. Names are recorded in sessions.json the first time a
session is seen and never reassigned, so they stay stable even if Claude Code
later prunes old logs from ~/.claude.

It is idempotent and safe to run repeatedly; it runs automatically via the
Stop hook in .claude/settings.json and can also be run by hand:

  python scripts/export_sessions.py
"""

from __future__ import annotations

import json
import re
import sys
from datetime import datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "docs" / "ai-sessions"
NAMES_FILE = OUT_DIR / "sessions.json"

MAX_TOOL_OUTPUT = 4000
EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
EMAIL_ALLOWLIST = {"noreply@anthropic.com"}


def project_slug(path: Path) -> str:
    # Claude Code names the project dir by replacing every non-alphanumeric char with '-'.
    return re.sub(r"[^A-Za-z0-9]", "-", str(path))


def find_session_dir() -> Path | None:
    projects = Path.home() / ".claude" / "projects"
    slug = project_slug(REPO_ROOT).lower()
    for candidate in projects.glob("*"):
        if candidate.is_dir() and candidate.name.lower() == slug:
            return candidate
    return None


def redact(text: str) -> str:
    return EMAIL_RE.sub(
        lambda m: m.group(0) if m.group(0).lower() in EMAIL_ALLOWLIST else "[redacted-email]",
        text,
    )


def strip_harness_tags(text: str) -> str:
    text = re.sub(r"<system-reminder>.*?</system-reminder>", "", text, flags=re.S)
    text = re.sub(
        r"<ide_opened_file>(.*?)</ide_opened_file>",
        lambda m: f"_[IDE context: {m.group(1).strip()}]_",
        text,
        flags=re.S,
    )
    text = re.sub(r"<ide_selection>(.*?)</ide_selection>", r"_[IDE selection]_\n```\n\1\n```", text, flags=re.S)
    return text.strip()


def fence(text: str, lang: str = "") -> str:
    ticks = "````" if "```" in text else "```"
    return f"{ticks}{lang}\n{text}\n{ticks}"


def truncate(text: str) -> str:
    if len(text) <= MAX_TOOL_OUTPUT:
        return text
    return text[:MAX_TOOL_OUTPUT] + f"\n... [truncated {len(text) - MAX_TOOL_OUTPUT} chars; see raw log]"


def tool_result_text(content) -> str:
    if isinstance(content, str):
        return content
    parts = []
    for item in content or []:
        if item.get("type") == "text":
            parts.append(item.get("text", ""))
        else:
            parts.append(f"[{item.get('type')} content]")
    return "\n".join(parts)


def render_session(records: list[dict]) -> tuple[str, dict]:
    meta = {"title": None, "start": None, "end": None, "prompts": 0, "models": set()}
    lines: list[str] = []

    for rec in records:
        rtype = rec.get("type")
        if rtype == "ai-title":
            meta["title"] = rec.get("aiTitle")
            continue
        if rtype not in ("user", "assistant"):
            continue
        ts = rec.get("timestamp")
        if ts:
            meta["start"] = meta["start"] or ts
            meta["end"] = ts

        msg = rec.get("message") or {}
        content = msg.get("content")
        if isinstance(content, str):
            content = [{"type": "text", "text": content}]

        if rtype == "user":
            if rec.get("isCompactSummary"):
                text = "\n".join(c.get("text", "") for c in content if c.get("type") == "text")
                lines.append(f"<details><summary>Context compaction summary</summary>\n\n{fence(text)}\n\n</details>\n")
                continue
            texts = [c.get("text", "") for c in content if c.get("type") == "text"]
            if texts and not rec.get("isMeta"):
                body = strip_harness_tags("\n\n".join(texts))
                if body:
                    meta["prompts"] += 1
                    lines.append(f"## User — {ts}\n\n{body}\n")
            for c in content:
                if c.get("type") == "tool_result":
                    out = truncate(tool_result_text(c.get("content")))
                    label = "Tool error" if c.get("is_error") else "Tool result"
                    lines.append(f"<details><summary>{label}</summary>\n\n{fence(out)}\n\n</details>\n")
        else:
            if msg.get("model"):
                meta["models"].add(msg["model"])
            for c in content or []:
                ctype = c.get("type")
                if ctype == "text" and c.get("text", "").strip():
                    lines.append(f"### Claude\n\n{c['text'].strip()}\n")
                elif ctype == "thinking" and c.get("thinking", "").strip():
                    lines.append(f"<details><summary>Claude reasoning</summary>\n\n{c['thinking'].strip()}\n\n</details>\n")
                elif ctype == "tool_use":
                    inp = c.get("input") or {}
                    desc = inp.get("description") or ""
                    summary = f"Tool call: {c.get('name')}" + (f" — {desc}" if desc else "")
                    body = truncate(json.dumps(inp, indent=2, ensure_ascii=False))
                    lines.append(f"<details><summary>{summary}</summary>\n\n{fence(body, 'json')}\n\n</details>\n")

    return "\n".join(lines), meta


def load_jsonl(path: Path) -> list[dict]:
    records = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                pass  # a partially written final line while the session is live
    return records


def local_date(iso_ts: str) -> str:
    return datetime.fromisoformat(iso_ts.replace("Z", "+00:00")).astimezone().strftime("%Y-%m-%d")


def first_timestamp(records: list[dict]) -> str | None:
    for rec in records:
        if rec.get("type") in ("user", "assistant") and rec.get("timestamp"):
            return rec["timestamp"]
    return None


def assign_names(sessions: list[tuple[str, str]]) -> dict[str, str]:
    """Give each new session the next <date>_<nn> name; existing names never change."""
    names = json.loads(NAMES_FILE.read_text(encoding="utf-8")) if NAMES_FILE.exists() else {}
    used: dict[str, int] = {}
    for name in names.values():
        date, num = name.rsplit("_", 1)
        used[date] = max(used.get(date, 0), int(num))
    for start, session_id in sorted(sessions):
        if session_id not in names:
            date = local_date(start)
            used[date] = used.get(date, 0) + 1
            names[session_id] = f"{date}_{used[date]:02d}"
    NAMES_FILE.write_text(json.dumps(dict(sorted(names.items(), key=lambda kv: kv[1])), indent=2) + "\n", encoding="utf-8")
    return names


def main() -> int:
    src = find_session_dir()
    if src is None:
        print("No Claude Code session directory found for this project.", file=sys.stderr)
        return 0

    index = []

    loaded = {}
    for jsonl in src.glob("*.jsonl"):
        records = load_jsonl(jsonl)
        start = first_timestamp(records)
        if start:
            loaded[jsonl.stem] = (jsonl, records, start)
    names = assign_names([(start, sid) for sid, (_, _, start) in loaded.items()])

    for session_id, (jsonl, records, _) in loaded.items():
        name = names[session_id]
        day_dir = OUT_DIR / name.rsplit("_", 1)[0]
        raw_dir = day_dir / "raw"
        raw_dir.mkdir(parents=True, exist_ok=True)
        (raw_dir / f"{name}.jsonl").write_text(redact(jsonl.read_text(encoding="utf-8")), encoding="utf-8")

        # Subagent transcripts live alongside the session in <session-id>/subagents/.
        sub_dir = src / session_id / "subagents"
        subagents = sorted(sub_dir.glob("*.jsonl")) if sub_dir.is_dir() else []
        for sub in subagents:
            dest = raw_dir / name / "subagents" / sub.name
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(redact(sub.read_text(encoding="utf-8")), encoding="utf-8")

        body, meta = render_session(records)
        md_name = f"{day_dir.name}/{name}.md"
        title = meta["title"] or "Untitled session"
        header = [
            f"# {title}",
            "",
            f"- **Session ID:** `{session_id}`",
            f"- **Started:** {meta['start']}",
            f"- **Last activity:** {meta['end']}",
            f"- **Model(s):** {', '.join(sorted(meta['models'])) or 'n/a'}",
            f"- **Raw log:** [raw/{name}.jsonl](raw/{name}.jsonl)",
        ]
        for sub in subagents:
            rel = f"raw/{name}/subagents/{sub.name}"
            header.append(f"- **Subagent log:** [{sub.name}]({rel})")
        doc = "\n".join(header) + "\n\n---\n\n" + body
        (day_dir / f"{name}.md").write_text(redact(doc), encoding="utf-8")
        index.append((name, md_name, title, meta["start"], meta["prompts"]))

    index.sort()
    readme = [
        "# AI Session Transcripts",
        "",
        "Every Claude Code session used on this project, exported by",
        "[`scripts/export_sessions.py`](../../scripts/export_sessions.py).",
        "A `Stop` hook in [`.claude/settings.json`](../../.claude/settings.json) re-runs",
        "the export after each Claude turn, so these files stay current and get",
        "committed alongside the code they produced.",
        "",
        "- `<date>/*.md`: readable transcripts (prompts, responses, tool calls and results;",
        "  long tool output is truncated).",
        "- `<date>/`: one directory per day, holding that day's transcripts.",
        "- `<date>/raw/*.jsonl`: complete, unedited Claude Code logs (email addresses redacted).",
        "- `sessions.json`: maps each Claude Code session ID to its file name.",
        "",
        "Files are named `<date>_<nn>`: the local date the session started, numbered",
        "from 01 each day in the order sessions were started.",
        "",
        "| Session | Title | Started (UTC) | User prompts |",
        "|---|---|---|---|",
    ]
    for name, md_name, title, start, prompts in index:
        readme.append(f"| [{name}]({md_name}) | {title} | {start[:16].replace('T', ' ')} | {prompts} |")
    (OUT_DIR / "README.md").write_text("\n".join(readme) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
