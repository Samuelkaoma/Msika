# R-01: Fixes from the first review

Not a roadmap card: three small fixes found when M-01 to M-15 were reviewed before merging.
Branch: `fix/review-follow-ups` · Depends on: M-15

## What we are fixing, in one paragraph

Before a wallet is registered, Msika now asks the payments system whether the wallet really is that
kind of wallet and, for trader and agent wallets, whether it is registered to that person's phone.
A payment callback with no amount (or a nonsense one) is now refused cleanly instead of being retried
forever. And a customer's payment whose free-text reference happens to look like one of Msika's own
references is still recorded as a sale instead of being dropped.

## New ideas in this lesson

- **Trust but verify a claim against its source.**
  - **What it is:** when someone tells you "this thing is mine", check with whoever issued the thing.
  - **Why we need it here:** wallet ids are global. Whoever registers an id first gets every payment
    into it recorded on their council's ledger. A council admin could claim another council's
    trader's wallet by a typo or on purpose. The payments system knows whose phone each wallet is on.
  - **Tiny example:** `if (!wallet.msisdn().equals(trader.phone().e164())) throw new InvalidInputException(...)`.
- **Write, then check, in one transaction.**
  - **What it is:** do the insert, then the check, inside the same transaction. If the check fails,
    the exception rolls the insert back, so nothing is stored.
  - **Why we need it here:** the existing answers (a duplicate is 409, a bad shape is 400) come from
    the insert, and they should still come first. Checking after the insert keeps them, and the
    rollback means a wallet that fails the check never stays in the table.
- **Poison message.**
  - **What it is:** a message that fails every time it is processed. If the receiver answers "try
    again" (a 5xx), the sender retries it forever.
  - **Why we need it here:** a callback with no `amountNgwee` used to be read as 0. The ledger then
    refused 0 (an entry must be above zero), which answered 500, which the payments system retries.
    The fix is to say 400 at the door: "this will never work, stop sending it".
- **Fall through, don't give up.**
  - **What it is:** if the first way of matching something fails, try the next way before deciding
    it is not yours.
  - **Why we need it here:** a reference is free text the payer can type. Looking like ours is not
    the same as being ours. If no request matches, the wallets may still say it is a sale.

## Where it sits in the architecture

```
council admin ──POST /wallets──► DepositService.registerWallet
                                   1. PaymentService.findWallet  ──HTTP──► payments system (outside any transaction)
                                   2. in one council transaction:
                                        RegisterWallet.execute      (shape, stall, agent, insert)
                                        VerifyWalletOwnership.check (kind, phone)  ── fails? whole transaction rolls back
```

- `msika-core`: the new `PaymentsWallet` record, `PaymentsPort.findWallet`, `TraderRepository.find`,
  and the new use case `VerifyWalletOwnership`. No Spring, no HTTP (AGENTS.md rule 7).
- `msika-api`: the zamsim adapter's `findWallet` (`GET /v1/wallets/{id}`), the wiring, and the two
  callback fixes in `PaymentCallbackController` and `PaymentService`.
- Rules protected: rule 6 (outside systems only through ports), rule 4 (one council's ledger never
  gets another council's money), rule 5 (callbacks that cannot succeed must not loop).

## Step by step

1. Added `PaymentsWallet` (id, kind, phone) and `findWallet` to the port, so Msika can ask the
   payments system about a wallet without knowing which payments system it is.
2. Implemented it in `ZamsimPaymentsAdapter`: 404 or 400 from zamsim means "no such wallet", anything
   else is "payments unavailable" (the API answers 502). zamsim's own wallet types are mapped to
   Msika's kinds in the adapter, so the core never sees zamsim's names.
3. Added `TraderRepository.find`, needed to get the phone of the trader who holds a stall.
4. Wrote `VerifyWalletOwnership`: the wallet must exist and be the right kind; a TRADER wallet must be
   on the phone of the stall's current holder; an AGENT wallet on the agent's phone. Merchant and
   supplier wallets are checked for kind only (see "Mistakes to avoid").
5. Changed `DepositService.registerWallet` to look the wallet up first, then register and check in
   one transaction.
6. In `PaymentCallbackController.parse`, refused an `amountNgwee` that is missing, not a whole
   number, or not above zero.
7. In `PaymentService.handle`, let a reference that parses but matches no request fall through to
   the wallet check.

## Files touched

| File | What changed | Why |
|---|---|---|
| `msika-core/.../payments/PaymentsWallet.java` | new | What the payments system says about a wallet |
| `msika-core/.../payments/PaymentsPort.java` | `findWallet` | Ask about a wallet through the port |
| `msika-core/.../wallet/VerifyWalletOwnership.java` | new | The ownership rules |
| `msika-core/.../trader/TraderRepository.java` | `find` | The stall holder's phone |
| `msika-api/.../payments/ZamsimPaymentsAdapter.java` | `findWallet` | `GET /v1/wallets/{id}` on zamsim |
| `msika-api/.../payments/PaymentsConfiguration.java` | unconfigured port refuses lookups too | Fail closed |
| `msika-api/.../payments/PaymentService.java` | `findWallet`; fall through on no match | Fixes 1 and 3 |
| `msika-api/.../payments/PaymentCallbackController.java` | amount check | Fix 2 |
| `msika-api/.../deposit/DepositService.java`, `DepositConfiguration.java` | lookup, register, check | Fix 1 |
| `msika-api/.../persistence/JdbcTraderRepository.java` | `find` | Fix 1 |

## How we know it works

- `VerifyWalletOwnershipTest` (unit): the holder's phone is accepted; someone else's phone, no phone,
  an empty stall, the wrong kind, an unknown wallet are refused; refusal messages name no phone number;
  merchant and supplier are checked for kind only.
- `DepositApiTest` (integration, real Postgres):
  - an unknown wallet is 400 and leaves no row;
  - a trader wallet on a stranger's phone or of the wrong kind is 400 and leaves no row; an agent
    wallet on another agent's phone is 400, and 201 once it is on the right phone;
  - a payments outage during the lookup is 502 and leaves no row;
  - callbacks with a missing, zero, negative, fractional or quoted amount are 400 and nothing reaches
    the ledger;
  - a sale with a look-alike reference is recorded as a `MERCHANT_SALE`.

## Rebuild it yourself

1. Add a method to a port and implement it in a fake first, then in the real adapter. Which tests
   break when you add it, and why?
2. Write the "write, then check" pattern: an insert followed by a check that throws. Prove with a test
   that the row is gone afterwards.
3. Send a callback with `"amountNgwee": "12000"` (a string). What did the old code do with it?

## Mistakes to avoid

- **Calling the payments system inside a database transaction.** A slow reply would hold locks. The
  lookup happens before the transaction, and its answer is passed in.
- **Putting phone numbers in error messages.** They reach the API caller and the logs. The messages
  say whose phone was expected, never the number.
- **Over-claiming.** In zamsim one phone has one wallet, so a merchant wallet cannot share the
  trader's phone, and suppliers are not people Msika knows. For those two kinds this check proves the
  wallet exists and is the right kind, not who owns it. Proving that needs the real payments
  system's owner records (card M-46).
