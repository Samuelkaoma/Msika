# Contributing to Msika

## Branches (git flow)

| Branch | What it is | Who merges into it |
|---|---|---|
| `main` | Released code only. Every merge is a tagged release (`v0.1.0`) | SK, from `release/*` or `hotfix/*` |
| `develop` | Where finished work comes together. Must always build and pass checks | Anyone, through a reviewed pull request |
| `feature/M-07-stalls` | One roadmap card. Branched from `develop` | Merged into `develop` |
| `release/0.1.0` | Preparing a release. Fixes only, no new features | Merged into `main` and back into `develop` |
| `hotfix/0.1.1-short-name` | An urgent fix to a release. Branched from `main` | Merged into `main` and back into `develop` |

Day to day:

```bash
git switch develop
git pull
git switch -c feature/M-07-stalls
# work, commit, run the checks
git push -u origin feature/M-07-stalls
# open a pull request into develop
```

Keep a branch short-lived. If `develop` moves on while you work, merge `develop` into your branch
(do not rebase a branch someone else has pulled).

## Pull requests

- Into `develop` only, except release and hotfix branches.
- One roadmap card per pull request. The title starts with the card: `M-07 Councils, markets and stalls`.
- One approval needed. The author does not merge their own pull request without it.
- The checklist in the template must be filled in honestly, including the output of the checks.
- Tick the card in `docs/ROADMAP.md` in the same pull request.

## Commit messages

**No `Co-Authored-By` trailers, and no tool signatures such as "Generated with ..." in commits or pull request
descriptions.** The `commit-msg` hook refuses them locally. `scripts/check-no-coauthor.sh <base-ref>` checks a whole branch;
adding it to the CI workflow needs a token with the `workflow` permission, so a maintainer does that.

A plain sentence that says what changed, in the present tense:

```
Refuse a deposit for a stall that has no trader
Add the daily seal job and run it at 20:00 Lusaka time
```

No prefixes like `feat:`. A body is welcome when the reason is not obvious.

## The checks

Every change must pass the same checks, whether or not GitHub Actions is running.

```powershell
./scripts/check.ps1 --fast   # unit tests, lint, typecheck: about a minute
./scripts/check.ps1          # everything, including integration tests (needs Docker running)
```

(`scripts/check.sh` does the same on Linux and macOS.)

Run `scripts/setup.ps1` once after cloning. It turns on the pre-push hook, which runs the fast
checks before every push. Do not skip it with `--no-verify`. If the hook is wrong, fix the hook.

Before asking for review, run the full checks and paste the last lines of the output into the pull
request.

## CI without paying for minutes

The Msika repository on GitHub is **public**. GitHub does not count Actions minutes for public
repositories on its standard runners, so the account being out of minutes (which stopped CI on the
private RTSA repository) does not affect this one. Card M-03 sets it up and confirms on the Actions
usage page that runs are not being counted.

That only holds while the repo stays public. It also means **nothing secret goes in the repo**: no
keys, no passwords, no real phone numbers or names. Secrets live in `.env` files that git ignores,
and `.env.example` shows what is needed.

If Actions is ever unavailable, in order of preference:

1. **The local gate.** The pre-push hook and `scripts/check` are the real gate anyway. The reviewer
   runs the full checks on the branch before approving.
2. **A self-hosted runner.** GitHub does not charge minutes for jobs on a runner you host. A team
   member's machine or a cheap server can register as a runner for this repo, and `ci.yml` switches
   to `runs-on: self-hosted`. Only do this while the repo accepts pull requests from the team alone:
   on a public repo, a stranger's pull request could run code on that machine. Require approval for
   outside contributors' workflows in the repo settings first.
3. **Another CI service** with a free tier, running the same `scripts/check.sh`. Because all the
   logic is in the script, switching costs one config file.

Deployment does not use GitHub Actions either. Msika runs on the team's VPS under Dokploy, which
redeploys `main` when it changes (card M-45). So merging a release into `main` is what deploys it,
and nothing reaches the server without going through `develop`, a release branch and its checks.

## Code rules

The rules every change must follow are in `AGENTS.md`. Read it. The short version: money in whole
ngwee, the ledger never changes, every council table has row-level security, everything from outside
is idempotent, the simulators are only reached through ports, and no real personal data in the repo.
