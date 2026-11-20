# M-03: GitHub Actions CI

Card: `M-03` · Branch: `feature/M-03-ci` · Depends on: M-02

## What we are building, in one paragraph

The same checks as `scripts/check`, but run automatically by GitHub on a clean machine every time a
pull request is opened or updated. The local hook (M-02) protects you from your own mistakes. CI
protects everyone from the chance that "it works on my machine" is not true.

## New ideas in this lesson

### Continuous integration (CI)
- **What it is:** a server that builds and tests every change automatically.
- **Why here:** a reviewer should see a green or red tick on the pull request instead of having to
  trust the author.

### GitHub Actions and workflows
- **What it is:** GitHub's CI. A *workflow* is a YAML file in `.github/workflows/`. It says *when* to
  run (the `on:` part) and *what* to do (`jobs:` made of `steps:`).
- **Tiny example:**
  ```yaml
  on: pull_request
  jobs:
    check:
      runs-on: ubuntu-latest
      steps:
        - uses: actions/checkout@v7.0.1   # get the code
        - run: ./scripts/check.sh         # run our gate
  ```

### Runners
- **What it is:** the machine a job runs on. `ubuntu-latest` is a fresh Linux virtual machine,
  thrown away after the job. It already has Docker, which the integration tests will need (M-04).

### Actions ("uses:")
- **What it is:** ready-made steps written by others, such as `actions/setup-java`.
- **Why pin the version:** `@v6.3.0`, not `@v6` or `@main`. A step that changes under us can break or
  compromise a build, and `AGENTS.md` says to pin exact versions.

### Caching
- **What it is:** saving downloaded dependencies between runs. `setup-java` with `cache: gradle` and
  `setup-node` with `cache: pnpm` do this using the lock files as the key.
- **Why here:** downloading everything each time would make every run slower.

### Concurrency
- **What it is:** a rule about runs at the same time. We group runs by branch and set
  `cancel-in-progress: true`, so pushing again cancels the now-pointless older run.

### Why this costs nothing
GitHub does not count Actions minutes for public repositories on its standard runners. That holds
only while the repo is public, which is another reason no secrets may be committed.

## Where it sits in the architecture

Outside the system, like M-02. The workflow only calls `scripts/check.sh`, so all the logic lives in
one script that you can also run on your laptop. If we ever change CI provider, only the small YAML
file changes.

## Step by step

1. Branched from the M-02 branch and marked the card ◐.
2. Looked up the latest version of each action with the GitHub API, so we pin real versions.
3. Wrote `.github/workflows/ci.yml`:
   - **Trigger:** pull requests into `develop`, `main` and `feature/**`, and a manual button
     (`workflow_dispatch`). The `feature/**` part exists because our pull requests are stacked on
     each other until earlier ones are merged.
   - **Concurrency:** cancel an older run when a newer push arrives.
   - **Permissions:** `contents: read` only. The workflow can read the code and nothing else.
   - **Steps:** checkout, Java 21 (Temurin, Gradle cache), pnpm (version from `packageManager` in
     `package.json`), Node 24 (pnpm cache), then `./scripts/check.sh`.
4. Pushed and opened the pull request, then watched the run.

## Files touched

| File | What changed | Why |
|---|---|---|
| `.github/workflows/ci.yml` | New | The CI workflow |
| `docs/ROADMAP.md` | Card marked | Required by `AGENTS.md` |
| `docs/learning/*` | This lesson, index, glossary | The teaching pack |

## How we know it works

See the pull request for the run itself. The card's Done items:

| Done item | Proof |
|---|---|
| The first pull request shows the CI check | The run on this pull request (link in the PR description). |
| It is required by branch protection | **Not done yet, on purpose.** Requiring a check that the earlier open pull requests cannot produce would block their merge. Once this card is merged into `develop`, run the command in the pull request description. |
| The Actions usage page shows the run did not count against minutes | **A person must check this** in GitHub: Settings, Billing, Usage. Nothing in the repo can show it. |

## Rebuild it yourself

1. Create a workflow that runs `echo hello` on every pull request. Open a test pull request and find
   the run in the Actions tab.
2. Make it run your project's tests. Break a test on purpose and watch the pull request go red.
3. Add a cache step and compare the run times of the first and second run.
4. Push twice quickly and watch concurrency cancel the first run.

## Mistakes to avoid

- **Unpinned actions** (`@main`, `@v6`). Pin exact tags.
- **Giving the workflow more permissions than it needs.** Start from `contents: read`.
- **Putting logic in the YAML.** Keep it in `scripts/check`, so it can be run and debugged locally.
- **Making a check "required" before it exists on every open pull request.** GitHub waits forever for
  a check that never runs.
