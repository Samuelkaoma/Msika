# M-15: deposits, and sales and supplier payments from the payments system

Card: `M-15` · Branch: `feature/M-15-deposits` · Depends on: M-14, M-10

## What we are building, in one paragraph

An agent takes a trader's cash at closing time and asks for it to be paid into the trader's wallet. Msika
now models that as a **deposit** with a life: `PENDING`, then `CONFIRMED`, `FAILED`, `CANCELLED` or
`DISPUTED`. The deposit follows the payment request from M-14: the payments system's callback decides what
happens to it. The same callback endpoint also learns about payments Msika did not start (a customer paying
a stall, a stall paying a supplier) and records them as `VERIFIED`. All of it is reachable through a REST
endpoint for now; SMS (M-19) will call the same code.

## New ideas in this lesson

### A state machine
A deposit can only move along allowed arrows:

```
PENDING --payment confirmed--> CONFIRMED --trader says NO--> DISPUTED
   |  \--payment failed------> FAILED
   \--agent cancels---------> CANCELLED --payment confirmed anyway--> DISPUTED
```
Anything not on the picture is refused (HTTP 409). Writing the picture first, then a test for every arrow
and for every arrow that is *not* there, is the whole method.

### An atomic status change
`update deposit set status = :to ... where id = :id and status = :from`. If the deposit is no longer in
`from`, zero rows change and the caller knows it lost. When a cancel and a confirmation arrive together,
Postgres makes the second wait for the first and then re-checks the status, so exactly one wins. We run
that race 25 times in a test.

### The callback after the cancel
The agent cancels, but Msika cannot recall a payment already in flight. If the payments system then
confirms, **the money did move**. So the ledger records it (the ledger follows what happened, not what
anyone meant), and the deposit becomes `DISPUTED` with the reason `PAID_AFTER_CANCEL`, so a person notices.
If the payments system instead says it failed, the deposit stays `CANCELLED`: everyone agrees nothing happened.

### A hook: `PaymentOutcomes`
`HandlePaymentEvent` (M-14) does not know what a deposit is. After it settles a payment request it calls
`PaymentOutcomes`, and `DepositOutcomes` moves the deposit, in the **same database transaction**. The levy
sweep and loan repayments can plug in the same way later.

### Idempotency-Key on a command
The agent's command carries `Idempotency-Key`. Sending it again returns the same deposit (200, not 202) and
makes no second payment, so a lost reply can never become two payments for one handful of cash. If the
payments system was unreachable the first time (we answer 502), the deposit is saved `PENDING`, and sending
the same command again asks the payments system again with the same payments key.

### The deposit reference
Four characters from `23456789ABCDEFGHJKMNPQRSTUVWXYZ` (no 0/O, 1/I/L), unique per council per day, that the
trader quotes: `NO 8F2K`. The database enforces the uniqueness; the code retries with a new reference on a
clash.

### The wallet registry, and a way across the security wall
Msika needs to know which payments-system wallet is whose: a stall's `TRADER` wallet (deposits go in), its
`MERCHANT` wallet (sales come in), an agent's float wallet, and `SUPPLIER` wallets. That is the
`payments_wallet` table, with row-level security like every other council table.
A callback names wallets, not councils, so Msika must learn the council *before* it can open that council's
data. One narrow `SECURITY DEFINER` function, `payments_find_wallet`, returns the owner of ONE wallet whose
id the caller already knows (the same idea as the sign-in lookups in M-09).

### VERIFIED against DEPOSITED
A deposit is cash the agent says was handed over: `DEPOSITED`. A customer paying a stall's merchant wallet,
or a stall paying a supplier, is something the payments system itself vouches for: `VERIFIED`. Figures shown
later keep them apart (rule 3).

## Where it sits in the architecture

```
REST: POST /deposits (Idempotency-Key) --> RequestDeposit: save Deposit(PENDING) + PaymentRequest (one transaction)
                                       --> PaymentsPort.cashIn (outside any transaction)
signed callback --> HandlePaymentEvent --> RecordEntry (DEPOSIT, DEPOSITED) + PaymentOutcomes --> deposit CONFIRMED / FAILED / DISPUTED
                \-> RecordWalletPayment --> RecordEntry (MERCHANT_SALE / SUPPLIER_PAYMENT, VERIFIED)
```

## Step by step

1. Branched from M-14, marked the card ◐.
2. **Core:** `Deposit`, `DepositStatus`, `DepositReference`, `DepositRepository`, `RequestDeposit`,
   `ChangeDeposit` (cancel, dispute), `DepositOutcomes`, `PaymentOutcomes`, the wallet registry
   (`WalletOwner`, `WalletRegistry`, `RegisterWallet`) and `RecordWalletPayment`.
3. **Migration `V8`:** `payments_wallet`, `payments_find_wallet`, `deposit`, both with `council_id` and a
   row-level security policy, composite foreign keys, and checks that mirror the rules (a `CONFIRMED`
   deposit has its ledger entry, a `FAILED` one never does).
4. **api:** JDBC repositories, `DepositService`, `DepositController`, `DepositConfiguration`; the callback now
   reads `kind` and the two wallet ids; a 502 answer when the payments system cannot be asked.
5. **Tests:** 28 unit tests for the lifecycle and wallets; 14 HTTP tests including the cancel race.
6. **Sabotaged** the late-payment rule; the right tests failed.

## Files touched

| File | What changed | Why |
|---|---|---|
| `platform/msika-core/.../deposit/*`, `wallet/*`, `payments/PaymentOutcomes`, `RecordWalletPayment` | New | The lifecycle and the registry |
| `platform/msika-core/.../payments/HandlePaymentEvent`, `PaymentEvent` | Extended | Calls the hook; carries kind and wallets |
| `platform/msika-core/.../agent/AgentRepository` | `find` added | A deposit names an agent |
| `platform/msika-api/.../db/migration/V8__deposits_and_wallets.sql` | New | Tables, RLS, the lookup function |
| `platform/msika-api/.../deposit/*`, `persistence/Jdbc{Deposit,Wallet}*` | New | REST and storage |
| `platform/msika-api/.../payments/*` | Extended | Wallet payments, 502 |
| `.githooks/commit-msg`, `scripts/check-no-coauthor.sh`, CI, `AGENTS.md`, `CONTRIBUTING.md` | New rule | No co-author trailers in commits |

## How we know it works

| Done item | Proof |
|---|---|
| Each state change | `confirmed`, `failed`, `paidAfterCancel`, `failedAfterCancel`, `dispute`, and the "cannot" tests for every arrow that is not allowed (unit and HTTP) |
| A callback that arrives after the agent cancelled | `paidAfterCancel` (ledger has the entry, deposit `DISPUTED`), `failedAfterCancel`, and `cancelRacesConfirmation` (25 races: one wins, one ledger entry each, never `CANCELLED` with money) |
| Merchant sales and supplier payments as `VERIFIED` | `merchantSale`, `supplierPayment`; unit tests for every case that must be ignored |
| Idempotency | `idempotentStart`, `unreachable` (502, retry with the same key, payments system asked with the same payments key) |

## Rebuild it yourself

1. Draw the state arrows, then write one test per arrow and per missing arrow.
2. Write the status change as a single conditional `UPDATE` and check the row count.
3. Run two threads at the same change, 25 times, and assert the one allowed outcome.
4. Add a `SECURITY DEFINER` function that answers one narrow question across tenants.

## Mistakes to avoid

- **Reading the status, then writing it.** Another request changes it in between. Make the write conditional.
- **Ignoring money that moved after a cancel.** The ledger would be wrong. Record it and flag it.
- **Using the wallet registry table directly for the callback.** Row-level security hides it until a council is
  chosen; the callback has to find the council first.
- **A test that repeats a callback with a new timestamp.** A real repeat is byte-identical.

## Not done here (on purpose)

- **SMS commands and the trader's message** (`NO 8F2K`): M-19. The service they will call exists.
- **Resolving a `DISPUTED` deposit** (the council's decision and any reversal): later, with the dashboard (M-27).
- **Nightly reconciliation** of the ledger against payments statements: M-16.
