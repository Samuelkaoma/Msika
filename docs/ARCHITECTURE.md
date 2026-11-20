# Msika architecture

Msika turns a market trader's cash day into a record. The trader's cash goes into a mobile money
wallet at closing time. Msika records it against the stall, pays the council levy out of it, builds a
monthly turnover statement, and later uses the history to support credit.

This file describes the system we are building. `docs/ROADMAP.md` says in what order. `AGENTS.md`
holds the rules every change must follow.

---

## 1. What is real and what is simulated

We have no access to eLevy or to Zamtel Money's APIs. We build everything ourselves and put every
outside system behind an interface (a "port"), so the real system can replace the simulator later
by changing configuration, not code.

| Outside system | Today | Later | Port in `msika-core` |
|---|---|---|---|
| Zamtel Money (wallets, cash-in, merchant payments, transfers) | `zamsim`, our own simulator, a separate service called over HTTP | Zamtel's real API | `PaymentsPort` |
| eLevy (council levy accounts) | `elevysim`, our own simulator, a separate service called over HTTP | eLevy, once a council or the government agrees | `LevyAuthorityPort` |
| SMS (trader and agent messages) | Simulated phone console in dev; an Android phone used as an SMS gateway in the pilot | A bulk-SMS provider with two-way messaging or a Zamtel shortcode | `SmsGatewayPort` |
| Lender | A lender account inside `zamsim` that approves by simple rules | A licensed lender | `LenderPort` |
| ZRA | Nothing. We produce a statement the trader files themselves | Possible export in ZRA's format | none |

**The simulators are separate processes with their own databases.** Msika never reads a simulator's
tables, never imports simulator code, and treats every simulator response the way it would treat a
real one: it can be slow, fail, arrive twice, or arrive out of order. If Msika works against
`zamsim`, it is ready for Zamtel.

---

## 2. Rules the design rests on

1. **Msika never holds money and never lends.** Money only moves between wallets in the payments
   system (`zamsim`). Msika *asks* for a transfer the trader has agreed to, and records the result.
2. **Money is a whole number of ngwee.** `long` in Java, `bigint` in Postgres. No `double`, no
   `float`, no `BigDecimal` for stored amounts. K12.50 is `1250`.
3. **The ledger is append-only.** No `UPDATE` or `DELETE` on ledger rows, ever. A mistake is fixed by
   a new reversing entry that points at the one it reverses.
4. **Every ledger entry has a provenance label.**
   - `VERIFIED`: a digital payment the payments system confirmed (merchant sale, supplier payment).
   - `DEPOSITED`: cash the trader deposited through an agent, confirmed by the payments system.
   - `DECLARED`: something the trader told us by SMS that nothing has confirmed.
   Statements and the credit score always show the three separately. Declared figures never become
   verified by being repeated.
5. **Each day's ledger is sealed.** At day close, the day's entries for a council go into a Merkle
   tree, the root is signed, and the seal is chained to the previous day's seal. Any later change to
   a past entry breaks the chain, and anyone with the public key can check it.
6. **Each council sees only its own data.** Every table that holds council data has a `council_id`,
   and Postgres row-level security enforces it, so a forgotten `WHERE` clause cannot leak data.
7. **Everything that arrives from outside is idempotent.** SMS, payment callbacks and API commands
   all carry a unique key. Processing the same message twice has the same effect as processing it
   once.
8. **Personal data is kept to the minimum** (Data Protection Act 2021). We store a trader's phone
   number, name, stall and consent. No NRC numbers in the pilot. Logs mask phone numbers
   (`0977***739`). The repository is public, so **no real names, phone numbers or keys ever go in
   it**. Seed and test data are synthetic.

---

## 3. Repository layout (monorepo)

```
Msika/
├── platform/                      Gradle multi-module build, Java 21, Spring Boot
│   ├── msika-ledger/              Plain Java, no Spring. Money, ledger entries, Merkle tree,
│   │                              signed daily seal. Started from SKACE (see §8).
│   ├── msika-core/                Plain Java, no Spring. Domain model and use cases: councils,
│   │                              markets, stalls, traders, agents, deposits, levy, turnover,
│   │                              SMS command parsing. Defines the ports.
│   ├── msika-api/                 Spring Boot app. REST API, security, Postgres persistence
│   │                              (Flyway, JPA/JDBC), adapters for the ports, scheduled jobs.
│   ├── zamsim/                    Spring Boot app. Simulated Zamtel Money. Own database schema.
│   └── elevysim/                  Spring Boot app. Simulated eLevy. Own database schema.
├── apps/                          pnpm workspace
│   ├── council-web/               React + Vite + TypeScript. Council dashboard.
│   ├── sim-console/               React + Vite + TypeScript. Fake phones and wallets for demos.
│   └── agent-app/                 React Native (Expo). Phase 8, stretch.
├── ml/                            Python service (FastAPI). Phase 7.
├── infra/
│   └── docker-compose.yml         Postgres, msika-api, zamsim, elevysim, the web apps
├── scripts/
│   ├── check.ps1 / check.sh       The local quality gate (see CONTRIBUTING.md)
│   └── seed/                      Synthetic demo market
├── .githooks/pre-push             Runs the fast gate before every push
├── .github/workflows/ci.yml       Same checks on GitHub (see CONTRIBUTING.md on minutes)
└── docs/
```

Dependency direction, enforced by an ArchUnit test in `msika-api`:

```
msika-api  ──►  msika-core  ──►  msika-ledger
   │                ▲
   └── adapters ────┘   (adapters implement ports defined in msika-core)

zamsim, elevysim: depend on nothing in Msika. Msika depends on them only over HTTP.
```

`msika-core` and `msika-ledger` must not import Spring, JPA, Jackson or any HTTP client.

---

## 4. The main flows

### 4.1 Closing-time cash deposit (the heart of the system)

```
Agent at stall                  Msika (msika-api)                    zamsim
──────────────                  ─────────────────                    ──────
SMS "DEP B114 450"  ─────────►  parse, check agent and stall,
                                create Deposit (PENDING),
                                send cash-in request  ─────────────► debit agent float,
                                (idempotency key = deposit id)       credit trader wallet,
                                                                     callback (signed) ──┐
                                ◄──────────────────────────────────────────────────────┘
                                Deposit CONFIRMED,
                                ledger entry DEPOSITED K450,
                                levy sweep (4.3),
SMS to trader  ◄──────────────  "K450 deposited at B114 by agent
                                 Mwila. Ref 8F2K. Reply NO 8F2K
                                 if this is wrong."
```

If the trader replies `NO 8F2K`, the deposit is flagged for the council to review. This is the
first defence against agent fraud.

### 4.2 Digital payments

Merchant sales and supplier payments happen in `zamsim` (from the sim console). `zamsim` notifies
Msika through the same signed callback. Msika records them as `VERIFIED`, against the stall that
owns the merchant wallet.

### 4.3 Levy

- Each market has levy rules: an amount per stall class per trading day (configurable, because each
  council sets its own).
- Each trading day, Msika charges the levy to every allocated stall.
- When a deposit is confirmed, Msika pays outstanding levy out of it: it asks `zamsim` to move the
  amount from the trader's wallet to the council's collection wallet, then tells `elevysim` to credit
  the trader's levy account. The trader agreed to this once, at registration, and that consent is
  stored with a timestamp.
- The trader's levy account never goes into arrears while they deposit. If they do not deposit,
  arrears build up and the dashboard shows them.

### 4.4 Day close

A scheduled job runs per council at a set time (default 20:00, Africa/Lusaka):
1. Stops accepting entries for that business date. Anything later goes to the next day.
2. Builds the day's Merkle tree over the council's ledger entries, in id order.
3. Signs `(council, business date, entry count, root, previous seal hash)` with the council's key.
4. Stores the seal. The dashboard shows it and anyone can verify it.
5. Sends each trader who had activity a one-message daily summary.

### 4.5 Monthly turnover statement

On the 1st of each month Msika builds each trader's statement for the previous month: verified,
deposited and declared totals, levy paid, and an estimated turnover (from Phase 7 on). The trader
receives a summary by SMS and can reply `OK` or `WRONG`. The council and the trader can download the
statement as PDF and CSV. Msika does not file anything with ZRA.

### 4.6 Credit (Phase 7)

The ML service computes an estimated daily sales figure and an explainable score from the ledger.
A trader who qualifies can reply `LOAN` to ask for a stock loan. Msika sends a referral to the lender
(inside `zamsim`), the lender pays the supplier's wallet directly, and Msika sets up repayment as a
share of each later deposit. Msika never pays out the loan or holds any of the money.

---

## 5. Data model (first cut)

All tables except `council` have `council_id` and row-level security. Amounts are `bigint` ngwee.
Times are `timestamptz`. `business_date` is the Lusaka trading date.

| Table | Key columns |
|---|---|
| `council` | id, name, timezone, signing key id |
| `market` | id, council_id, name, location, day_close_time |
| `stall` | id, council_id, market_id, code (`SOW-B-114`), class, status (VACANT, ALLOCATED, SUSPENDED), qr_token |
| `trader` | id, council_id, msisdn, display_name, language, consent_levy_sweep_at, consent_data_at |
| `stall_allocation` | id, council_id, stall_id, trader_id, valid_from, valid_to |
| `agent` | id, council_id, msisdn, display_name, active |
| `ledger_entry` | id, council_id, stall_id, trader_id, kind, provenance, amount_ngwee, business_date, occurred_at, source_ref, idempotency_key (unique), reverses_entry_id |
| `daily_seal` | council_id, business_date, entry_count, merkle_root, prev_seal_hash, signature, key_id |
| `deposit` | id, council_id, stall_id, agent_id, amount_ngwee, status, payments_txn_id, disputed_at |
| `payment_instruction` | id, council_id, purpose (LEVY, LOAN_REPAYMENT), amount_ngwee, status, idempotency_key, payments_txn_id |
| `levy_rule` | id, council_id, market_id, stall_class, amount_ngwee, valid_from |
| `levy_charge` | id, council_id, stall_id, business_date, amount_ngwee, settled_by_instruction_id |
| `sms_inbound` | id, council_id (null until matched), gateway_message_id (unique), msisdn, body, received_at, command, outcome |
| `sms_outbound` | id, council_id, msisdn, body, status, attempts, next_attempt_at (an outbox) |
| `council_user` | id, council_id, email, password_hash, role |
| `audit_log` | id, council_id, actor, action, target, at |

`ledger_entry.kind`: `DEPOSIT`, `MERCHANT_SALE`, `SUPPLIER_PAYMENT`, `DECLARED_SALE`,
`LEVY_PAYMENT`, `LOAN_REPAYMENT`, `REVERSAL`.

The database user the app connects as owns no tables and has no `BYPASSRLS`. Migrations run as a
separate owner role. Each request or job sets `SET LOCAL app.council_id = ...` inside its
transaction. Jobs that work across councils loop over councils and set it for each one.

---

## 6. The simulators

### zamsim (simulated Zamtel Money)

Its own Spring Boot app and database schema. Its API is shaped like a typical mobile money
collections API, so the real integration later is an adapter swap:

| Endpoint | What it does |
|---|---|
| `POST /v1/wallets` | Create a wallet (trader, agent, merchant, council, lender, supplier) |
| `POST /v1/cash-in` | Agent puts cash into a customer wallet. Returns 202 and a transaction id; the result comes by callback |
| `POST /v1/request-to-pay` | Merchant asks a customer to pay. The customer approves in the sim console |
| `POST /v1/transfers` | Wallet to wallet, used for levy sweeps, loan disbursement and repayment |
| `GET /v1/transactions/{id}` | Status of one transaction |
| `GET /v1/statements?wallet=&from=&to=` | Used by Msika's nightly reconciliation |

- Every write needs an `Idempotency-Key` header. The same key returns the same result.
- Callbacks go to Msika's `POST /api/v1/payments/callback`, signed with HMAC-SHA256 over the body
  in an `X-Signature` header. Msika rejects unsigned or badly signed callbacks.
- **Fault injection**, set per environment: a failure rate, a callback delay, duplicate callbacks
  and callbacks out of order. Integration tests turn these on to prove Msika copes.
- Balances never go negative. A transfer without enough money fails cleanly.

### elevysim (simulated eLevy)

Models what we understand of eLevy: each trader has a prepaid levy account per council. The daily
levy is deducted from it. If the balance does not cover it, arrears build up.

| Endpoint | What it does |
|---|---|
| `POST /v1/levy-accounts` | Open an account for a trader at a market |
| `POST /v1/levy-accounts/{id}/top-ups` | Credit the account (Msika calls this after the money has moved in zamsim) |
| `GET /v1/levy-accounts/{id}` | Balance and arrears |
| `POST /v1/admin/run-daily-charge` | Deduct one day's levy from every account (scheduled, and callable in demos) |

This lets the demo show the before and after side by side: without Msika the account drifts into
arrears, with Msika it stays at zero arrears.

---

## 7. The SMS channel

See `docs/SMS-PROTOCOL.md` for the commands. The shape of it:

```
Phone ──► gateway ──► POST /api/v1/sms/inbound (signed) ──► sms_inbound row (unique gateway id)
                                                         ──► parse ──► use case ──► sms_outbound row
Outbox worker ──► SmsGatewayPort.send ──► gateway ──► phone
```

Gateway adapters:
- `ConsoleSmsGateway`: dev and demos. The sim console shows fake phones you can type into.
- `AndroidSmsGateway`: the pilot. An Android phone with a SIM runs an open-source SMS-gateway app
  that forwards incoming texts to the webhook and sends our replies. One phone per pilot market is
  enough.
- `ProviderSmsGateway`: later, a two-way bulk-SMS provider or a Zamtel shortcode.

The inbound endpoint answers `200` immediately after storing the message, and the processing
happens after the store, so a slow use case cannot make the gateway retry.

---

## 8. Code we start from

The ledger and seal code starts from **SKACE Central Banking** (Samuel's own project, Java 21):

- `skace-core/domain/.../domain/ledger/Money.java` (record of `long minorUnits` and currency)
- `skace-core/domain/.../domain/integrity/MerkleAccumulator.java` (RFC 6962 style: leaf prefix 0x00,
  node prefix 0x01)
- `skace-core/domain/.../domain/integrity/SignedTreeHead.java`, `SignatureScheme.java`,
  `EcdsaSignatureScheme.java`, `InclusionProof.java`, `ConsistencyProof.java`

We **copy** these into `msika-ledger` under the package `zm.msika.ledger`. We do not use a git
submodule. We record the SKACE commit they came from (`5fb7524`) in `platform/msika-ledger/SOURCE.md`
and adapt them freely after that. We bring their unit tests across with them.

Nothing is copied from Arcus. It is Go, not Java, and it is client work. Its *ideas* (default-deny
permissions, an audit row for every change, checking the user row on every request) are reused in
`msika-api`'s security design.

---

## 9. Security

- Council users sign in with email and password (BCrypt) and get a short-lived JWT. The user row is
  re-read on every request, so deactivating a user takes effect at once.
- Roles: `COUNCIL_ADMIN`, `COUNCIL_OFFICER` (read-only), `MSIKA_OPERATOR` (manages councils). Every
  endpoint is denied unless a rule allows it.
- Traders and agents are identified by phone number on SMS. A phone number that is not registered
  gets a registration reply and nothing else.
- Webhooks (SMS gateway, payment callbacks) are checked with HMAC signatures and a timestamp no older
  than 5 minutes.
- Signing keys for day seals live outside the repo (an environment variable or a key file on the
  server). The dev key is generated on first run and kept in a git-ignored folder.
- Every change a council user makes writes an `audit_log` row.
