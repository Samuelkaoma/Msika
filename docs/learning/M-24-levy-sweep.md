# M-24: Levy sweep

Card: `M-24` · Branch: `feature/M-24-levy-sweep` · Depends on: M-15, M-22, M-23

## What we are building, in one paragraph

This is the promise Msika makes to councils: a trader who deposits never falls into levy arrears. When an agent's
deposit is confirmed, Msika pays the trader's unpaid levy days out of it, with the consent they gave at
registration. The money moves from the trader's wallet to the council's collection wallet in the payments system,
and then the levy authority (eLevy, simulated by elevysim) is told so it credits the trader's account. Msika never
holds the money.

## New ideas in this lesson

- **A multi-step payment as a state machine**
  - **What it is:** `payment_instruction` moves `PENDING → SENT → MOVED → DONE`, or to `FAILED`. Each step is done
    by whoever learns something (the deposit's confirmation, the worker, a callback) and each is safe to repeat.
  - **Why we need it here:** two outside systems must both act, either can fail at any moment, and a server can
    restart between them. The row says exactly where things stand.
- **Idempotency keys chosen so a retry cannot pay twice**
  - The transfer's key is derived from the payment request, so zamsim moves the money once. The eLevy top-up's key
    is the payments transaction id, so eLevy credits once for each real movement of money.
- **Reserving before paying**
  - The chosen levy days are marked with the instruction when it is created, in the same transaction as the
    deposit's confirmation, so two deposits arriving together cannot both pay the same day. A failed transfer
    un-marks them.
- **One port per outside system**
  - `LevyAuthorityPort` in `msika-core`, `ElevysimLevyAdapter` in `msika-api` (AGENTS.md rule 6).

## Where it sits in the architecture

```
payment callback (deposit) → DepositOutcomes → DepositEventsHub ─┬─ DepositSmsNotifier (texts)
                                                                 └─ LevySweep.confirmed: reserve days, PENDING
LevySweep worker: PENDING → PaymentsPort.transfer (trader wallet → council wallet) → SENT
payment callback (transfer) → HandlePaymentEvent: LEVY_PAYMENT, VERIFIED → LevySweep.succeeded → MOVED
LevySweep worker: MOVED → LevyAuthorityPort.topUp (key = payments txn) → DONE
```

## How we know it works

`LevySweepApiTest`, with the payments system and eLevy scripted and callbacks signed and posted for real:
- `fullSweep`: three K5 days paid from a K450 deposit; `DEPOSIT` and `LEVY_PAYMENT` on the ledger; eLevy credited
  K15 once; nothing sent twice.
- `smallerThanOwed`: K12 pays the two oldest days; K3 pays nothing.
- `transferFails`: nothing recorded or credited, the days are unpaid again, and the next deposit pays them.
- `authorityFailsAfterMoney`: eLevy down twice after the money moved; retried; credited once; money moved once.
- `noConsent`: nothing swept. `duplicateConfirmation`: one sweep.
- `LevySweepPlanTest`: oldest whole days first, in order, never skipping.

## Mistakes to avoid

- Calling the payments system inside the callback's transaction: the worker does it afterwards.
- Using a new key for each eLevy retry: the trader would be credited twice.
- Paying part of a day: a charge would be neither paid nor unpaid.
