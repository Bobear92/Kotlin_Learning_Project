---
name: feature
description: Build one feature of the inventory API end to end in teaching mode, covering contract check, approved plan, tests and implementation, study notes and docs via /sync-docs, doc review and hand-off to /walkthrough. Use whenever Robert asks to add or change API behavior.
argument-hint: <what to build, e.g. "POST /inventory/{skuId}">
---

# /feature: $ARGUMENTS

Follow these steps in order. Do not skip one because the feature looks small.

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

Give a short plan covering the files to create or change, each one's layer (controller / service /
repository / model / config / test), and the Kotlin and Spring concepts this feature will
introduce for the first time. That list becomes the agenda for the walkthrough.
**Stop and wait for Robert to approve the plan.**

## 3. Tests and implementation

- Write the tests alongside the code: at least one per acceptance criterion, covering the error
  paths as well as the happy path.
- Follow the documentation standard in `CLAUDE.md`: short KDoc, one-line `// Why:` and
  `// Contract:` comments, and no teaching comments in code. Prefer idiomatic Kotlin; when an
  idiom is dense, keep it and explain it in the file's study notes rather than writing
  Java-style Kotlin.
- The PostToolUse hook reports missing KDoc after each edit. Fix those gaps immediately.

## 4. Build and test

Run the Gradle build and tests (`./gradlew build` or `gradlew.bat build`). Report the result
truthfully. If something fails, fix it and explain the failure. The failure is often
the most useful thing to learn from.

## 5. Documentation

Run `/sync-docs`. It writes or updates the study notes for every changed file, the concepts
index, the README (endpoint status, curl examples, decision records) and the TODO, then runs
the `doc-reviewer` agent until it returns `PASS`. Tell Robert what the reviewer caught.

## 6. Check the docs gate

`python scripts/check_notes.py` must print nothing. (The Stop hook enforces this too.)

## 7. Hand-off

Summarize what was built. Propose a commit message (small and descriptive, ending with the
attribution line from the system reminder), and commit only if Robert says so. Finish with:
"Ready for `/walkthrough`, then `/ship`." Do not start another feature in this turn.
