# GitHub Task Command

Run one GitHub issue end-to-end in an isolated worktree.

Input:

- A GitHub issue URL, `#123`, or `123`.
- Resolve the numeric issue id and use that exact id as the worktree and branch name.

Project setup:

@AGENTS.md
@PROJECT.md
@docs/DECISIONS.md
1. Read `/Users/viktorskalinins/.codex/RTK.md`.
2. Prefer committed scripts under `scripts/` over ad-hoc equivalents.

Workflow:

1. Read the GitHub task thoroughly.
   - Use `gh issue view <id> --comments`.
   - Inspect linked PRs, parent/sub-issues, dependencies, and sibling tasks when available and relevant.
   - Summarize the concrete acceptance criteria before changing files.
2. Update the main checkout.
   - Check for unrelated local changes and do not overwrite them.
   - Fetch latest origin.
   - Checkout the repository default branch, preferring `master` when present.
   - Pull fast-forward-only latest updates.
3. Start an isolated worktree named exactly as the task id.
   - Run `rtk scala-cli run scripts/worktree-start.scala -- <id>`.
   - Continue all implementation work inside `.worktrees/<id>`.
4. Implement the task in the worktree.
   - Follow pure FP project rules: no `var`, `null`, `throw`, or `unsafeRunSync`.
   - For Scala source analysis, compile first and use ScalaSemantic MCP tools when available.
   - Keep edits scoped to the task.
5. Verify locally.
   - Run the narrowest relevant tests first.
   - Before PR, run the repo pre-push gate: `rtk mill prePush` or `rtk scala-cli run scripts/git-pre-push.scala`.
6. If no files changed:
   - Comment on the issue with the investigation result and why no code change was needed.
   - Close the issue only if the task is already satisfied.
   - Run `rtk scala-cli run scripts/worktree-finish.scala -- <id>` from the main checkout.
   - Report the issue comment and cleanup status.
7. If files changed:
   - Commit with a concise task-focused message.
   - Push the branch.
   - Create a PR with `gh pr create`, linking the task so GitHub can close it automatically when merged.
   - Wait for GitHub CI to pass using `gh pr checks --watch` or the relevant `gh run watch` command.
   - If CI fails, inspect logs, fix, push again, and wait again.
   - Merge the PR after CI passes.
   - Post a final issue comment containing:
     - What changed.
     - Verification performed.
     - PR link.
     - Merge result.
   - Close the issue with a closing comment if it did not close automatically.
   - Clean up the worktree with `rtk scala-cli run scripts/worktree-finish.scala -- <id>`.

Stop conditions:

- Do not delete or revert unrelated user changes.
- If the task cannot be completed because of missing credentials, failing external services, or an ambiguous requirement that cannot be resolved from related GitHub tasks, stop with the exact blocker and the current worktree/branch state.
