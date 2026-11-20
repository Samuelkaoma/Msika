# M-13: zamsim callbacks and fault injection

Card: `M-13` · Branch: `feature/M-13-zamsim-callbacks` · Depends on: M-12

## What we are building, in one paragraph

Real payment systems are not polite. They reply late, reply twice, reply out of order, fail at random, and
need you to approve things on a phone. Msika must cope with all of that, so `zamsim` now does it too, on
demand. It sends **signed callbacks** (webhooks) with **retries**, can be told to misbehave in four ways
(**fault injection**), and supports **request-to-pay**, a payment that waits for the customer to approve
it. Nothing here is for Msika to use yet; M-14 builds the receiving side and proves it copes.

## New ideas in this lesson

### Callbacks (webhooks)
- **What it is:** instead of Msika asking "did it go through?" again and again, zamsim *calls Msika's URL*
  when something happens. Msika's side is a web endpoint that receives the call.
- **Why it matters:** the call can be lost, repeated or delayed, so the receiver must be built for that.

### Signing: how a receiver knows a callback is real
- Anyone can send a request to Msika's callback URL. The **signature** proves it came from zamsim.
- **HMAC-SHA256:** a way to make a fingerprint of a message using a **shared secret** that only the two
  sides know. Change one character of the message, or use a different secret, and the fingerprint is
  completely different. We send it as `X-Signature: sha256=<64 hex characters>`.
- **The timestamp is signed too:** we sign `timestamp + "." + body`, not just the body. Otherwise an
  attacker could record a genuine callback and replay it a week later. Msika will reject any timestamp
  more than five minutes old (M-14), and because the timestamp is inside the signature, an attacker cannot
  simply change it.
- **Constant-time comparison:** when checking a signature we use `MessageDigest.isEqual`, which takes the
  same time however many characters match, so an attacker cannot guess a signature a character at a time
  by timing it.
- Tests prove it fails if the body, the timestamp or the secret is wrong.

### The outbox: never lose a callback, never send one for something that did not happen
- **The trap:** pay in the database, *then* send the callback. If the server dies in between, the payment
  happened but nobody is told. Send first and the payment fails, and you told a lie.
- **The pattern:** write the callback to a table **in the same database transaction** as the payment. Both
  commit together or neither does. A separate worker later reads the table and sends. A test proves it:
  a payment that is rolled back leaves no callback behind.
- A repeated `Idempotency-Key` queues no second callback.

### At-least-once delivery and retries
- The worker **claims** a delivery (bumps its attempt count and pushes its next attempt a minute forward)
  before sending. Two workers never send the same one, and if the worker crashes mid-send the delivery
  simply comes back round. So delivery is **at least once**: sometimes twice. That is how real systems
  behave, and the reason Msika must be **idempotent**.
- A failure is anything but a 2xx answer: a 500, a timeout, a refused or dropped connection. A failed
  delivery waits longer each time (1s, 2s, 4s, 8s ... called *exponential backoff*) so a struggling receiver
  is not hammered. After the last allowed attempt it is marked **DEAD** and the reason is kept.
- `FOR UPDATE SKIP LOCKED` lets several workers share one queue without waiting on each other.

### Fault injection
Four settings, changed over HTTP and effective at once. Rates are 0 (never) to 1 (always); tests use exactly
0 or 1 so they are predictable.

| Setting | What it makes zamsim do |
|---|---|
| `failureRate` | Fail a cash-in, transfer or approved request with `SIMULATED_FAILURE`, moving nothing |
| `callbackDelayMs` | Wait this long before the first attempt to deliver a callback |
| `duplicateCallbackRate` | Deliver a callback twice, with the **same** callback id |
| `reorderRate` (+ `reorderExtraDelayMs`) | Hold one callback back so a *later* payment's callback arrives first |

Admin fund and withdraw are never injected, because they set up and clean up demos.

### Request-to-pay
- A **merchant asks a customer to pay**. The request is `PENDING`: nothing moves, and there are no ledger
  lines. The customer later **approves** (the payment is made now, or fails for lack of money) or
  **rejects**, or it **expires** if nobody answers.
- Approve and reject are **safe to repeat**. A request that was rejected or has expired cannot be approved.
- Eight threads approving the same request pay exactly once (the row is locked).
- A callback is sent only when the request is *answered*, never while it waits.

### A database rule for the new state
`check ((status = 'PENDING') = (completed_at is null))`: a transaction is complete exactly when it is no
longer pending. Two of our own older tests broke when we added it, because they inserted half-finished rows
by hand. That is the rule doing its job.

## Where it sits in the architecture

```
zamsim
  wallet/    WalletService (payments + request-to-pay), controllers
  fault/     FaultSettings, FaultService (the dice)
  callback/  CallbackSigner, CallbackService (the outbox), CallbackWorker (sends), CallbackConfig
  db         V3: fault_settings, callback_delivery, request-to-pay columns

   payment --(same DB transaction)--> callback_delivery row --worker--> POST Msika /api/v1/payments/callback
```
Msika must not import any of this. `CallbackSigner` documents the exact format, and Msika's checker in M-14
is written against that description.

## Step by step

1. Branched from M-12, marked the card ◐.
2. **Migration `V3`:** request-to-pay columns and states, `fault_settings`, the `callback_delivery` outbox.
3. **Faults:** `FaultSettings` (validated), `FaultService`.
4. **Callbacks:** `CallbackSigner`, `CallbackService` (writes the outbox, applies delay, reordering and
   duplication), `CallbackWorker` (claims, sends, retries, gives up).
5. **`WalletService`** now settles in one place (`settle`), rolls the failure dice there, queues the
   callback, and handles request-to-pay.
6. **API:** `/v1/request-to-pay` (ask, list pending, approve, reject), `/v1/admin/faults`,
   `/v1/admin/callbacks`.
7. **Tests:** a real HTTP receiver that can answer 500, drop the connection or accept.
8. **Sabotaged** the signer (no timestamp in the signature) and the worker (gives up at once); the right
   tests failed.

## Files touched

| File | What changed | Why |
|---|---|---|
| `platform/zamsim/.../db/migration/V3__callbacks_faults_and_request_to_pay.sql` | New | Tables and rules |
| `platform/zamsim/.../fault/*`, `callback/*` | New | Faults, signing, outbox, worker |
| `platform/zamsim/.../wallet/WalletService.java`, `Wallets.java`, `WalletController.java` | Extended | Settlement, request-to-pay, new fields |
| `platform/zamsim/.../AdminController.java`, `RequestExpirySweeper.java` | New | Admin endpoints, expiry |
| `platform/zamsim/src/test/...` | `CallbackDeliveryTest`, `RequestToPayTest`; rules test updated | Proof |
| `infra/docker-compose.yml`, `infra/.env.example` | Callback settings, no values | Public repo: no secrets |

## How we know it works

| Done item | Proof |
|---|---|
| The signature | `signedCallback` (a real receiver verifies the header with the shared secret), `signatureDetectsTampering` (body, timestamp, secret, missing, wrong) |
| The retry after a 500 from the receiver | `retryAfterServerErrors` (500, 500, 200: same callback id and body each time, attempts 1,2,3, then delivered and not sent again), `droppedConnectionIsRetried`, `givesUpAfterTheMaximumAttempts` (DEAD after exactly 4) |
| Each fault mode | `faultDelay`, `faultDuplicate`, `faultOutOfOrder` (the second payment's callback arrives first), `faultFailureRate` (and on approval), plus `faultsApi` for setting them |

## Rebuild it yourself

1. Compute an HMAC-SHA256 of `"123." + body` with a secret and check it on the other side.
2. Write a table of pending messages in the same transaction as the thing they describe.
3. Write a loop that claims rows with `FOR UPDATE SKIP LOCKED`, sends them, and reschedules failures with a
   growing delay.
4. Make a tiny HTTP server (`com.sun.net.httpserver.HttpServer`) that answers 500 twice and then 200.
5. Add a setting that makes one message arrive late, and see another overtake it.

## Mistakes to avoid

- **Sending the callback outside the payment's transaction.** You lose or invent callbacks.
- **Signing only the body.** A recorded callback can be replayed.
- **Comparing signatures with `equals`.** Use a constant-time comparison.
- **Assuming a callback arrives once, in order, quickly.** It does not.
- **Retrying instantly and forever.** Back off, and give up with a recorded reason.
- **Putting the signing secret in the repository.** Environment variable only.

## Not done here (on purpose)

- **Msika's receiving endpoint** and its signature and timestamp check: M-14.
- **The console** for approving requests and moving the fault sliders: M-20.
- **A callback while a request is still pending.** Only the answer is reported.
- **Authentication on the admin endpoints.** zamsim is a private-network simulator.
