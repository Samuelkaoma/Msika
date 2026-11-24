# Msika roadmap

How we build Msika, in order, one card at a time. Read `AGENTS.md` first, then
`docs/ARCHITECTURE.md` and `docs/SMS-PROTOCOL.md`.

- Start: Monday 23 November 2026. Weeks below count from there.
- **Demo date: _set this when the challenge announces it_.** Everything above the MVP line must be
  done and rehearsed one week before it.
- The 22 December to 2 January holiday is planned as a light week (week 5).

## Team and owners

| Code | Person | Owns |
|---|---|---|
| SK | Samuel Kaoma | Architecture, ledger and seal, code review, merges to `main` |
| TM | Taonga Mtonga | SMS channel, payments adapter, sim console, agent app |
| KK | Kashela Kabechani | Infra, Postgres and RLS, security, simulators, CI |
| WM | Wanipa Mwale | Synthetic data, survey, levy and turnover figures, ML |
| MP | Mapalo Phiri | Levy rules research, council contacts, demo script, pitch |

An AI assistant (Claude) can take any card. The person named still reviews and owns it.

## How to read a card

```
M-07  Title                                   owner · depends on · branch
  Do:    what to build
  Done:  checks that must all be true before the pull request is merged
```

Every card is one branch `feature/M-07-short-name` from `develop`, one pull request back into
`develop`, and must pass `scripts/check` (see `CONTRIBUTING.md`). Tick the card's box in the status
table in the same pull request.

## Status

| Phase | Cards | Status |
|---|---|---|
| 0 Foundation | M-01 to M-05 | ☑ |
| 1 Core record | M-06 to M-11 | ☑ |
| 2 Payments simulator | M-12 to M-16 | ☑ |
| 3 SMS channel | M-17 to M-21 | ☐ |
| 4 Levy and eLevy simulator | M-22 to M-25 | ☑ (M-23 amounts still to research) |
| 5 Council dashboard and demo | M-26 to M-30 | ☐ |
| 5M Market map (inside Phase 5) | M-48 to M-51 | ☑ |
| 5A Council approvals (inside Phase 5) | M-61 to M-64 | ☐ |
| **MVP line: release v0.1.0** | | |
| 6 Turnover statements | M-31 to M-33 | ☐ |
| 7 Credit and intelligence | M-34 to M-39 | ☐ |
| 8 Agent app and WhatsApp | M-40 to M-42 | ☐ |
| 9 Pilot readiness | M-43 to M-47 | ☐ |
| 10 Measured market map | M-52 to M-60 | ☐ |
| 11 Enforcement and oversight | M-65 to M-70 | ☐ |

The market map and council admin cards are numbered after M-47 because they were added later; the
numbers say nothing about order. Phases 5M and 5A are built inside Phase 5, before the demo cards
M-28 and M-29.

Card boxes: ☐ not started, ◐ in progress (put the branch name next to it), ☑ merged.

---

## Phase 0: Foundation (week 1)

Goal: anyone can clone the repo, run one command, and get a working empty system with green checks.

**☑ M-01 Monorepo skeleton** · SK · none · `feature/M-01-skeleton`
- Do: create the layout in ARCHITECTURE §3. Gradle Kotlin DSL multi-module build with the Gradle
  wrapper (nobody needs Gradle installed), Java 21 toolchain, the current stable Spring Boot pinned to
  an exact version. `msika-api`, `zamsim` and `elevysim` each start with a `/health` endpoint. pnpm
  workspace with `council-web` and `sim-console` as Vite + React + TypeScript apps showing a
  placeholder page. `.gitignore`, `.editorconfig`, `.gitattributes` (LF line endings), root
  `README.md` with the quick start.
- Done: `./gradlew build` and `pnpm -r build` both pass on a clean clone on Windows. The three health
  endpoints answer when run. Versions are pinned exactly (no `+`, no `^` in root config).

**☑ M-02 Git flow and the local gate** · KK · M-01 · `feature/M-02-local-gate`
- Do: `scripts/check.ps1` and `scripts/check.sh` with `--fast` (unit tests, lint, typecheck) and full
  (adds integration tests and builds). `.githooks/pre-push` is a `sh` script that runs
  `scripts/check.sh --fast` (Git for Windows runs hooks in its own bash, so one hook serves everyone). `scripts/setup.ps1` and
  `scripts/setup.sh` run `git config core.hooksPath .githooks`. `.github/pull_request_template.md`
  with the checklist from CONTRIBUTING.md. Create the `develop` branch. Turn on branch protection
  for `main` and `develop` on GitHub: pull request required, one approval, no force pushes.
- Done: a push with a failing unit test is blocked by the hook. The PR template appears on a new
  pull request. `main` and `develop` cannot be pushed to directly.

**☑ M-03 GitHub Actions CI** · KK · M-02 · `feature/M-03-ci`
- Do: `.github/workflows/ci.yml` that runs `scripts/check.sh` (full) on pull requests into `develop`
  and `main`. Linux runners only. Cancel a run when a newer push replaces it. Cache Gradle and pnpm.
  See CONTRIBUTING.md on why this costs no minutes while the repo is public.
- Done: the first pull request shows the CI check, and it is required by branch protection. The
  Actions usage page shows the run did not count against the account's minutes.

**☑ M-04 Local stack** · KK · M-01 · `feature/M-04-compose`
- Do: `infra/docker-compose.yml` with Postgres (pinned version) holding three databases or schemas
  (`msika`, `zamsim`, `elevysim`), each with its own user. Flyway in each Spring app with an empty
  first migration. Two Postgres roles for Msika: `msika_owner` (runs migrations) and `msika_app`
  (the app; no table ownership, no `BYPASSRLS`). Testcontainers set up for integration tests.
- Done: `docker compose up` starts everything and the health endpoints report the database as up.
  An integration test boots `msika-api` against Testcontainers Postgres.

**☑ M-05 Architecture tests and decisions log** · SK · M-01 · `feature/M-05-arch-tests`
- Do: ArchUnit test in `msika-api` enforcing the dependency rules in ARCHITECTURE §3 (core and
  ledger import no Spring, JPA, Jackson or HTTP clients; nothing in Msika imports `zamsim` or
  `elevysim`). `docs/decisions/` with short records for: monorepo, simulators as separate services,
  money as `long` ngwee, append-only ledger, RLS for tenancy.
- Done: adding `import org.springframework...` to a class in `msika-core` fails the build.

## Phase 1: Core record (weeks 1 to 2)

Goal: councils, markets, stalls, traders and agents exist, and the ledger records and seals entries.

**☑ M-06 Ledger module** · SK · M-01 · `feature/M-06-ledger`
- Do: copy `Money`, `MerkleAccumulator`, `SignedTreeHead`, `SignatureScheme`,
  `EcdsaSignatureScheme`, `InclusionProof`, `ConsistencyProof` and their tests from SKACE (ARCHITECTURE
  §8) into `msika-ledger`, package `zm.msika.ledger`. On Samuel's machine the sources are in
  `C:\Users\Samuel\Desktop\Projects\SKACE CENTRAL BANKING\skace-core\domain\src\main\java\zm\skace\domain\`
  (`ledger\` and `integrity\`), with tests under the matching `src\test` folder. Copy only these
  classes and whatever small types they need to compile (such as `Currency`). Write `SOURCE.md`. Add `LedgerEntry` (with kind,
  provenance, amount, business date, idempotency key, optional reversed entry) and `DailySeal`
  (council, date, count, root, previous seal hash, signature) with `seal(...)` and `verify(...)`.
- Done: tests prove that changing, removing or reordering any entry in a sealed day makes `verify`
  fail, that a broken chain between two days is detected, and that `Money` refuses to mix
  currencies or overflow.

**☑ M-07 Councils, markets and stalls** · SK · M-04, M-05 · `feature/M-07-stalls`
- Do: domain model and use cases in `msika-core`; tables, Flyway migrations and repositories in
  `msika-api`. Stall codes like `SOW-B-114`. Each stall gets a random `qr_token`. REST endpoints for
  operators and council admins to create markets, create stalls in bulk (CSV upload), and allocate a
  stall to a trader.
- Done: integration tests for create, bulk import (including a CSV with a bad row, which is
  rejected with the row number), allocate and free a stall.

**☑ M-08 Row-level security** · KK · M-07 · `feature/M-08-rls`
- Do: RLS policies on every council table using `current_setting('app.council_id')`. A transaction
  helper sets `SET LOCAL app.council_id` from the signed-in user or the job's council. Jobs loop over
  councils.
- Done: an integration test with two councils proves that council A's user, a council A job and a
  hand-written query without `WHERE` all see none of council B's rows. A test fails if any table
  with a `council_id` column has no policy.

**☑ M-09 Council users and security** · KK · M-07 · `feature/M-09-auth`
- Do: ARCHITECTURE §9. Sign-in, JWT, user row re-read per request, roles, default deny, audit log
  for every change.
- Done: tests for wrong password, deactivated user (refused on the very next request), officer
  trying to change something (403), and an endpoint with no rule (403).

**☑ M-10 Traders, agents and stall QR plates** · TM · M-07 · `feature/M-10-traders`
- Do: register traders and agents (council side). Phone numbers stored in E.164 and masked in logs.
  Generate a printable A6 PDF plate per stall with the code, market name and a QR code holding the
  stall code and token.
- Done: tests for phone normalisation (`0977...`, `260977...`, `+260977...` give the same number)
  and for masking. A market's plates download as one PDF.

**☑ M-11 Recording entries and day close** · SK · M-06, M-08 · `feature/M-11-day-close`
- Do: a `RecordEntry` use case that is the only way to write a ledger entry (enforces idempotency
  and business date). A database trigger that refuses `UPDATE` and `DELETE` on `ledger_entry`. A
  scheduled day-close job per council (ARCHITECTURE §4.4), plus an operator endpoint to run it by
  hand. A `GET /api/v1/seals/{date}` endpoint and a `scripts/verify-seal` tool.
- Done: recording the same idempotency key twice gives one entry. An `UPDATE` on the ledger fails
  at the database. Day close is safe to run twice. The verify tool fails on a hand-edited database.

## Phase 2: Payments simulator (weeks 2 to 3)

Goal: money moves between wallets in `zamsim`, and Msika records it correctly even when the
simulator misbehaves.

**☑ M-12 zamsim wallets and transfers** · KK · M-04 · `feature/M-12-zamsim-core`
- Do: ARCHITECTURE §6. Wallets, cash-in, transfers, transaction status, statements. Its own
  double-entry ledger, so the total of all wallets only changes by cash-in and cash-out.
  `Idempotency-Key` on every write.
- Done: tests prove that balances never go negative, money is conserved across 1,000 random
  transfers, and a repeated key returns the first result.

**☑ M-13 zamsim callbacks and fault injection** · KK · M-12 · `feature/M-13-zamsim-callbacks`
- Do: signed callbacks with retries. Settings for failure rate, delay, duplicate and out-of-order
  callbacks. Request-to-pay that waits for approval in the console.
- Done: tests show the signature, the retry after a 500 from the receiver, and each fault mode.

**☑ M-14 Payments adapter in Msika** · TM · M-11, M-13 · `feature/M-14-payments-adapter`
- Do: `PaymentsPort` in `msika-core`; an HTTP adapter for `zamsim` in `msika-api`; the callback
  endpoint that checks the signature and timestamp. Every callback goes through `RecordEntry`.
- Done: with all fault modes turned on, a run of 500 simulated deposits ends with exactly the right
  ledger, no duplicates and nothing lost.

**☑ M-15 Deposits** · TM · M-14, M-10 · `feature/M-15-deposits`
- Do: the deposit lifecycle (PENDING, CONFIRMED, FAILED, CANCELLED, DISPUTED) and the flow in
  ARCHITECTURE §4.1, through a REST endpoint for now (SMS comes in Phase 3). Merchant sales and
  supplier payments from `zamsim` recorded as `VERIFIED` (ARCHITECTURE §4.2).
- Done: tests for each state change, including a callback that arrives after the agent cancelled.

**☑ M-16 Nightly reconciliation** · KK · M-15 · `feature/M-16-reconciliation`
- Do: a job that compares each council's ledger for the day with `zamsim` statements and lists every
  difference (missing in Msika, missing in zamsim, amount differs).
- Done: tests that plant each kind of difference and check it is reported. The dashboard can show
  the list later (M-27).

## Phase 3: SMS channel (weeks 3 to 4)

Goal: traders and agents do everything in SMS-PROTOCOL.md from a phone.

**☑ M-17 SMS command parser** · TM · M-01 · `feature/M-17-sms-parser`
- Do: a pure parser in `msika-core` that turns a message body into a command object or a parse
  error. No database, no Spring.
- Done: a test for every example in SMS-PROTOCOL.md, plus amounts with commas, K prefix, decimals,
  zero, negative and too large.

**☑ M-18 Inbound webhook and outbox** · TM · M-09, M-17 · `feature/M-18-sms-inbound`
- Do: the webhook in SMS-PROTOCOL.md (store, then process), `SmsGatewayPort`, the outbox table and
  a worker that sends with retries. Reply templates in one place, each checked for 160 GSM-7
  characters. Rate limiting.
- Done: a repeated `gatewayMessageId` is processed once. A bad signature gets 401. A test fails if
  any template can go over 160 characters with the longest values.

**☑ M-19 Trader and agent commands** · TM · M-15, M-18 · `feature/M-19-sms-commands`
- Do: wire every command in SMS-PROTOCOL.md to its use case, including registration with consent,
  `DEP` going through the deposit flow, `NO` disputing a deposit, and the confirmation SMS to the
  trader.
- Done: an end-to-end test sends `DEP B114 450` from an agent number and ends with a confirmed
  deposit, a `DEPOSITED` ledger entry and the right SMS in the trader's outbox.

**☑ M-20 Sim console** · TM · M-19 · `feature/M-20-sim-console`
- Do: `apps/sim-console`: a page of fake phones (pick a trader or agent, type a message, see
  replies), the zamsim wallets and transactions, request-to-pay approval, and the fault settings.
  The `ConsoleSmsGateway` adapter feeds it.
- Done: the whole deposit flow can be shown in a browser with no real phone.

**☐ M-21 SMS relay to the VPS** · TM · M-18, M-45 · `feature/M-21-sms-relay`
- Do: ARCHITECTURE §7, "Getting SMS to a cloud server". Msika runs on a cloud VPS, which cannot hold
  a SIM, so a **relay** in Lusaka holds the Zamtel SIM and talks to the VPS over HTTPS, always
  connecting out (no open ports at the relay's end). Build:
  - the relay's two endpoints on the VPS: inbound `POST /api/v1/sms/inbound` (already from M-18) and
    `GET /api/v1/sms/relay/outbox` plus `POST /api/v1/sms/relay/outbox/{id}/result`, where the relay
    collects replies to send and reports each one sent or failed. Both signed with the relay's own
    HMAC secret, and each relay sees only its own messages;
  - `RelaySmsGateway`, the adapter that queues replies for the relay instead of sending them itself;
  - the relay itself, two options sharing that protocol, documented in `docs/SMS-GATEWAY-SETUP.md`:
    (a) a small script on a Raspberry Pi or old laptop with a USB GSM modem, using Gammu's SMS
    daemon to read and send texts; (b) an Android phone running an open-source SMS-gateway app,
    with a small mapping endpoint if its webhook format differs. Option (a) is preferred because it
    restarts by itself after a power cut;
  - relay health on `/health`: last contact time, and messages waiting more than 2 minutes.
- Done: an integration test plays the relay (inbound, then collecting and acknowledging the reply)
  with a duplicate inbound and a reply reported failed and retried. A real text from a team
  member's phone reaches `sms_inbound` on the VPS and gets a reply (people's work: needs the SIM and
  the device), recorded as a short video for the pitch.

## Phase 4: Levy and the eLevy simulator (weeks 4 to 5)

Goal: levy is charged daily and paid automatically from deposits, and the demo can show arrears
with and without Msika.

**☑ M-22 elevysim** · KK · M-04 · `feature/M-22-elevysim`
- Do: ARCHITECTURE §6, elevysim. Accounts, top-ups, daily charge, arrears.
- Done: tests for charge with enough balance, charge into arrears, and a top-up clearing arrears.

**☑ M-23 Levy rules** · MP + WM · M-07 · `feature/M-23-levy-rules` · code done; **real amounts still to research** (`docs/research/levy.md` §1)
- Do: MP and WM find out the real daily levy amounts for Lusaka markets and stall classes and write
  them up in `docs/research/levy.md` with sources. TM or an assistant builds `levy_rule` and the
  daily `levy_charge` job from them.
- Done: charges for a seeded market match a table worked out by hand in the research note.

**☑ M-24 Levy sweep** · SK · M-15, M-22, M-23 · `feature/M-24-levy-sweep`
- Do: ARCHITECTURE §4.3. On a confirmed deposit, pay outstanding levy: transfer in zamsim, then
  top-up in elevysim, both through ports and `payment_instruction` rows with idempotency keys.
  Only for traders who gave consent.
- Done: tests for a deposit smaller than the levy owed, a transfer that fails, an elevysim top-up
  that fails after the money moved (it is retried, never paid twice), and a trader without consent
  (nothing is swept).

**☑ M-25 Levy receipts and arrears** · TM · M-24 · `feature/M-25-levy-receipts`
- Do: `LEVY` and `BAL` replies use real figures. A weekly SMS receipt of levy paid.
- Done: replies match the elevysim account after a seeded week.

## Phase 5: Council dashboard and the demo (weeks 5 to 6)

Goal: a council officer sees their markets, and the full story can be shown in 7 minutes.

**☑ M-26 Dashboard shell and sign-in** · TM · M-09 · `feature/M-26-dashboard-shell`
- Do: `council-web` with sign-in, layout, navigation and a typed API client generated from the
  OpenAPI spec of `msika-api`.
- Done: sign in, sign out, an expired session goes back to sign-in, an officer sees no edit buttons.

**☑ M-27 Collections and occupancy** · TM · M-26, M-24 · `feature/M-27-collections`
- Do: pages for markets (stalls vacant, allocated and active today), collections (deposits and levy
  per day and per market), arrears, agents (deposits, disputes, reconciliation differences), and
  the daily seals with a "verify" button.
- Done: figures match direct SQL on the seeded demo market. Works at phone width.

### Phase 5M: Market map (design in `docs/MARKET-MAP.md`)

Goal: a council officer sees every stall drawn where it stands, coloured by whether it is free,
active, quiet or suspended, can find any trader on it, and can watch the closing round live.

**☑ M-48 Market plans and generated layouts** · SK · M-08, M-10 · `feature/M-48-market-plans`
- Do: MARKET-MAP §4, §5.1 and §10. The geometry types in `zm.msika.core.map` (integer millimetres,
  no outside library), the plan tables with RLS and the trigger that freezes published plans, the
  publish checks that report every blocker at once, and the generator that lays out a plan from
  stall codes with a per-market numbering configuration. Draft, publish and read endpoints. Decision
  records for millimetre geometry and for no PostGIS.
- Done: tests for polygon validity (self-crossing, too few corners, zero area), overlap with and
  without the 50 mm tolerance, a publish that fails with four different blockers listing all four,
  an `UPDATE` on a published footprint failing at the database, and a generated plan for a
  300-stall market with every stall placed, no overlaps and no duplicate addresses.

**☑ M-49 Map status** · SK · M-48, M-15, M-24 · `feature/M-49-map-status`
- Do: MARKET-MAP §7 and the `map-status`, `panel` and `search` endpoints in §11. States worked out
  from the register and activity, never stored. Trader names and phones shown by role (officers see
  masked phones).
- Done: a test for each state and each levy band, including the edges (exactly 7 days quiet). The
  status of a 5,000-stall market comes back in under 300 ms with one query (measured in a test that
  prints the time). Search finds a trader by name, by `0977...` and by `+260977...`.

**☑ M-50 Market map page** · TM · M-26, M-49 · `feature/M-50-market-map`
- Do: MARKET-MAP §8.1 to §8.3 and §8.8 in `council-web`: the SVG plan with pan and zoom, layers,
  legend with counts, patterns as well as colours, dashed outlines for generated footprints, the
  stall panel, search, filters, the unmapped list, and the list view at phone width. Decision record
  for SVG without a map library.
- Done: component tests for the state-to-style mapping and for search highlighting. A generated
  5,000-stall market first draws in under one second and the result is written in the pull request
  with the machine it ran on. Works at 375 px wide as a list.

**☑ M-51 Live closing view** · TM · M-50, M-19 · `feature/M-51-live-closing`
- Do: MARKET-MAP §8.4. Server-sent events when a deposit is confirmed, the map recolouring stall by
  stall, and the agent-round overlay (done tonight, not reached yet).
- Done: an integration test sends three `DEP` messages and receives three events in order. The page
  reconnects after the server restarts without reloading.

### Phase 5A: Council approvals (design in `docs/COUNCIL-ADMIN.md`)

Goal: every decision a council makes waits in one inbox with its evidence, is decided by the right
person (never the one who raised it), and changes the register only when approved.

**◐ M-61 Roles, market scopes and separation of duties** · KK · M-09 · `feature/M-61-roles`
- Do: COUNCIL-ADMIN §3 and the `user_role` table in §9. Several roles per user, market scopes, the
  new roles, the permission matrix in code re-seeded on start, the test that every endpoint has a
  permission mapped, the rules in §3.3, and a migration that moves each existing user's role across.
  Decision record for scoped roles.
- Done: tests that a market master of market A cannot act in market B, an auditor can read
  everything and change nothing, a user cannot change their own roles, an enforcement officer
  cannot be given the revenue role, and an endpoint with no mapping fails the build.

**☐ M-62 The request engine** · SK · M-61 · `feature/M-62-requests`
- Do: COUNCIL-ADMIN §4.1, §4.3, §4.4 and §8. Requests, evidence, policies with code minimums,
  append-only decisions, effects run in the deciding transaction, deadlines, escalation and
  hand-over, bulk decisions with sampling. Start with two kinds to prove it (stall change, agent
  suspension). Decision record for one engine.
- Done: tests that the raiser cannot decide (refused by the database too), one person cannot give
  both of two approvals, an effect that fails leaves the request open with its reason, a decision
  row cannot be updated or deleted, an overdue request escalates, and a council cannot loosen a
  policy below its minimum.

**☐ M-63 The inbox page** · TM · M-62, M-26 · `feature/M-63-inbox`
- Do: COUNCIL-ADMIN §4.5 in `council-web`: my queue, raised by me, overdue, the request page with the
  proposed effect in words, evidence linking to the market map, and decisions with reasons.
- Done: component tests for each view; a rejection cannot be sent without a reason; works at phone
  width.

**☐ M-64 Registrations, stall and allocation requests, and disputes** · SK · M-62, M-19 · `feature/M-64-request-kinds`
- Do: the request kinds in COUNCIL-ADMIN §4.2 from trader registration to statement dispute,
  including agent registration. `REG`, `NO`, `WRONG`, `TRANSFER`, `SURRENDER` and `STATUS` by SMS
  raise or report on them (added to `docs/SMS-PROTOCOL.md`). New stalls queue their plates for
  printing.
- Done: an end-to-end test for each kind from raising to its effect, including a transfer where the
  second trader never confirms and a deposit dispute that is upheld (reversal entry recorded, agent
  case raised once M-67 exists, or a follow-up noted until then).

**☐ M-28 Synthetic demo market** · WM · M-19, M-48 · `feature/M-28-seed`
- Do: `scripts/seed/` builds one council, two markets, 300 stalls, 220 traders and 8 agents with 60
  days of realistic history: market days, rainy days, some traders who stop depositing, one agent
  with suspicious patterns. All names and numbers made up, with the reasoning in
  `docs/research/synthetic-data.md`. Each market gets a published generated plan (M-48) with
  sections, aisles, gates and toilets, so the map looks like a market.
- Done: one command resets the demo to a known state. Figures look plausible to MP and WM.

**☐ M-29 Demo script** · MP · M-20, M-27, M-28, M-51 · `feature/M-29-demo-script`
- Do: `docs/DEMO.md`: a 7-minute walk through stall plate, agent `DEP` by SMS, the stall turning
  colour on the live market map, trader confirmation, levy paid, eLevy account with no arrears beside
  one without Msika, council dashboard, sealed day verified. A fallback for each step if the network
  fails.
- Done: two full rehearsals by someone who did not write the code.

**☐ M-30 Release v0.1.0** · SK · all above, including M-48 to M-51 and M-61 to M-64 · `release/0.1.0`
- Do: the release branch from `develop`, fixes only, merge to `main`, tag `v0.1.0`, merge back to
  `develop`. CHANGELOG entry.
- Done: a clean clone of the tag runs the demo from the README alone.

---

## MVP line. Everything below is after v0.1.0.

## Phase 6: Turnover statements (week 7)

**☐ M-31 Monthly statement** · SK · M-30 · `feature/M-31-statement`
- Do: ARCHITECTURE §4.5. Verified, deposited and declared totals kept apart, plus levy paid.
- Done: totals match the ledger, and a declared figure never appears under verified.

**☐ M-32 Statement by SMS, PDF and CSV** · TM · M-31 · `feature/M-32-statement-export`
- Do: `MONTH`, `OK`, `WRONG`. PDF and CSV for traders (through the council) and councils.
- Done: a disputed statement shows on the dashboard.

**☐ M-33 Tax guidance note** · MP · M-31 · `feature/M-33-tax-note`
- Do: research what an informal trader at this turnover owes ZRA (turnover tax, TPIN registration)
  and write plain guidance for `docs/research/zra.md` and an SMS help text. Sources cited.
- Done: reviewed by someone with tax knowledge, or marked clearly as unreviewed.

## Phase 7: Credit and intelligence (weeks 7 to 9)

**☐ M-34 ML service skeleton** · WM · M-28 · `feature/M-34-ml-skeleton`
- Do: `ml/` FastAPI service, pinned dependencies, ruff and pytest in `scripts/check`. It reads a
  read-only export of ledger data, never the live database.
- Done: health endpoint, one test, included in docker compose.

**☐ M-35 Daily sales estimate** · WM · M-34 · `feature/M-35-sales-estimate`
- Do: estimate a trader's true daily sales from deposits, digital sales, supplier purchases,
  declared sales and market-day patterns. Start simple (a regression), report error on held-out
  synthetic traders.
- Done: a short evaluation note with the error and its limits.

**☐ M-36 Explainable credit score** · WM · M-35 · `feature/M-36-credit-score`
- Do: a scorecard (points per reason) a trader could understand: regular deposits, months active,
  levy kept current, repayment history. Each score comes with its top three reasons.
- Done: the same history always gives the same score and reasons.

**☐ M-37 Agent and stall anomalies** · WM · M-34 · `feature/M-37-anomalies`
- Do: flag agents with many disputes, unusual timing or amounts, and stalls whose depositor phone
  changes often (possible subletting). Flags go to the dashboard for a person to check, never
  automatic action.
- Done: finds the suspicious agent planted in M-28.

**☐ M-38 Stock loans through a lender** · SK · M-36 · `feature/M-38-loans`
- Do: ARCHITECTURE §4.6. `LenderPort`, a lender account and simple approval rules in zamsim, loan
  referral, payment straight to the supplier wallet, repayment as a share of each deposit, limits
  that grow after each repaid loan.
- Done: tests show Msika never holds loan money, and repayment stops at exactly the amount owed.

**☐ M-39 Chilimba groups** · TM · M-38 · `feature/M-39-chilimba`
- Do: a savings group of traders with a group wallet, rotating payouts, and members guaranteeing each
  other's loans.
- Done: a full rotation for a 5-member group runs in tests.

## Phase 8: Agent app and WhatsApp (stretch, week 9 onwards)

**☐ M-40 Agent app** · TM · M-19 · `feature/M-40-agent-app`
- Do: React Native (Expo): scan a stall QR plate, enter an amount, confirm, see today's round.
  Works offline and syncs later with the same idempotency keys.
- Done: a deposit made offline syncs once and only once.

**☐ M-41 WhatsApp channel** · TM · M-18 · `feature/M-41-whatsapp`
- Do: the WhatsApp Business Cloud API as another adapter behind the same command handling as SMS.
- Done: every SMS command works on WhatsApp.

**☐ M-42 Nyanja and Bemba** · MP + WM · M-19 · `feature/M-42-languages`
- Do: keywords and reply templates in both languages, checked by native speakers.
- Done: replies still fit 160 characters.

## Phase 9: Pilot readiness (week 10 onwards)

**☐ M-43 Data protection review** · KK · v0.1.0 · `feature/M-43-dpa`
- Do: `docs/DATA-PROTECTION.md`: what we hold, why, for how long, who sees it, consent wording, how a
  trader gets their data or leaves.
- Done: every column of personal data in the schema is listed in it.

**☐ M-44 Security review** · KK · v0.1.0 · `feature/M-44-security`
- Do: dependency audit in `scripts/check`, a review of auth, webhooks, RLS and secrets handling.
- Done: findings fixed or written down with a reason.

**☐ M-45 Hosting on Dokploy** · KK · M-04 · `feature/M-45-hosting`
- Built early, before M-21, because the SMS relay needs a public HTTPS address to post to.
- Do: run Msika on the team's VPS under Dokploy, as a Docker Compose app: `msika-api`, `zamsim`,
  `elevysim`, Postgres and the two web apps. Dokploy's Traefik gives each public service a domain
  with HTTPS. Every secret (database passwords, JWT secret, callback and relay secrets, the seal
  signing key) is set in Dokploy's environment settings, never in the repo (the repo is public).
  Only `msika-api` and the web apps are public; Postgres, zamsim and elevysim stay on the internal
  network. Daily Postgres backups to storage off the VPS, and one restore tried for real. Dokploy
  deploys `main` when it changes, which gives us deployment without spending GitHub Actions
  minutes. Write `docs/DEPLOY.md`.
- Done: the demo runs on the VPS from `main`. `/health` answers over HTTPS. A port scan from outside
  shows only 80 and 443 (and SSH). A backup has been restored into a fresh database and the seal
  verifier passes on it (a written manual check).

**☐ M-46 Real integration notes** · KK + MP · v0.1.0 · `feature/M-46-integration-notes`
- Do: for Zamtel and eLevy, what we need from them (sandbox, keys, callback format), mapped to our
  ports, so a partner meeting can end with a clear request.
- Done: one page each, reviewed by SK.

**☐ M-47 Pilot plan** · MP + WM · v0.1.0 · `feature/M-47-pilot`
- Do: market, number of traders and agents, survey before and after, what we measure (deposit
  rate, levy arrears, disputes).
- Done: agreed by the whole team.

## Phase 10: Measured market map (after v0.1.0)

Goal: the map stops being a schematic. Markets are surveyed by walking them with a phone, plans are
traced and tied to the city map, and the ground is compared with the register. Design in
`docs/MARKET-MAP.md` §5 to §9.

**☐ M-52 Survey simulation** · SK + WM · M-48 · `feature/M-52-survey-simulation`
- Do: MARKET-MAP §6.1 and §6.4. Copy the needed capture-kit modules from SKACE Architect (commit
  `c39ccb5`, on Samuel's machine at
  `C:\Users\Samuel\Desktop\Projects\SKACE Architect\capture-kit\src\`) into
  `apps/market-survey/src/kit/` with their tests and a `SOURCE.md`. Build a simulated market and a
  simulated surveyor with noise and drift, run 100 seeded walks per mode (tracked, compass and tilt,
  topology-only), with and without loop closure. Also simulate repeated walks of one market (full
  surveys and patrols, with a stall moved partway through) for M-59 to measure against (MARKET-MAP
  §6.6). Write the results in `docs/research/survey-accuracy.md`, labelled as simulation. Decision
  record for copying the kit.
- Done: with zero noise the market is reproduced within 2 cm. The tables are reproducible from the
  seeds with one command. The tracked mode meets the §6.4 target (0.75 m at the 95th percentile
  within a section) or the note says plainly that it does not and by how much.

**☐ M-53 Market walk app** · TM · M-52, M-10 · `feature/M-53-market-walk`
- Do: MARKET-MAP §6.2. `apps/market-survey`: a phone-first page that scans plates, takes readings
  (tracked or compass and tilt), records stall states and unregistered structures, ends aisles at
  anchors, offers topology-only mode, keeps everything on the phone until upload, and exports one
  survey JSON.
- Done: the simulated surveyor from M-52 drives the app's state machine in tests from start to
  upload. A field test on a real Android phone in a real market (people's work, not Claude's)
  checks at least 30 stalls against a tape measure, with the results added to
  `docs/research/survey-accuracy.md` beside the simulation.

**☐ M-54 Survey import and the reconciliation report** · SK · M-53, M-48 · `feature/M-54-reconciliation`
- Do: MARKET-MAP §6.3 and §6.5. Upload, the aisle chain and network adjustment in Java with
  uncertainties, snapping shared stall sides, the reconciliation report, and applying a survey to a
  draft plan as `SURVEYED` footprints. Officer actions on each finding, audited. Decision record:
  observations never change the register by themselves.
- Done: a test for each finding in the §6.5 table, planted in a simulated survey. The adjustment's
  uncertainties shrink when a loop is closed and grow along an open aisle. No register row changes
  when a report is produced.

**☐ M-55 Georeferencing and the city overview** · KK · M-48, M-50 · `feature/M-55-georeference`
- Do: MARKET-MAP §5.3 and §8.7. Control points, the similarity transform with residuals, GeoJSON
  export, and the Leaflet city overview with OpenStreetMap attribution.
- Done: tests for two points (exact fit), three points with one bad point (its residual stands out),
  and one point (refused with a clear message). Exported GeoJSON opens correctly in a GIS tool
  (a written manual check).

**☐ M-56 Plan editor and tracing** · TM · M-48, M-50 · `feature/M-56-plan-editor`
- Do: MARKET-MAP §5.2 and §5.5. Upload a background, set its scale from a known length, draw and
  move footprints, the "row of N stalls" tool, sections, aisles and features, save the draft, and
  publish with the blocker list shown.
- Done: a traced footprint carries the uncertainty from §5.2. Publishing a draft with an overlap
  shows the overlap on the drawing. Editing a published plan is impossible from the page and from
  the API.

**☐ M-57 History and print sheets** · TM · M-50 · `feature/M-57-history-print`
- Do: MARKET-MAP §8.5 and §8.6. The date control, and A3 and A4 sheets at 1:200 and 1:500 with
  title block, legend with counts and scale bar, plus the register list.
- Done: the map on a past date shows the trader who held a stall then, not now. A printed scale bar
  measures true with a ruler (a written manual check, with the printer named).

**☐ M-58 Allocation from the map and the waiting list** · SK + MP · M-50, M-19 · `feature/M-58-allocation`
- Do: MARKET-MAP §9. The waiting list, `APPLY`, `PLACE` and `FREE` by SMS (added to
  `docs/SMS-PROTOCOL.md` with their replies), allocating selected stalls to the next applicants from
  the map, the allocation SMS, and the public free-stalls page.
- Done: applicants are offered stalls in the order they applied within each class. The public page
  and the `FREE` reply contain no name or phone number (a test checks the response bodies). Every
  allocation is in the audit log.

**☐ M-59 Plans that improve with every walk** · SK · M-54 · `feature/M-59-plan-refinement`
- Do: MARKET-MAP §6.6. Keep every walk's raw readings and path. Fit each new walk onto the plan
  through the stalls it scanned (weighted, with outliers rejected), fuse all readings by weight with
  ageing, re-adjust the network, detect changed stalls and raise flags, derive aisle lines from the
  bundle of walked paths, and produce a plan proposal with its diff after each walk. Publish
  refinement-only proposals automatically where the council allows it. The map shows each stall's
  uncertainty and the age of its evidence. Decision record for evidence-based footprints.
- Done: on M-52's repeated-walk simulation, the 95th percentile corner error after five walks is at
  most about half that after one, the moved stall is flagged within three walks, and no unchanged
  stall is flagged (or the note says by how much the targets were missed). Working the plan out
  again from the raw readings gives the same result. A proposal with a topology change is never
  published automatically. A walk where most tie points are rejected is not used for refinement.

**☐ M-60 Patrol mode and walk-next guidance** · TM · M-53, M-59 · `feature/M-60-patrol-mode`
- Do: MARKET-MAP §6.2 item 7 and §6.6. Patrol mode in `apps/market-survey` (scan plates in passing,
  path recorded, nothing else asked), plate scans from the agent app (M-40) sent as evidence when it
  exists, and the "walk next" list and route from `GET /api/v1/markets/{id}/walk-next`.
- Done: a simulated patrol of 50 plate scans uploads and lowers the uncertainty of the scanned stalls
  on the next proposal. The walk-next list puts a never-walked aisle and an open change flag ahead of
  a well-measured stall.

## Phase 11: Enforcement and oversight (after v0.1.0)

Goal: when a by-law is broken, the council follows a fair process that Msika records from notice to
appeal, using only evidence that does not punish traders for depositing. Design in
`docs/COUNCIL-ADMIN.md` §2, §5 to §8. Nothing here is piloted before the legal review in M-43.

**☐ M-65 Levy adjustments and rule changes** · SK + MP · M-62, M-24 · `feature/M-65-levy-requests`
- Do: the levy adjustment and levy rule change kinds in COUNCIL-ADMIN §4.2: waivers, exemptions and
  closed-market days as compensating entries in Msika and elevysim, and rule changes with a
  resolution reference, a future start date and the advance notice SMS.
- Done: a waived day leaves both levy records consistent, nothing is edited, and a rule change
  cannot start sooner than its notice period.

**☐ M-66 Offence catalogue and evidence policy** · MP + SK · M-62 · `feature/M-66-offences`
- Do: COUNCIL-ADMIN §5.1 and §5.3. The catalogue with versions and legal references, changed only
  through an approved request, and the evidence policy in code. MP researches real by-laws in
  `docs/research/by-laws.md` with sources; until then only an obviously made-up demo catalogue
  exists. Decision record: deposits and income are never evidence.
- Done: a case cannot be decided with only disallowed evidence (a test tries each kind in the
  right-hand column of §5.3). A case is judged by the catalogue version in force on the offence date.

**☐ M-67 Enforcement cases** · SK · M-66, M-19 · `feature/M-67-cases`
- Do: COUNCIL-ADMIN §5.2 and §6: the case lifecycle, notices, `APPEAL` and `CASES` by SMS, proposals,
  decisions by a different person, second approval for suspension and revocation, appeals to someone
  not involved, and inspections recorded from patrol mode. Agent cases from upheld deposit disputes.
- Done: an end-to-end test of a case from opening to a decided appeal. Tests that a case cannot be
  decided before its response deadline unless the trader responded, that a notice must be sent
  first, and that the opener and the appeal decider can never be the same person as the decider.

**☐ M-68 Fine payments** · TM · M-67, M-14 · `feature/M-68-fines`
- Do: COUNCIL-ADMIN §5.2 step 7. `PAY` by SMS, the payment request to the wallet, the `FINE_PAYMENT`
  ledger kind (ARCHITECTURE §5 updated), statements showing fines apart from levy, overdue marking,
  and fine waivers through a request. Decision record: fines only through the wallet, never swept.
- Done: a test that no deposit or levy sweep ever pays a fine, a paid fine's ledger entry and receipt,
  and an overdue fine that changes nothing else by itself.

**☐ M-69 Oversight reports and audit export** · WM · M-67 · `feature/M-69-oversight`
- Do: COUNCIL-ADMIN §5.4 and the §8 export, for auditors and admins only.
- Done: the reports find a planted officer whose cases cluster on three traders and are mostly
  overturned; an officer cannot see reports about themselves.

**☐ M-70 Support access** · KK · M-62 · `feature/M-70-support-access`
- Do: COUNCIL-ADMIN §7.
- Done: an operator sees nothing of a council without an approved window, sees it during the window
  with every read audited, and nothing after it ends or is ended early.
