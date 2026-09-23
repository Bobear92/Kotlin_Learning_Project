---
name: ship
description: Ship the current branch. Commits any remaining work, pushes to GitHub, opens a pull request, merges it into main with a merge commit (never squash), and returns to an updated main. Use when Robert says to ship, push, or merge the current work.
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

## 2. Checks

_Placeholder: build, tests, contract validation and the doc-reviewer gate will be
added here later._

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

## 5. Merge

- `gh pr merge <number> --merge --delete-branch`
  - Always use `--merge`. **Never `--squash` or `--rebase`**, because the assessment reviewers
    want the full commit history. (A PreToolUse hook blocks squash and rebase.)
- `git switch main && git pull --ff-only`
- Delete the local branch if `gh` didn't: `git branch -d <branch>`.

## 6. Report

Give the PR URL, the merge commit SHA, and the commits that were shipped. Remind Robert
that the next piece of work needs a new branch; the branch guard will enforce this.
