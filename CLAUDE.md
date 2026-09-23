# CLAUDE.md

Project memory for Claude Code. Loaded automatically at the start of every session.

## What this project is

A take-home assessment for Nuuly: a small inventory REST API (receive stock by SKU,
process purchases, report inventory levels). Full brief: [docs/Nuuly_Assessment_Instructions.txt](docs/Nuuly_Assessment_Instructions.txt).

**It is also a Kotlin master class.** Robert is learning Kotlin and wants to use this
project to learn it properly, whatever happens with the interview. Every piece of
code must teach as well as work. Treat "can Robert explain every line of this in the
follow-up interview?" as a requirement alongside passing the tests.

## Source of truth

- **API contract:** [src/main/resources/openapi/inventory.yaml](src/main/resources/openapi/inventory.yaml). Paths, status codes,
  content types and schemas must match it exactly. Error responses (400/404) are
  `text/plain` strings, not JSON. `quantity` in requests has `minimum: 1`.
- If the contract is ambiguous (e.g. malformed JSON, missing body, SKU id format),
  stop and ask Robert rather than guessing; record the decision in the README.

## Task list

[.claude/TODO.md](.claude/TODO.md) is the working task list. Read it at the start of each session,
check items off as they are done, and add new items as they come up. Its **oversell protection
test plan** is a hard requirement: a purchase must never oversell, even under concurrent requests.
Every feature that touches stock must add or extend tests from that plan.

## Stack

- Kotlin on the JVM, Spring Boot (Spring MVC), Gradle with the Kotlin DSL (`build.gradle.kts`).
  Chosen because it is what Nuuly runs in production.
- PostgreSQL (run with Docker Compose; Testcontainers in tests). Stock changes are single atomic
  SQL statements, backed by `CHECK (quantity >= 0)`. See the README decision record.
- Tests: JUnit 5 with Spring's test support.
- Open decisions are tracked in the README under "Open decisions". Do not settle
  one silently; propose it, explain the trade-offs, and let Robert choose.

## Commenting and documentation standard (teaching mode)

This goes further than normal production code on purpose. Follow it for every `.kt` file.

1. **KDoc on every non-private declaration**: classes, interfaces, objects, and functions.
   Say what it does, why it exists, and where it sits in the request flow
   (controller → service → repository). Use `@param`, `@return`, and `@throws` when
   they add information. Test classes get a KDoc saying what they cover.
   A PostToolUse hook ([scripts/check_kdoc.py](scripts/check_kdoc.py)) enforces this mechanically.
2. **Tagged inline comments** explain the concepts, not the syntax. Use these prefixes so
   they can be searched (`grep -rn "// Kotlin:" src`):
   - `// Kotlin:` a Kotlin language feature (data class, null safety, `val` vs `var`,
     scope functions, extension functions, `when`, default arguments, sealed types...).
     Add a note on how Java would do it when that helps.
   - `// Spring:` framework behavior that is otherwise invisible: what an annotation
     does, how dependency injection wires this, what auto-configuration provides,
     how a request is mapped or a response is serialized.
   - `// Why:` a design decision and the alternative that was rejected.
   - `// Contract:` the line exists to satisfy a specific part of inventory.yaml.
3. Explain a concept in full the first time it appears in a file. After that, a short
   tag is enough. Longer explanations go in [docs/learning/kotlin-concepts.md](docs/learning/kotlin-concepts.md).
4. Do not narrate trivial statements (`// increment counter`). Every comment should
   teach something or record a decision.
5. `build.gradle.kts` and `application.yml` get the same treatment: comment each plugin,
   dependency and setting with what it is for.

## Feature workflow

Use `/feature <description>` for any new behavior. It runs the whole loop:
contract check → plan (Robert approves) → tests + implementation → build →
`doc-reviewer` agent → README update → hand-off to `/walkthrough`.

- **Do not start the next feature until the walkthrough for the current one is done.**
- After the walkthrough, fix any comment that turned out to be unclear.
- Keep changes small and commit-sized. The reviewers want a readable, unsquashed
  git history. Propose a commit message, but only commit when Robert says so or runs `/ship`.

## Git workflow: never work on main

- **Every session starts on a new branch.** If the session starts on `main`, ask Robert what the
  session's work is before touching anything, then run
  `git switch main && git pull --ff-only && git switch -c <type>/<slug>`, where `<type>` is
  `feature`, `fix`, `chore`, `docs` or `test` (e.g. `feature/get-inventory`).
- One branch per piece of work. Commit in small, logical steps; the reviewers read the history.
- **`/ship`** is the only way work reaches `main`: push → PR → merge commit → back to an
  updated `main`. Never squash or rebase merges (the brief says "Do not squash commits").
- Enforced by `scripts/branch_guard.py`:
  - a `SessionStart` hook flags when the session starts on `main`
  - a `PreToolUse` hook blocks, while on `main`, edits to repo files and `git commit`/`git push`
  - on any branch, it blocks pushes to `main` and squash/rebase merges

## Project tooling (.claude/)

- `skills/feature/`: the `/feature` workflow above.
- `skills/walkthrough/`: `/walkthrough`, an interactive line-by-line teaching session
  on recent changes, logged to [docs/learning/walkthroughs/](docs/learning/walkthroughs/).
- `skills/ship/`: `/ship`, which pushes the current branch, opens a PR, and merges it into
  `main` with a merge commit. Build, test and review gates will be added later.
- `agents/doc-reviewer.md`: read-only reviewer that checks changed code against the
  commenting standard above.
- `TODO.md`: the working task list.
- `settings.json` hooks:
  - `SessionStart` + `PreToolUse`: `scripts/branch_guard.py` (see Git workflow).
  - `Stop`: exports session transcripts to [docs/ai-sessions/](docs/ai-sessions/).
  - `PostToolUse` (Edit/Write): runs `scripts/check_kdoc.py` on edited `.kt` files and
    reports missing KDoc back to Claude.

## Environment

Windows 11. Claude has PowerShell and Git Bash available. Python is on PATH and is
used for the hook scripts.
