# M-14: the payments adapter and the callback endpoint

Card: `M-14` · Branch: `feature/M-14-payments-adapter` · Depends on: M-11, M-13

## What we are building, in one paragraph

Msika can now ask the payments system to move money, and learn what happened. The *ask* goes out through a
**port** (`PaymentsPort`) implemented by an **adapter** that talks HTTP to `zamsim`. The *answer* comes back
as a **signed callback** to an endpoint that checks the signature and the timestamp, finds which request it is
about, and records the deposit on the ledger through `RecordEntry`. The proof is a run of 500 deposits
through a real `zamsim` with every fault switched on, ending with exactly the right ledger.

## New ideas in this lesson

### Port and adapter (again, now with a real one)
- **Port:** an interface in `msika-core` saying what Msika *needs* (`cashIn(...)`). It knows nothing of HTTP.
- **Adapter:** a class in `msika-api` (`ZamsimPaymentsAdapter`) that does the HTTP. Replacing zamsim with the
  real Zamtel Money later means writing another adapter, not touching the rules.
- The adapter speaks to zamsim only through its public HTTP shape. It never imports zamsim's code (rule 6).

### Save first, call second
The order of `PaymentService.startDeposit` matters:
1. **Save the request** (committed). Now there is a record of what we asked, whatever happens next.
2. **Call the payments system, outside any database transaction.** A slow reply then holds no locks.
3. **Remember its transaction id.**
If step 2 fails, the request stays `SENT` and can be sent again with the **same idempotency key**
(`msika-<request id>`), so the payments system moves the money once however many times it is asked.

### The reference: how a callback finds its request
We put `msika:<council id>:<request id>` in the payment's `reference`. The payments system echoes it back in
every callback. So the callback is matched by a value *we* chose, not by the payments system's own
transaction id, which Msika might not have learned yet if the callback is faster than the reply (it can be).
The reference also names the council, so we know which council's data to open.

### Verifying a callback (the receiver's half of M-13)
Three checks, in this order, before anything is read as data:
1. **Headers present** (`X-Timestamp`, `X-Signature`).
2. **Timestamp within 5 minutes**, past *and* future. Old: a captured callback replayed later. Future: a
   callback pre-dated to be replayed later.
3. **HMAC-SHA256 of `timestamp + "." + body`** matches, compared in **constant time**.
The check is on the **exact bytes received** (the controller takes `byte[]`), because parsing and re-writing
JSON can change it. With no secret configured, *every* callback is refused: failing closed.

### What the HTTP answer tells the sender
| Answer | Meaning | Sender should |
|---|---|---|
| 401 | Not signed properly or too old | Not retry: this is not the payments system |
| 400 | Signed, but not understood | Not retry |
| 200 | Handled (including a repeat, and a payment that is not ours) | Stop sending it |
| 5xx | Our side failed (database down, amount does not match) | Send it again |

### Handling the same callback many times
`HandlePaymentEvent` is built to be safe when callbacks repeat, arrive late, or arrive twice at once:
- The request row is **locked** (`select ... for update`), so two copies are handled one after the other.
- **The first final outcome wins.** A repeat, or a later contradictory callback, is a harmless `DUPLICATE`.
- The ledger entry's idempotency key is `zamsim:<transaction id>`, the same for every callback about the same
  payment. Even if everything above failed, the database could not hold the entry twice (rule 5).
- A **confirmed amount that differs from the amount asked for** is not recorded. It is reported as a server
  error so the sender retries and a person looks. Money moved that we did not ask to move.
- The whole callback is **one database transaction**: it is applied completely or not at all.

### Test doubles that misbehave
The 500-deposit test makes *Msika* unreliable too, with a test-only servlet filter: some callbacks are refused
with 503 before being read, and some are **fully applied but acknowledged with a 500** (the sender never
learns it worked and sends again). That second case is the nastiest, and the one `DUPLICATE` exists for.

## Where it sits in the architecture

```
agent deposit --> PaymentService --(1) save request (SENT)
                                 --(2) PaymentsPort.cashIn --> ZamsimPaymentsAdapter --HTTP--> zamsim
                                 --(3) attach transaction id

zamsim --signed callback--> PaymentCallbackController --> CallbackVerifier (signature, timestamp)
                                                      --> PaymentService.handle (one transaction, one council)
                                                          --> HandlePaymentEvent --> RecordEntry --> ledger_entry
```

## Step by step

1. Branched from M-13, marked the card ◐.
2. **`msika-core`:** `PaymentsPort`, `PaymentRequest`, `PaymentReference`, `PaymentEvent`,
   `PaymentRequestRepository`, `HandlePaymentEvent` (no Spring, no HTTP: ArchUnit still passes).
3. **Migration `V7`:** `payment_request`, with `council_id`, a row-level security policy, a composite foreign
   key to the stall, and checks (`SENT` has no completion time; `FAILED` has a reason).
4. **`msika-api`:** `JdbcPaymentRequestRepository`, `ZamsimPaymentsAdapter`, `CallbackVerifier`,
   `PaymentService`, `PaymentCallbackController`, `PaymentsConfiguration`; the callback path is `permitAll`
   in the security rules because the payments system is not a signed-in user. Its signature is its login.
5. **Tests:** unit tests with fakes; 12 HTTP tests of the endpoint; the 500-deposit run.
6. **Sabotaged** the verifier (always true, no time window) and the duplicate defences (no final check, key
   per callback instead of per transaction); the right tests failed each time.

## Files touched

| File | What changed | Why |
|---|---|---|
| `platform/msika-core/.../payments/*` | New | Port, request, reference, event, the use case |
| `platform/msika-api/.../db/migration/V7__payment_requests.sql` | New | Table, RLS, checks |
| `platform/msika-api/.../payments/*` | New | Adapter, verifier, service, endpoint, wiring |
| `platform/msika-api/.../persistence/JdbcPaymentRequestRepository.java` | New | Postgres storage |
| `platform/msika-api/.../security/SecurityConfiguration.java` | One rule | Callback path is open, signature-checked |
| `platform/msika-api/build.gradle.kts` | `integrationTest` builds zamsim's jar | The big test starts the real zamsim |
| `platform/msika-core/src/test/.../ledger/LedgerFakes.java` | Made public | Reused by the payment tests |
| `platform/msika-*/src/test/...payments/*` | New | Proof |
| `infra/docker-compose.yml`, `infra/.env.example` | Payment settings, no values | Public repo: no secrets |

## How we know it works

| Done item | Proof |
|---|---|
| Signature and timestamp checked | `badSignature`, `tamperedBody`, `replayWindow` (6 minutes old and 6 in the future refused, 4 minutes accepted) |
| Every callback goes through `RecordEntry` | `recorded` (one `DEPOSIT`, `DEPOSITED`, key `zamsim:<txn>`); unit tests in `HandlePaymentEventTest` |
| No duplicates | `duplicateInARow`, `duplicateAtTheSameTime` (16 at once: exactly one `RECORDED`), unit crash-recovery test |
| 500 deposits, all faults on, exact ledger, nothing lost | `DepositsUnderFaultsTest`: for every request, zamsim's own final status and amount agree with Msika's; exactly one ledger entry per succeeded payment and none for failed; no extra entries; each stall's ledger sum equals the trader wallet balance in zamsim; no DEAD callbacks |
| The test is not easy | The same test asserts the faults really fired: payments failed, duplicate callbacks were sent, callbacks were refused before reading, and others applied but not acknowledged |

## Rebuild it yourself

1. Write an interface for "move money", and an adapter that implements it with `RestClient`.
2. Save a row *before* calling out; use the row's id in the idempotency key and the reference.
3. Build an endpoint that takes `byte[]`, checks `timestamp + "." + body` against an HMAC, and only then parses.
4. Lock the row, handle the first outcome, and treat later ones as repeats.
5. Start a second program from a test (`ProcessBuilder`) and make both misbehave on purpose.

## Mistakes to avoid

- **Matching a callback by the payments system's id.** It may arrive before you have learned that id.
- **Calling the payments system inside a database transaction.** A slow reply holds locks.
- **Parsing the body before checking the signature**, or checking a re-serialised copy.
- **Answering 200 to a callback you failed to apply** (it is never sent again), or 500 to one you simply do
  not recognise (it is sent forever).
- **Trusting the amount in a callback.** Compare it with what you asked for.
- **A test that passes because nothing went wrong.** Assert that the faults happened.

## Not done here (on purpose)

- **The deposit lifecycle** (`PENDING`, `CONFIRMED`, `CANCELLED`, `DISPUTED`) and the REST endpoint: M-15.
- **Nightly reconciliation** for callbacks that never arrive at all (zamsim gave up): M-16. Today a request
  stuck in `SENT` is visible with `listSent`, but nothing sweeps it yet.
- **Rotating the callback secret**, and one secret per council: later, with real keys (M-44, M-45).
