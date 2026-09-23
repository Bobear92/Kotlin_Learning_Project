---
name: sync-docs
description: Bring the project documentation in line with the code, covering the per-file study notes in docs/learning/notes/, the concepts index, the README and the TODO. Use after any code change, when the check_notes Stop hook reports stale or missing notes, and as a step of /feature.
argument-hint: "[files to sync; default: everything changed on this branch]"
---

# /sync-docs $ARGUMENTS

The code stays clean. The teaching lives in `docs/learning/notes/`, one notes file per source
file. This skill keeps every piece of documentation truthful after a code change. **Wrong
documentation is worse than missing documentation**, because Robert will learn from it.

## 1. Find what changed

If `$ARGUMENTS` names files, use those. Otherwise:

```sh
python scripts/check_notes.py            # stale / missing / orphan notes
git diff --name-only main...HEAD         # committed on this branch
git status --porcelain -uall             # uncommitted
```

Use `docs/learning/notes/README.md` to map each source file to its notes path.

## 2. Update each notes file

For each changed source file, read the **whole** source file, then its notes file, and edit
the notes to match. Follow the format in `docs/learning/notes/README.md`:

- **Role** section: where the file sits in controller → service → repository, and why it exists.
- **Walkthrough**: one `###` section per small chunk (about 5–20 lines), in file order. Quote the
  chunk *exactly as it is in the source* (no line numbers), then explain it under the
  `**Kotlin:**`, `**Spring:**`, `**Why:**` and `**Contract:**` labels:
  - every Kotlin feature a Java developer or Kotlin beginner wouldn't recognize, with the
    Java equivalent where it helps;
  - Spring behavior the code doesn't show (what an annotation causes, when objects are
    created, what runs before and after);
  - each design decision and the alternative we rejected;
  - for contract rules, the exact part of `src/main/resources/openapi/inventory.yaml` it satisfies.
- Explain a concept in full in the first notes file where it appears; elsewhere, link to that
  file instead of repeating it.
- **Check your understanding**: 1–3 questions that make Robert predict behavior. Don't include answers.
- Remove sections for code that no longer exists, and re-quote chunks that changed.
- Set `Last synced with code:` to today's date.
- A new source file gets a new notes file; a deleted one gets its notes deleted. A moved file
  gets its notes moved.
- Every source file's first line is a link to its notes, in its own comment syntax
  (`// Study notes: docs/learning/notes/main/InventoryApplication.kt.md`; `#` for YAML, `--` for SQL).
  Add it to new files, and fix it when a file moves.

If a change truly needs no new explanation (for example a rename), still check that every quote
in the notes matches the code, then bump the date.

## 3. Keep the code comments slim

While reading the source, check it meets the in-code standard in `CLAUDE.md`: short KDoc on every
non-private declaration, one-line `// Why:` and `// Contract:` comments, and no teaching
`// Kotlin:` or `// Spring:` comments. Move any long explanation you find into the notes.

## 4. Update the indexes and project docs

- `docs/learning/kotlin-concepts.md`: add a row for each concept appearing for the first time,
  linking to the notes file that explains it. No duplicate rows.
- `README.md`: update anything the change affected: the endpoint status table, build/run
  commands, project layout, tech stack and versions, open decisions and decision records.
- `.claude/TODO.md`: check off finished items (with the date) and add any new ones.

## 5. Verify

1. Run `python scripts/check_notes.py`. It must print nothing and exit 0.
2. Launch the `doc-reviewer` agent on the changed files. Fix every accuracy finding, and rerun
   it until it returns `PASS`.
3. Tell Robert in a few lines which docs changed and what the reviewer caught.
