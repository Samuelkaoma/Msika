# M-02: Git flow and the local gate

Card: `M-02` · Branch: `feature/M-02-local-gate` · Depends on: M-01

## What we are building, in one paragraph

A safety net that runs on your own computer before code leaves it. One command (`scripts/check`)
runs the tests and checks. A git hook runs the fast version automatically before every `git push`,
so broken code cannot reach GitHub by accident. We also add a pull request template and protect the
`main` and `develop` branches so nobody pushes to them directly.

## New ideas in this lesson

### Git flow
- **What it is:** a branching convention. `main` holds released code, `develop` is where finished
  work comes together, and each roadmap card gets its own short-lived `feature/...` branch.
- **Why here:** five people work at once. Branches keep half-finished work out of everyone else's way,
  and pull requests give each change a reviewer.

### Quality gate
- **What it is:** a set of automatic checks every change must pass: tests, lint and type checking.
- **Why here:** `AGENTS.md` says "never skip a test or loosen a check to get green". A gate that is
  one command makes that rule easy to follow.
- **Fast versus full:** fast (about a minute) runs unit tests, lint and type checks. Full adds
  integration tests (which need Docker) and the complete builds.

### Lint
- **What it is:** a check for code that is legal but suspicious. For Java we turn on every compiler
  warning (`-Xlint:all`) and make warnings errors (`-Werror`). For the web apps, TypeScript's strict
  type check does the equivalent job.
- **Tiny example:** an unchecked cast compiles fine, but with `-Werror` the build stops and asks you
  to deal with it.

### Test tags (unit versus integration tests)
- **What it is:** JUnit lets you label a test with `@Tag("integration")`. We exclude that tag from the
  `test` task and run it in a separate `integrationTest` task.
- **Why here:** integration tests start a real database in Docker. They are slow and need Docker, so
  they only run in the full check.

### Git hook
- **What it is:** a script git runs at certain moments. `pre-push` runs just before a push. If it
  exits with an error, git cancels the push.
- **Why here:** the hook is the real gate. It works even if GitHub is unavailable. The hook lives in
  `.githooks/` and `scripts/setup` tells git to use that folder (`core.hooksPath`), because git does
  not copy hooks when you clone.

### Pull request template
- **What it is:** a file at `.github/pull_request_template.md`. GitHub pre-fills every new pull
  request with it.
- **Why here:** it makes the author prove each Done item, paste the check output, and tick the
  `AGENTS.md` rules.

### Branch protection
- **What it is:** a GitHub setting on a branch: pull request required, approvals required, no force
  pushes, no deletion.
- **Why here:** `main` and `develop` must always build.

## Where it sits in the architecture

None of this is part of the running system. It protects it. The gate scripts call the tools from M-01
(`./gradlew`, `pnpm`).

```
git push --> .githooks/pre-push --> scripts/check.sh --fast --> gradlew test + pnpm typecheck
                                         |  fails? the push is cancelled
```

## Step by step

1. Branched from the M-01 branch (the M-01 pull request is not merged yet) and marked the card ◐.
2. **Lint and test tags** in the root `build.gradle.kts`: `-Xlint:all,-processing -Werror` for every
   Java module, `test` excludes `integration`, and a new `integrationTest` task runs only those.
   `check` depends on `integrationTest`, so `./gradlew build` includes them.
3. **`typecheck` script** (`tsc --noEmit`) in each web app.
4. **`scripts/check.sh` and `scripts/check.ps1`.** `--fast`: Java tests (with lint),
   `pnpm install --frozen-lockfile`, typecheck. Full: also `./gradlew build` and `pnpm -r build`.
   They stop at the first failure.
5. **`.githooks/pre-push`.** A tiny `sh` script that runs `scripts/check.sh --fast`. It is `sh`, not
   PowerShell, because Git for Windows runs hooks with its own bash, so one file works for everyone.
6. **`scripts/setup.sh` and `scripts/setup.ps1`.** One line: `git config core.hooksPath .githooks`.
7. **`.github/pull_request_template.md`.**
8. **Branch protection** through the GitHub API: one approval, no force pushes, no deletions, on
   `main` and `develop`. We also set the repo's default branch to `develop`, because GitHub reads the
   PR template from the default branch.
9. **Proved the hook works.** We planted a failing test, committed it locally and ran `git push`.
   The hook ran the tests, they failed, and git refused to push. We then removed the test.

## Files touched

| File | What changed | Why |
|---|---|---|
| `build.gradle.kts` | Lint flags, `integration` tag split, `integrationTest` task | Fast and full gates |
| `apps/*/package.json` | Added `typecheck` script | The gate needs a type check without building |
| `scripts/check.sh`, `scripts/check.ps1` | New | The gate |
| `scripts/setup.sh`, `scripts/setup.ps1` | New | Turn on the hook |
| `.githooks/pre-push` | New | Run the fast gate before every push |
| `.github/pull_request_template.md` | New | The checklist for every pull request |
| `README.md` | Setup and check instructions | Quick start |
| `docs/ROADMAP.md` | Card marked | Required by `AGENTS.md` |

## How we know it works

| Done item | Proof |
|---|---|
| A push with a failing unit test is blocked by the hook | Planted a failing test and ran a real `git push`. Output: `BUILD FAILED`, then `failed to push some refs`. Nothing reached GitHub. |
| The PR template appears on a new pull request | The file is in the repo and the default branch is `develop`. It will appear once this card is merged into `develop`. Not seen yet. |
| `main` and `develop` cannot be pushed to directly | Protection is set and was read back through the GitHub API. Admins can still bypass it (see below). No push to a protected branch was attempted. |

## Rebuild it yourself

1. Write a `scripts/check.sh` that runs your project's tests and exits non-zero on failure.
2. Make a `pre-push` hook that calls it. Run `git config core.hooksPath` to point git at the folder.
3. Plant a failing test and try to push. Remove the test.
4. Add a pull request template and open a test pull request to see it appear.
5. Turn on branch protection for a branch in GitHub's settings and try to push to it.

## Mistakes to avoid

- **Skipping the hook with `--no-verify`.** If the hook is wrong, fix the hook.
- **Committing the hook and forgetting setup.** Hooks are not cloned, so everyone must run
  `scripts/setup` once.
- **Branch protection with "enforce for admins" on, with one person on the team.** You cannot
  approve your own pull request, so you lock yourself out. We left it off. Turn it on once there is a
  second reviewer.
