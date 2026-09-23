---
name: walkthrough
description: Interactive teaching session that walks Robert through recently written code line by line, explaining Kotlin and Spring concepts, pausing for questions and checking understanding, then logs the session to docs/learning. Use after every feature, or when Robert asks to go through or explain code.
argument-hint: "[optional: files, commit range, or topic; defaults to everything since the last walkthrough]"
---

# /walkthrough $ARGUMENTS

Robert is learning Kotlin through this project. He has to understand the code well
enough to explain and defend it in an interview. Go at his pace. This is a
conversation, not a lecture.

## 1. Establish scope

- If `$ARGUMENTS` names files, a commit range or a topic, use that.
- Otherwise, find the last walkthrough log in `docs/learning/walkthroughs/` and read the
  commit SHA it records. The scope is `<that sha>..HEAD` plus any uncommitted changes.
  If there are no logs yet, the scope is everything under `src/` plus the build files.

Show the list of files in scope. Order them along the path a request takes:
build/config → entry point → controller → service → repository/model → error handling → tests.
Ask whether that order works for him before starting.

## 2. Walk through one file at a time

For each file:

1. **Role.** One or two sentences on what this file is for and how a request reaches it.
2. **Code in small chunks** (roughly 5–20 lines). Quote each chunk with its `path:line` reference, then explain:
   - every Kotlin feature in it: what it does, why it's idiomatic, and what the Java
     equivalent would look like when that comparison helps;
   - any Spring behavior that isn't visible in the code (what the framework does with the
     annotation, when the object is created, what runs before and after);
   - the design decision, and the alternative we rejected and why.
3. **Pause.** Ask whether he has questions about the file. Then ask one short
   check-your-understanding question, for example: "What happens if `quantity` is 0 here?"
   or "Why is this a `val`?" or "What would the response be if the SKU doesn't exist?"
   **Wait for his reply before moving to the next file.** Don't answer your own question.
4. If his answer is wrong or partial, re-explain it another way. Don't just repeat the
   explanation.
5. If a question shows that a code comment is unclear or wrong, offer to fix the comment.

Where a quick experiment would help, suggest one he can run himself, such as changing a
value and rerunning a test to watch it fail, or a `curl` call against the running app.

## 3. Wrap up

- Recap the concepts covered in 3–6 bullets.
- Ask whether anything should be revisited.

## 4. Log it

Write `docs/learning/walkthroughs/<YYYY-MM-DD>-<short-slug>.md` containing:

```
# Walkthrough: <feature/topic>
- Date: <date>
- Covers: <first sha>..<HEAD sha> (plus uncommitted: yes/no)
- Files: <list>

## Concepts covered
- <concept>: <one-line summary in Robert's terms>

## Questions Robert asked
- Q: ... / A: ...

## Understanding checks
- <question> → <how he answered, what was clarified>

## Follow-ups
- <comments to fix, topics to revisit, exercises to try>
```

Then update `docs/learning/kotlin-concepts.md`. For each concept seen for the first time, add a
row with the concept, a short explanation, and a `path:line` link to where it first appears in
this codebase. Don't add duplicate rows.
