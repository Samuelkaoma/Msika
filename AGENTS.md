# Instructions for AI assistants (and a summary for people)

Msika records a market trader's cash day: closing-time deposits into a mobile money wallet, digital
sales, levy paid, and monthly turnover, all against a stall. It is built in Java 21 / Spring Boot,
Postgres, React and Python. Zamtel Money and eLevy are **simulated** by our own services because we
have no access to them yet.

## Read these before any work

1. `docs/ROADMAP.md`: the cards, in order. Work happens one card at a time.
2. `docs/ARCHITECTURE.md`: what the system is and the rules it rests on.
3. `docs/SMS-PROTOCOL.md`: the exact SMS commands and replies.
4. `CONTRIBUTING.md`: git flow, commit messages, the checks.

## How to work a card

1. Find the card the user named, or the first ☐ card whose dependencies are all ☑. If the user named
   a card whose dependencies are not done, say so before starting.
2. `git switch develop && git pull`, then `git switch -c feature/M-NN-short-name` (the branch name
   is on the card).
3. Mark the card ◐ with the branch name in the status section of `docs/ROADMAP.md`.
4. Build only what the card's **Do** says. If something else is needed, write it down in the pull
   request as a follow-up rather than building it.
5. Write the tests that prove each **Done** item. A Done item without a test (or, where a test is
   impossible, a written manual check) is not done.
6. Run `./scripts/check.ps1` (or `.sh`) in full. Fix every failure. Never skip a test, mark one as
   ignored, or loosen a check to get green.
7. Mark the card ☑ in the roadmap, commit, push, and open a pull request into `develop` using the
   template. Do not merge it. A person reviews and merges.

Before M-02 exists there is no check script. For M-01 run `./gradlew build` and `pnpm -r build`.

## Rules that are never broken

These come from `docs/ARCHITECTURE.md` §2. Breaking one is a bug even if every test passes.

1. **Money is `long` ngwee** in Java and `bigint` in Postgres. Never `double`, `float` or
   `BigDecimal` for stored amounts. Use `Money` from `msika-ledger`.
2. **The ledger is append-only.** Only the `RecordEntry` use case writes `ledger_entry`. Never update
   or delete a ledger row. Mistakes are fixed with a `REVERSAL` entry.
3. **Every ledger entry has a provenance** (`VERIFIED`, `DEPOSITED`, `DECLARED`), and figures shown
   to anyone keep them apart.
4. **Every table with council data has `council_id` and a row-level security policy.** Never connect
   as the owner role from the app. Never add `BYPASSRLS`.
5. **Everything from outside is idempotent**: SMS (`gatewayMessageId`), payment callbacks
   (transaction id), commands (`Idempotency-Key`). Write the duplicate test.
6. **Outside systems only through ports.** `msika-core` defines `PaymentsPort`, `LevyAuthorityPort`,
   `SmsGatewayPort`, `LenderPort`. Adapters live in `msika-api`. Msika never imports code from
   `zamsim` or `elevysim` and never reads their databases.
7. **`msika-core` and `msika-ledger` import no Spring, JPA, Jackson or HTTP clients.** An ArchUnit
   test enforces this.
8. **Msika never holds money and never lends.** Money only moves between wallets in the payments
   system, with the trader's recorded consent.
9. **The repository is public.** No secrets, keys, real names or real phone numbers in code, tests,
   seed data, docs or commit messages. Use `+26097700xxxx` style made-up numbers.
10. **Every SMS reply fits 160 GSM-7 characters** and comes from the templates in one place.

## Conventions

- Java package root `zm.msika`. Modules: `zm.msika.ledger`, `zm.msika.core`, `zm.msika.api`,
  `zm.msika.zamsim`, `zm.msika.elevysim`.
- Database changes only through new Flyway migrations. Never edit a migration that is on `develop`.
- Times are stored as `timestamptz`. The business date is the Africa/Lusaka date.
- Phone numbers are E.164 (`+260...`) once they enter the system, and masked in logs.
- Integration tests use Testcontainers Postgres, not H2 and not a shared database.
- REST paths start with `/api/v1/`. The OpenAPI spec is generated from the code.
- Pin exact dependency versions. Adding a dependency needs a sentence in the pull request on why.
- Commit messages are plain sentences in the present tense (see CONTRIBUTING.md). Never add a
  `Co-Authored-By` trailer or a "Generated with ..." line to a commit or pull request. A commit hook refuses them.

## When you are unsure

- If a card is unclear or seems to conflict with these rules, stop and ask the user rather than
  guessing. Quote the part that conflicts.
- If you find a bug outside the card, write it in the pull request under "Found while working" and
  leave it alone.
- Say plainly what you did not finish or could not verify. "Tests pass" means you ran them and saw
  them pass in this session.

## Where code came from

`msika-ledger` starts from SKACE Central Banking's ledger and integrity classes. See
`platform/msika-ledger/SOURCE.md` once M-06 is done. Nothing is copied from Arcus.
