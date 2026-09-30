---
name: ship
description: Ship the current branch. Commits any remaining work, pushes to GitHub, opens a pull request, waits for the CI checks to pass, merges it into main with a merge commit (never squash), and returns to an updated main. Use when Robert says to ship, push, or merge the current work.
argument-hint: "[optional: PR title]"
disable-model-invocation: true
---

# /ship $ARGUMENTS

Running `/ship` is Robert's go-ahead to commit, push and merge the current branch.
Report each step as you go. If any step fails, stop and explain; don't improvise
around it (no force-push, no `--no-verify`, no merging from the command line).

## 1. Pre-flight

- Current branch: `git branch --show-current`. If it's `main`, stop. There's nothing to ship,
  and work should never happen on main.
- Show `git status --short` and `git log --oneline origin/main..HEAD`.
- If there are uncommitted changes, check the file list for anything that shouldn't be
  committed (`.env`, credentials, keys, build output, IDE files, large binaries). If
  something looks wrong, stop and ask. Otherwise stage everything and commit with a concise
  message describing the change, ending with the attribution line from the system reminder.
- If there are no commits ahead of `origin/main`, stop. There's nothing to ship.

## 2. Local checks

- `python scripts/check_notes.py` must print nothing (study notes in sync). If it reports
  anything, stop and run `/sync-docs` first.
- The build and tests run in CI (step 5), not here.

## 3. Sync with main

- `git fetch origin`
- If `origin/main` has commits this branch lacks, `git merge origin/main` (a merge, not a
  rebase, so history is preserved). On conflicts, stop and walk Robert through them.

## 4. Push and open the pull request

- `git push -u origin <branch>`
- If a PR for this branch already exists (`gh pr view --json url`), reuse it.
  Otherwise, `gh pr create --base main --head <branch>` with:
  - **Title:** `$ARGUMENTS` if given, otherwise a short summary of the branch's commits.
  - **Body:** a `## Summary` of what changed and why (bullets), a `## Commits` list, and the
    PR attribution line from the system reminder at the end.

## 5. Wait for CI

The `Tests` workflow (`.github/workflows/tests.yml`) runs two checks on the pull request:
**Tests** (`./gradlew build`) and **Load test** (`./gradlew loadTest`).

- `gh pr checks <number> --watch --fail-fast` waits until they finish (a few minutes).
- If any check fails, **stop; don't merge.** Give Robert the run's URL (its summary page has the
  test report with every failure and its message), explain the failure, and fix it on the branch.
  Then push again and repeat this step.
- Only continue when every check passed.

## 6. Merge

- `gh pr merge <number> --merge --delete-branch`
  - Always use `--merge`. **Never `--squash` or `--rebase`**, because the assessment reviewers
    want the full commit history. (A PreToolUse hook blocks squash and rebase.)
- `git switch main && git pull --ff-only`
- Delete the local branch if `gh` didn't: `git branch -d <branch>`.

## 7. Report

Give the PR URL, the merge commit SHA, the CI run URL, and the commits that were shipped. Remind Robert
that the next piece of work needs a new branch; the branch guard will enforce this.
