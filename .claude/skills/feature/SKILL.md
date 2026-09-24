---
name: feature
description: Coach Robert through one feature of the inventory API. Claude pins the contract, plans, and writes failing tests; Robert writes the production code while Claude coaches and reviews; then study notes and docs via /sync-docs, doc review and hand-off to /walkthrough. Use whenever Robert asks to add or change API behavior.
argument-hint: <what to build, e.g. "POST /inventory/{skuId}">
---

# /feature: $ARGUMENTS

Follow these steps in order. Do not skip one because the feature looks small.

**Robert writes the production code; you coach.** Never create or edit files under `src/main/`
unless Robert explicitly asks for that specific edit (see "Who writes what" in `CLAUDE.md`).
Your deliverables are the contract check, the plan, the tests, hints, reviews and the docs.

## 0. Gate: previous walkthrough done?

Check the newest file in `docs/learning/walkthroughs/`. If there are commits touching
`src/` after the commit it records, a walkthrough is still owed. Tell Robert and suggest
running `/walkthrough` first. Continue only if he says to.

## 1. Pin the behavior to the contract

Read `src/main/resources/openapi/inventory.yaml` and quote the exact part this feature implements.
List the acceptance criteria as a checklist: method + path, request schema and validation,
every response status with its content type (`application/json` vs `text/plain`), and body shape.
List any ambiguity the contract leaves open and ask Robert how to resolve it.

## 2. Plan, then wait for approval

Give a short plan covering:

- the files Robert will create or change, each one's layer (controller / service / repository /
  model / migration), and what each needs to do, described in words and signatures, not code;
- the test files you will write, and which acceptance criterion each test covers;
- the Kotlin and Spring concepts this feature introduces for the first time. That list is both
  what you'll coach him through and the agenda for the walkthrough.

**Stop and wait for Robert to approve the plan.**

## 3. Write the failing tests

- Write the tests under `src/test/` first: at least one per acceptance criterion, covering the
  error paths as well as the happy path. They are the spec for Robert's code.
- Tests reference the classes and functions named in the plan. The build won't compile until he
  creates them; that's expected. Tell him which compile errors and failures to expect.
- Follow the documentation standard in `CLAUDE.md` for the test files (KDoc, `// Why:`, notes
  header line).

## 4. Robert writes the code; you coach

- Create the marker file `.claude/coding-in-progress` (gitignored). While it exists, the
  `check_notes` Stop hook skips `src/main/`, so it won't push you to document half-finished code.
- Hand over a short checklist: which file to write next and what it must do, in request-path
  order (usually migration → model → repository → service → controller). Suggest a first
  test to make pass.
- **Hints go in increasing order**: the concept → the API or annotation to look up → the shape
  of the code in words → a code snippet, but only when he asks, and in chat for him to type.
  Never write the file for him.
- Explain concepts when he asks or when you see him stuck on one, with the Java equivalent
  when that helps.
- Run the build (`.\gradlew.bat build` in PowerShell) when he asks, and explain failures in terms of what his
  code does. Report results truthfully.
- The KDoc hook only runs on your edits, so remind him that every non-private declaration he
  writes needs KDoc, plus the `// Study notes:` header line.
- While his code is in progress, its study notes are expected to lag. Don't write notes for
  half-finished code.
- If you ever try to edit `src/main/`, the `code_guard` hook asks Robert for approval. Don't
  request that unless he asked for that specific edit.

## 4b. Review

When the tests pass, review his code: contract conformance, idiomatic Kotlin (flag
Java-style Kotlin and explain the idiom), the documentation standard, and anything the tests
don't catch. Explain each point; he makes the changes. Rerun the build after his fixes.

## 5. Documentation

Once the build is green and the review is done, run `/sync-docs`. It writes or updates the
study notes for every changed file (describing the code Robert wrote), the concepts
index, the README (endpoint status, curl examples, decision records) and the TODO, then runs
the `doc-reviewer` agent until it returns `PASS`. Tell Robert what the reviewer caught.

## 6. Check the docs gate

`python scripts/check_notes.py` must print nothing. (The Stop hook enforces this too.)

## 7. Hand-off

Summarize what Robert built and what he learned along the way. Propose a commit message (small and descriptive, ending with the
attribution line from the system reminder), and commit only if Robert says so. Finish with:
"Ready for `/walkthrough`, then `/ship`." Do not start another feature in this turn.
