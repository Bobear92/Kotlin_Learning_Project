# Study notes

One notes file per source file. The code stays clean, with short KDoc and one-line
`// Why:` / `// Contract:` comments. The teaching lives here.

## Where each file's notes live

The notes tree mirrors the source tree, with the long package prefix dropped:

| Source | Notes |
| --- | --- |
| `src/main/kotlin/com/robertwoolbright/inventory/<path>.kt` | `docs/learning/notes/main/<path>.kt.md` |
| `src/test/kotlin/com/robertwoolbright/inventory/<path>.kt` | `docs/learning/notes/test/<path>.kt.md` |
| `src/main/resources/<path>` (`application*.yml`, `db/migration/*.sql`) | `docs/learning/notes/main/resources/<path>.md` |
| `<root file>` (`*.gradle.kts`, `compose.yaml`) | `docs/learning/notes/<root file>.md` |

## Finding the notes from the code

Every source file's first line points to its notes, written in that file's comment syntax:

```kotlin
// Study notes: docs/learning/notes/main/InventoryApplication.kt.md
```

In VS Code, select the path and press **Ctrl+P** to open it. (The editor doesn't turn relative
paths in comments into links. IntelliJ usually does.) `check_notes.py` checks that the header is
there and correct, so a moved or renamed file can't keep pointing to the wrong note.

## Format

```markdown
# <FileName>

> Source: [path](link) · Last synced with code: YYYY-MM-DD

## Role
Where this file sits (controller → service → repository) and why it exists.

## Walkthrough
### <short heading per chunk>
​```kotlin
<the chunk, quoted from the source>
​```
**Kotlin:** language features, with the Java equivalent when that helps.
**Spring:** framework behavior the code doesn't show.
**Why:** the decision, and the alternative we rejected.
**Contract:** the part of inventory.yaml this satisfies.

## Check your understanding
- Questions to answer without looking back.
```

Code is **quoted, not referenced by line number**, so the notes survive edits elsewhere in
the file, and a stale quote is easy to spot (`doc-reviewer` checks for them). Quotes are
compared ignoring indentation, and a line containing only `...` marks lines left out on purpose.

## Keeping them in sync

- `/sync-docs` updates the notes and the `Study notes:` header lines (plus the concepts index, README and TODO) for whatever changed.
- A `Stop` hook ([scripts/check_notes.py](../../../scripts/check_notes.py)) won't let Claude finish
  a turn while any source file has been changed more recently than its notes file.
- If a code change truly needs no new explanation (a rename, say), bumping the
  "Last synced" date is the deliberate way to mark the notes as reviewed.
