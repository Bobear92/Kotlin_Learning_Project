# How this was built

This project is two things at once: the Nuuly inventory assessment, and how I learned Kotlin.
I used AI (Claude Code) as a **teacher and pair**, not as the author of my code. This page explains
the rules I set, how they were enforced, and where to find the evidence, so you can check it yourself.

**In short:**

- **I wrote all of the production code** (`src/main/`). Claude wrote the tests first, coached me
  while I wrote the code, reviewed it, and documented it. Every claim below links to its evidence.
- **Every feature ended with a walkthrough** where I explained the code back and answered questions.
  The logs keep my wrong answers and how they were corrected.
- **It's verifiable:** `python scripts/authorship_audit.py` lists, from the committed session
  transcripts, every change Claude ever made under `src/main/`.

## The goal

I came to this assessment knowing Java-style backend work but not Kotlin. Nuuly runs Kotlin and
Spring Boot, so I picked that stack and set myself one requirement next to "the tests pass":
**I have to be able to explain and defend every line in the follow-up interview.** That rules out
letting an AI write the code, however fast it would be.

## The rules

From [CLAUDE.md](../CLAUDE.md), "Who writes what":

| | Robert | Claude |
| --- | --- | --- |
| Production code (`src/main/`: Kotlin, SQL migrations, `application.yml`), incl. KDoc and comments | writes it | doesn't touch it unless I ask for a specific edit |
| Tests (`src/test/`) | reviews them, wrote the naive repository for the negative control | writes them **before** I start, as the spec |
| Help while coding | asks | hints in increasing steps: the concept → the API to look up → the shape in words → a snippet in chat, only when asked, never a file edit |
| Code review | makes the changes | explains each point |
| Study notes, README, tooling, CI | reviews and decides | writes |

Open design decisions were always mine. Claude laid out the options and trade-offs, and I chose.
The choices are recorded in the README's "Open decisions" table.

### How the rules were enforced

Rules in a prompt can be ignored, so the important ones are hooks that run on every tool call
([.claude/settings.json](../.claude/settings.json)):

- [scripts/code_guard.py](../scripts/code_guard.py): any Claude edit under `src/main/` stops and
  asks me first.
- [scripts/check_notes.py](../scripts/check_notes.py): Claude can't end a turn while a source file
  is newer than its study notes.
- [scripts/check_kdoc.py](../scripts/check_kdoc.py): reports missing KDoc after every edit.
- [scripts/branch_guard.py](../scripts/branch_guard.py): no work on `main`; everything goes through
  a pull request with the full commit history (no squash).

## The loop for every feature

Run by the `/feature` skill ([.claude/skills/feature](../.claude/skills/feature/SKILL.md)):

1. **Pin the contract:** quote the exact part of `inventory.yaml`, list the acceptance criteria,
   and put every ambiguity to me.
2. **Plan**, then wait for my approval.
3. **Claude writes failing tests.** They are the spec for my code.
4. **I write the code; Claude coaches.** Hints, not answers.
5. **Review.** Claude reviews against the contract, idiomatic Kotlin and the documentation standard;
   I make the changes.
6. **Study notes** for every file I wrote ([docs/learning/notes/](learning/notes/README.md)): the
   code quoted chunk by chunk, each Kotlin feature compared with Java, the Spring behavior the code
   doesn't show, and questions to check myself. A reviewer agent audits them for accuracy.
7. **Walkthrough:** I explain the code back, line by line, and answer check questions
   ([docs/learning/walkthroughs/](learning/walkthroughs/)).

## The proof

### Who wrote `src/main/`

Claude ran most `git commit` commands and added its `Co-Authored-By` trailer to each one, including
commits of code I wrote. So **git history alone overstates Claude's part.** I noticed this on
2026-09-30. Rather than rewrite history (the brief asks for it unsquashed), I added an audit that
uses the full session transcripts committed in [docs/ai-sessions/](ai-sessions/):

```sh
python scripts/authorship_audit.py
```

It lists every file edit Claude made under `src/main/`, whether it changed code or only comments,
and my last message before it. The result:

- **Code changes by Claude happened only twice:**
  - The **2026-09-23 scaffold**: the generated `InventoryApplication.kt` and `application.yml`,
    before the rule existed.
  - The **2026-09-24 incident**: in the first feature, Claude wrote `GET /inventory` itself.
    That code was deleted, the rule was tightened, and I rewrote the feature myself.
- **Since then: 7 edits, all comments or KDoc**, each made right after I asked for it (for example
  "Add the KDoc where you feel it needs to be").
- **Everything else in `src/main/`, which is all of the application logic, I typed.**

From 2026-09-30 on, commits of my code carry no Claude trailer; their body says
"Written by Robert; Claude coached and ran the commit."

### What learning looked like

The walkthrough logs record my answers, including the wrong ones. A few examples:

- **Migrations** ([log](learning/walkthroughs/2026-09-25-get-inventory.md)): I thought a new `V2`
  file recreates the table and the newest file is the truth. Corrected: migrations are a chain of
  changes, like commits. I then answered correctly that editing `V1` fails at startup.
- **Spring beans and threads** (same log): I first said two simultaneous requests get two
  controller objects. After an experiment with `identityHashCode`, I explained why it's one
  shared object, and why `val`-only fields make that safe.
- **The database `CHECK` constraint** ([log](learning/walkthroughs/2026-09-29-purchase-inventory.md)):
  I thought `CHECK (quantity >= 0)` would stop a purchase of `-5`. Tracing it showed stock goes *up*
  to 15, which the constraint happily accepts. My takeaway: input validation has to live in the
  application.
- **Request order** (same log): I guessed a missing SKU would win over an invalid body. Routing
  can't know which SKUs exist; validation runs first, so it's a 400.
- **`val` in a constructor** ([log](learning/walkthroughs/2026-09-28-get-inventory-by-sku.md),
  [log](learning/walkthroughs/2026-09-30-oversell-hardening-tests.md)): I mixed up storage
  (`val`) and visibility (`private`) twice, on 09-28 and again on 09-30. It's still on my review list.
- **Lost updates** ([log](learning/walkthroughs/2026-09-30-oversell-hardening-tests.md)): I wrote
  the deliberately broken read-then-write purchase for the negative-control test myself and found
  the race window in it.

### By the numbers (2026-09-23 to 2026-09-30)

| | |
| --- | --- |
| Pull requests, each merged with its full history | 10 |
| Commits | 96 |
| Tests (plus a separate load test) | 124 |
| Study-notes files, one per source file | 32 |
| Kotlin/Spring concepts indexed, each linked to where it first appears | ~150 ([index](learning/kotlin-concepts.md)) |
| Walkthrough logs | 6 |
| Interview-prep questions with answers | 34 ([sheet](learning/interview-prep.md)) |
| Session transcripts committed | 30 |

## Where to look

If you have ten minutes, in this order:

1. [CLAUDE.md](../CLAUDE.md): the rules I gave the AI.
2. A walkthrough log, e.g. [purchase](learning/walkthroughs/2026-09-29-purchase-inventory.md):
   what I understood, and what I got wrong.
3. A study-notes file, e.g. [InventoryRepository](learning/notes/main/inventory/InventoryRepository.kt.md):
   how the code was taught.
4. `python scripts/authorship_audit.py`: who wrote the code.
5. A session transcript in [docs/ai-sessions/](ai-sessions/): the unedited conversations.

## Limits, honestly

- **The tests are mostly Claude's.** They were written first as the spec for my code. I reviewed
  them and wrote the naive repository for the negative control, but I didn't author the suite.
- **The last walkthrough is partial.** It covers the negative control; the contract, property,
  multi-instance and load tests are listed as follow-ups in its log.
- **The early scaffold** (Gradle setup, entry point) came from Spring Initializr and Claude, before
  the rule existed. I walked through it on day one, but I didn't type it.
