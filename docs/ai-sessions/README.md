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
| [2026-09-23_04](2026-09-23/2026-09-23_04.md) | Project structure and scaffolding | 2026-09-23 14:44 | 22 |
| [2026-09-24_01](2026-09-24/2026-09-24_01.md) | Inventory list endpoint | 2026-09-24 16:13 | 20 |
| [2026-09-24_02](2026-09-24/2026-09-24_02.md) | Continue with step 2 | 2026-09-24 17:31 | 7 |
| [2026-09-24_03](2026-09-24/2026-09-24_03.md) | Inventory item step 3 | 2026-09-24 21:03 | 23 |
| [2026-09-24_04](2026-09-24/2026-09-24_04.md) | InventoryRepository step 4 | 2026-09-25 03:47 | 8 |
| [2026-09-25_01](2026-09-25/2026-09-25_01.md) | InventoryService step 5 | 2026-09-25 14:04 | 4 |
| [2026-09-25_02](2026-09-25/2026-09-25_02.md) | Inventory item step 6 | 2026-09-25 14:59 | 29 |
| [2026-09-27_01](2026-09-27/2026-09-27_01.md) | Inventory skuId endpoint | 2026-09-27 18:09 | 10 |
| [2026-09-27_02](2026-09-27/2026-09-27_02.md) | InventoryRepository step 2 | 2026-09-27 18:54 | 6 |
| [2026-09-27_03](2026-09-27/2026-09-27_03.md) | SkuNotFoundException step 3 | 2026-09-27 19:22 | 6 |
| [2026-09-27_04](2026-09-27/2026-09-27_04.md) | InventoryService step 4 | 2026-09-27 19:49 | 4 |
| [2026-09-27_05](2026-09-27/2026-09-27_05.md) | InventoryController step 5 | 2026-09-27 20:06 | 7 |
| [2026-09-27_06](2026-09-27/2026-09-27_06.md) | InventoryExceptionHandler walkthrough | 2026-09-27 20:58 | 19 |
| [2026-09-28_01](2026-09-28/2026-09-28_01.md) | POST /inventory/{skuId} endpoint | 2026-09-28 18:07 | 15 |
| [2026-09-28_02](2026-09-28/2026-09-28_02.md) | InventoryRepository step 2 | 2026-09-28 20:55 | 12 |
| [2026-09-28_03](2026-09-28/2026-09-28_03.md) | Nuuly inventory assessment step 4 | 2026-09-28 21:36 | 5 |
| [2026-09-28_04](2026-09-28/2026-09-28_04.md) | InventoryQuantity Docker setup | 2026-09-28 21:53 | 4 |
| [2026-09-28_05](2026-09-28/2026-09-28_05.md) | AddStockOverflowException | 2026-09-28 22:04 | 4 |
| [2026-09-28_06](2026-09-28/2026-09-28_06.md) | InventoryService step 7 | 2026-09-28 22:16 | 7 |
| [2026-09-28_07](2026-09-28/2026-09-28_07.md) | Inventory controller step 8 | 2026-09-28 22:36 | 23 |
| [2026-09-29_01](2026-09-29/2026-09-29_01.md) | Walkthrough | 2026-09-29 15:45 | 34 |
| [2026-09-29_02](2026-09-29/2026-09-29_02.md) | InventoryExceptionHandler last feature | 2026-09-29 19:00 | 32 |
| [2026-09-29_03](2026-09-29/2026-09-29_03.md) | InventoryRepository step 3 | 2026-09-29 21:03 | 11 |
| [2026-09-29_04](2026-09-29/2026-09-29_04.md) | InventoryService step 5 | 2026-09-29 21:55 | 3 |
| [2026-09-29_05](2026-09-29/2026-09-29_05.md) | InventoryController step 6 | 2026-09-29 22:29 | 12 |
| [2026-09-29_06](2026-09-29/2026-09-29_06.md) | InventoryService walkthrough | 2026-09-29 23:31 | 26 |
| [2026-09-30_01](2026-09-30/2026-09-30_01.md) | Testing feature | 2026-09-30 15:37 | 38 |
| [2026-10-01_01](2026-10-01/2026-10-01_01.md) | Happy and unhappy calls for 4 endpoints | 2026-10-01 17:07 | 7 |
