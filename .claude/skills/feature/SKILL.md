---
name: feature
description: Build one feature of the inventory API end to end in teaching mode, covering contract check, approved plan, tests and implementation with full comments, doc review, README update and hand-off to /walkthrough. Use whenever Robert asks to add or change API behavior.
argument-hint: <what to build, e.g. "POST /inventory/{skuId}">
---

# /feature: $ARGUMENTS

Follow these steps in order. Do not skip one because the feature looks small.

## 0. Gate: previous walkthrough done?

Check the newest file in `docs/learning/walkthroughs/`. If there are commits touching
`src/` after the commit it records, a walkthrough is still owed. Tell Robert and suggest
running `/walkthrough` first. Continue only if he says to.

## 1. Pin the behavior to the contract

Read `docs/open_api/inventory.yaml` and quote the exact part this feature implements.
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
- Follow the commenting standard in `CLAUDE.md` as you write, not as a later pass. Use the
  `// Kotlin:`, `// Spring:`, `// Why:` and `// Contract:` tags. Prefer idiomatic Kotlin, and when
  an idiom is dense, keep it and explain it in a comment rather than writing Java-style Kotlin.
- The PostToolUse hook reports missing KDoc after each edit. Fix those gaps immediately.

## 4. Build and test

Run the Gradle build and tests (`./gradlew build` or `gradlew.bat build`). Report the result
truthfully. If something fails, fix it and explain the failure. The failure is often
the most useful thing to learn from.

## 5. Documentation review

Launch the `doc-reviewer` agent on the changed files. Fix every finding in categories 1–6 and
rerun the agent until it returns `PASS`. Tell Robert what it caught.

## 6. README

If the feature changes how to build, run or call the API, or settles an open decision,
update `README.md` (endpoint status, curl examples, decision record).

## 7. Hand-off

Summarize what was built. Propose a commit message (small and descriptive, ending with the
attribution line from the system reminder), and commit only if Robert says so. Finish with:
"Ready for `/walkthrough`, then `/ship`." Do not start another feature in this turn.
