# AI Session Transcripts

Every Claude Code session used on this project, exported by
[`scripts/export_sessions.py`](../../scripts/export_sessions.py).
A `Stop` hook in [`.claude/settings.json`](../../.claude/settings.json) re-runs
the export after each Claude turn, so these files stay current and get
committed alongside the code they produced.

- `<date>/*.md`: readable transcripts (prompts, responses, tool calls and results;
  long tool output is truncated).
- `<date>/`: one directory per day, holding that day's transcripts.
- `<date>/raw/*.jsonl`: complete, unedited Claude Code logs (email addresses redacted).
- `sessions.json`: maps each Claude Code session ID to its file name.

Files are named `<date>_<nn>`: the local date the session started, numbered
from 01 each day in the order sessions were started.

| Session | Title | Started (UTC) | User prompts |
|---|---|---|---|
| [2026-09-23_01](2026-09-23/2026-09-23_01.md) | Nuuly assessment session history setup | 2026-09-23 13:38 | 3 |
| [2026-09-23_02](2026-09-23/2026-09-23_02.md) | Testing new entry organization method | 2026-09-23 13:51 | 2 |
| [2026-09-23_03](2026-09-23/2026-09-23_03.md) | Nuuly inventory Kotlin master class | 2026-09-23 14:05 | 8 |
| [2026-09-23_04](2026-09-23/2026-09-23_04.md) | Project structure and scaffolding | 2026-09-23 14:44 | 21 |
